package com.blackcore.callblind

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BlindAccessibilityService : AccessibilityService() {

    private var blindView: View? = null
    private var sideTabView: View? = null
    private var isTabExpanded = false
    private var wasAutoActivated = false
    private var pulseAnimator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapseSideTab() }

    private var isDraggingTab = false
    private var sideTabOnLeft = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f
    private var longPressRunnable: Runnable? = null
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private var isDraggingBadge = false
    private var badgeInitialTX = 0f
    private var badgeInitialTY = 0f
    private var badgeTouchX = 0f
    private var badgeTouchY = 0f
    private var badgeLongPress: Runnable? = null

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showSideTab()
        if (isAutoMode()) {
            registerCallStateListener()
        }
    }

    private fun isAutoMode(): Boolean {
        val prefs = getSharedPreferences("shieldtap_prefs", Context.MODE_PRIVATE)
        return prefs.getString("mode", "basic") == "plus" &&
            prefs.getBoolean("plus_purchased", false)
    }

    fun onModeChanged() {
        if (isAutoMode()) {
            registerCallStateListener()
        } else {
            unregisterCallStateListener()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun toggleBlind() {
        if (isBlindActive) {
            removeBlind()
            showSideTab()
        } else {
            wasAutoActivated = false
            hideSideTab()
            showBlind()
        }
    }

    // ── Call State Detection ──

    private fun registerCallStateListener() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
            telephonyCallback = callback
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in API 31")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            phoneStateListener = listener
        }
    }

    private fun unregisterCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                telephonyManager?.unregisterTelephonyCallback(it)
            }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
        telephonyCallback = null
        phoneStateListener = null
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!isBlindActive) {
                    wasAutoActivated = true
                    hideSideTab()
                    showBlind(forceCallStyle = true)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isBlindActive && wasAutoActivated) {
                    wasAutoActivated = false
                    removeBlind()
                    showSideTab()
                }
            }
        }
    }

    fun registerCallStateFromActivity() {
        registerCallStateListener()
    }

    fun stopService() {
        removeBlind()
        hideSideTab()
        unregisterCallStateListener()
        disableSelf()
    }

    // ── Side Tab ──

    private fun hGravity() = if (sideTabOnLeft) Gravity.START else Gravity.END

    private fun showSideTab() {
        if (sideTabView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences("shieldtap_prefs", Context.MODE_PRIVATE)
        sideTabOnLeft = prefs.getBoolean("side_tab_left", false)

        val tab = FrameLayout(this)

        val strip = View(this).apply {
            background = stripDrawable(false)
        }
        tab.addView(strip, FrameLayout.LayoutParams(
            dpToPx(12f).toInt(),
            FrameLayout.LayoutParams.MATCH_PARENT,
            hGravity()
        ))

        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            text = ""
            visibility = View.GONE
        }
        tab.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val maxOffset = screenHeight / 2 - dpToPx(70f).toInt()

        tab.setOnTouchListener { v, event ->
            val p = tab.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2)
                    tab.getLocationOnScreen(loc)
                    dragStartX = loc[0]
                    dragStartY = loc[1]
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    isDraggingTab = false
                    // Long-press to enter drag mode (only in collapsed waiting state)
                    if (!isTabExpanded) {
                        longPressRunnable = Runnable {
                            isDraggingTab = true
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            strip.background = stripDrawable(true)
                            // Switch to absolute positioning for free left/right/up/down drag
                            p.gravity = Gravity.TOP or Gravity.START
                            p.x = dragStartX
                            p.y = dragStartY
                            wm.updateViewLayout(tab, p)
                        }
                        handler.postDelayed(longPressRunnable!!, 400L)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragTouchX
                    val dy = event.rawY - dragTouchY
                    if (!isDraggingTab && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (isDraggingTab) {
                        p.x = (dragStartX + dx.toInt()).coerceIn(0, screenWidth - p.width)
                        p.y = (dragStartY + dy.toInt()).coerceIn(0, screenHeight - p.height)
                        wm.updateViewLayout(tab, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (isDraggingTab) {
                        // Snap to nearest side edge, keep vertical position
                        val centerX = p.x + p.width / 2
                        sideTabOnLeft = centerX < screenWidth / 2
                        val centerY = p.y + p.height / 2
                        val yOffset = (centerY - screenHeight / 2).coerceIn(-maxOffset, maxOffset)
                        p.gravity = hGravity() or Gravity.CENTER_VERTICAL
                        p.x = 0
                        p.y = yOffset
                        wm.updateViewLayout(tab, p)
                        (strip.layoutParams as FrameLayout.LayoutParams).gravity = hGravity()
                        strip.requestLayout()
                        strip.background = stripDrawable(false)
                        saveSideTab(sideTabOnLeft, yOffset)
                        isDraggingTab = false
                    } else if (abs(event.rawX - dragTouchX) < touchSlop &&
                        abs(event.rawY - dragTouchY) < touchSlop &&
                        event.action == MotionEvent.ACTION_UP) {
                        if (isTabExpanded) {
                            handler.removeCallbacks(collapseRunnable)
                            toggleBlind()
                        } else {
                            expandSideTab()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        sideTabView = tab
        isTabExpanded = false

        val savedY = if (prefs.contains("side_tab_y")) {
            prefs.getInt("side_tab_y", 0)
        } else {
            (screenHeight * 0.17f).toInt()
        }

        val params = WindowManager.LayoutParams(
            dpToPx(24f).toInt(),
            dpToPx(96f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = hGravity() or Gravity.CENTER_VERTICAL
            y = savedY
        }

        wm.addView(tab, params)
    }

    private fun stripDrawable(active: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            if (active) {
                setColor(0x3340C4FF)
                setStroke(dpToPx(2.5f).toInt(), 0xFF40C4FF.toInt())
            } else {
                setColor(0x15FFFFFF)
                setStroke(dpToPx(1.5f).toInt(), 0xBB333333.toInt())
            }
            val r = dpToPx(8f)
            // Round only the inner corners (facing the screen center)
            cornerRadii = if (sideTabOnLeft) {
                floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            } else {
                floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }
    }

    private fun saveSideTab(onLeft: Boolean, y: Int) {
        getSharedPreferences("shieldtap_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("side_tab_left", onLeft)
            .putInt("side_tab_y", y)
            .apply()
    }

    private fun expandSideTab() {
        val tab = sideTabView ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = tab.layoutParams as WindowManager.LayoutParams
        params.width = dpToPx(120f).toInt()
        params.height = dpToPx(120f).toInt()
        wm.updateViewLayout(tab, params)

        val strip = (tab as FrameLayout).getChildAt(0)
        strip.visibility = View.GONE

        val r = dpToPx(16f)
        val btnBg = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xEE1A1A2E.toInt())
                setStroke(dpToPx(1f).toInt(), 0x40FFFFFF)
                cornerRadii = if (sideTabOnLeft) {
                    floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                } else {
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                }
            }
        }
        tab.addView(btnBg, 0, FrameLayout.LayoutParams(
            dpToPx(96f).toInt(),
            dpToPx(96f).toInt(),
            hGravity() or Gravity.CENTER_VERTICAL
        ))

        val circleSize = dpToPx(48f).toInt()
        val pulseCircle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x30FFFFFF)
                setStroke(dpToPx(2f).toInt(), 0x80FFFFFF.toInt())
            }
            alpha = 0.8f
        }
        tab.addView(pulseCircle, 1, FrameLayout.LayoutParams(
            circleSize, circleSize,
            hGravity() or Gravity.CENTER_VERTICAL
        ).apply {
            if (sideTabOnLeft) marginStart = dpToPx(24f).toInt()
            else marginEnd = dpToPx(24f).toInt()
        })

        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.5f, 1.3f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val s = anim.animatedValue as Float
                pulseCircle.scaleX = s
                pulseCircle.scaleY = s
                pulseCircle.alpha = 1.5f - s
            }
            start()
        }

        val label = tab.getChildAt(3) as TextView
        label.visibility = View.VISIBLE
        label.text = getString(R.string.side_tab_activate)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        (label.layoutParams as FrameLayout.LayoutParams).apply {
            width = dpToPx(96f).toInt()
            height = dpToPx(96f).toInt()
            gravity = hGravity() or Gravity.CENTER_VERTICAL
        }

        // Slide-in from the screen edge (negative = from the left)
        val slideFrom = if (sideTabOnLeft) -dpToPx(120f) else dpToPx(120f)
        btnBg.translationX = slideFrom
        pulseCircle.translationX = slideFrom
        label.translationX = slideFrom
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 264
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val tx = (anim.animatedValue as Float) * slideFrom
                btnBg.translationX = tx
                pulseCircle.translationX = tx
                label.translationX = tx
            }
            start()
        }

        isTabExpanded = true
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, 3000)
    }

    private fun collapseSideTab() {
        val tab = sideTabView ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = tab.layoutParams as WindowManager.LayoutParams
        params.width = dpToPx(24f).toInt()
        params.height = dpToPx(96f).toInt()
        wm.updateViewLayout(tab, params)

        pulseAnimator?.cancel()
        pulseAnimator = null

        val container = tab as FrameLayout
        while (container.childCount > 2) {
            container.removeViewAt(0)
        }

        container.getChildAt(0).apply {
            visibility = View.VISIBLE
            background = stripDrawable(false)
        }

        val label = container.getChildAt(1) as TextView
        label.visibility = View.GONE
        label.text = ""
        (label.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }

        isTabExpanded = false
    }

    private fun hideSideTab() {
        handler.removeCallbacks(collapseRunnable)
        pulseAnimator?.cancel()
        pulseAnimator = null
        sideTabView?.let {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            sideTabView = null
        }
        isTabExpanded = false
    }

    // ── Blind Overlay ──

    private fun isInCall(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun showBlind(forceCallStyle: Boolean = false) {
        if (blindView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val callStyle = forceCallStyle || isInCall()
        blindView = createBlindView(callStyle)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        wm.addView(blindView, params)
        isBlindActive = true
        showNotification()
    }

    private fun removeBlind() {
        blindView?.let {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            blindView = null
        }
        isBlindActive = false
        wasAutoActivated = false
        hideNotification()
    }

    private fun createBlindView(callStyle: Boolean): View {
        val container = FrameLayout(this)

        // Call: dim the screen (50% black). Lock: fully transparent so video
        // stays visible while touches are still blocked.
        val bg = View(this).apply {
            setBackgroundColor(if (callStyle) 0x80000000.toInt() else 0x00000000)
            setOnTouchListener { _, _ -> true }
        }
        container.addView(bg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val badge: View = if (callStyle) {
            TextView(this).apply {
                text = getString(R.string.app_name)
                setTextColor(0xDDFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                val padH = dpToPx(14f).toInt()
                val padV = dpToPx(8f).toInt()
                setPadding(padH, padV, padH, padV)
                background = GradientDrawable().apply {
                    setColor(0x1A1E88E5.toInt())
                    setStroke(dpToPx(1.5f).toInt(), 0xFF1E88E5.toInt())
                    cornerRadius = dpToPx(10f)
                }
                setOnClickListener { toggleBlind() }
            }
        } else {
            // Lock icon for general touch-lock use. Thin ring + faint dark
            // circle keep it findable on bright video (dial down later).
            ImageView(this).apply {
                setImageResource(R.drawable.ic_lock)
                alpha = 0.85f
                val p = dpToPx(11f).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x40000000)
                    setStroke(dpToPx(1.5f).toInt(), 0x80FFFFFF.toInt())
                }
                makeBadgeDraggable(this)
            }
        }

        val badgeSize = if (callStyle)
            FrameLayout.LayoutParams.WRAP_CONTENT else dpToPx(48f).toInt()
        container.addView(badge, FrameLayout.LayoutParams(
            badgeSize,
            if (callStyle) FrameLayout.LayoutParams.WRAP_CONTENT else dpToPx(48f).toInt(),
            Gravity.BOTTOM or Gravity.END
        ).apply {
            bottomMargin = dpToPx(80f).toInt()
            marginEnd = dpToPx(20f).toInt()
        })

        return container
    }

    /** Long-press to drag the lock badge; short tap dismisses. Position persists. */
    private fun makeBadgeDraggable(badge: View) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val minTx = -(screenW - dpToPx(68f))
        val maxTx = dpToPx(20f)
        val minTy = -(screenH - dpToPx(168f))
        val maxTy = dpToPx(60f)

        val prefs = getSharedPreferences("shieldtap_prefs", Context.MODE_PRIVATE)
        badge.translationX = prefs.getFloat("lock_badge_tx", 0f).coerceIn(minTx, maxTx)
        badge.translationY = prefs.getFloat("lock_badge_ty", 0f).coerceIn(minTy, maxTy)

        badge.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    badgeInitialTX = badge.translationX
                    badgeInitialTY = badge.translationY
                    badgeTouchX = event.rawX
                    badgeTouchY = event.rawY
                    isDraggingBadge = false
                    badgeLongPress = Runnable {
                        isDraggingBadge = true
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        v.alpha = 1f
                    }
                    handler.postDelayed(badgeLongPress!!, 400L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - badgeTouchX
                    val dy = event.rawY - badgeTouchY
                    if (!isDraggingBadge && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        badgeLongPress?.let { handler.removeCallbacks(it) }
                    }
                    if (isDraggingBadge) {
                        badge.translationX = (badgeInitialTX + dx).coerceIn(minTx, maxTx)
                        badge.translationY = (badgeInitialTY + dy).coerceIn(minTy, maxTy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    badgeLongPress?.let { handler.removeCallbacks(it) }
                    if (isDraggingBadge) {
                        prefs.edit()
                            .putFloat("lock_badge_tx", badge.translationX)
                            .putFloat("lock_badge_ty", badge.translationY)
                            .apply()
                        badge.alpha = 0.85f
                        isDraggingBadge = false
                    } else {
                        val dx = event.rawX - badgeTouchX
                        val dy = event.rawY - badgeTouchY
                        if (abs(dx) < touchSlop && abs(dy) < touchSlop &&
                            event.action == MotionEvent.ACTION_UP) {
                            toggleBlind()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ── Notification ──

    private fun showNotification() {
        createNotificationChannel()

        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, BlindToggleReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_blind)
            .setOngoing(true)
            .addAction(R.drawable.ic_blind, getString(R.string.notification_dismiss), stopPendingIntent)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun hideNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ── Util ──

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(collapseRunnable)
        unregisterCallStateListener()
        removeBlind()
        hideSideTab()
        instance = null
    }

    companion object {
        var instance: BlindAccessibilityService? = null
            private set
        var isBlindActive = false
            private set

        private const val CHANNEL_ID = "call_blind_channel"
        private const val NOTIFICATION_ID = 1
    }
}

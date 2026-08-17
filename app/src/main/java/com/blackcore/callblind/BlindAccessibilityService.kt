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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
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
        return prefs.getString("mode", "basic") == "plus"
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
                    showBlind()
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

    private fun showSideTab() {
        if (sideTabView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val tab = FrameLayout(this)

        val strip = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x15FFFFFF)
                setStroke(dpToPx(1.5f).toInt(), 0xBB333333.toInt())
                cornerRadii = floatArrayOf(
                    dpToPx(8f), dpToPx(8f), 0f, 0f,
                    0f, 0f, dpToPx(8f), dpToPx(8f)
                )
            }
        }
        tab.addView(strip, FrameLayout.LayoutParams(
            dpToPx(12f).toInt(),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END
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

        tab.setOnClickListener {
            if (isTabExpanded) {
                handler.removeCallbacks(collapseRunnable)
                toggleBlind()
            } else {
                expandSideTab()
            }
        }

        sideTabView = tab
        isTabExpanded = false

        val screenHeight = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            dpToPx(24f).toInt(),
            dpToPx(96f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            y = (screenHeight * 0.17f).toInt()
        }

        wm.addView(tab, params)
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

        val btnBg = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xEE1A1A2E.toInt())
                setStroke(dpToPx(1f).toInt(), 0x40FFFFFF)
                cornerRadii = floatArrayOf(
                    dpToPx(16f), dpToPx(16f), 0f, 0f,
                    0f, 0f, dpToPx(16f), dpToPx(16f)
                )
            }
        }
        tab.addView(btnBg, 0, FrameLayout.LayoutParams(
            dpToPx(96f).toInt(),
            dpToPx(96f).toInt(),
            Gravity.END or Gravity.CENTER_VERTICAL
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
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            marginEnd = dpToPx(24f).toInt()
        })

        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.5f, 1.3f).apply {
            duration = 1000
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
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
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
            background = GradientDrawable().apply {
                setColor(0x15FFFFFF)
                setStroke(dpToPx(1.5f).toInt(), 0xBB333333.toInt())
                cornerRadii = floatArrayOf(
                    dpToPx(8f), dpToPx(8f), 0f, 0f,
                    0f, 0f, dpToPx(8f), dpToPx(8f)
                )
            }
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

    private fun showBlind() {
        if (blindView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        blindView = createBlindView()

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

    private fun createBlindView(): View {
        val container = FrameLayout(this)

        val bg = View(this).apply {
            setBackgroundColor(0x80000000.toInt())
            setOnTouchListener { _, _ -> true }
        }
        container.addView(bg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val dismissBox = TextView(this).apply {
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

        container.addView(dismissBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            bottomMargin = dpToPx(80f).toInt()
            marginEnd = dpToPx(20f).toInt()
        })

        return container
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

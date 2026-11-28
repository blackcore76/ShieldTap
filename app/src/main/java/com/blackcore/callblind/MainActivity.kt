package com.blackcore.callblind

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("shieldtap_prefs", MODE_PRIVATE)
    }

    private lateinit var billing: BillingManager
    private var justPurchased = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BlindAccessibilityService.instance != null) {
            requestBatteryOptimizationExemption()
        }

        setContentView(R.layout.activity_main)

        billing = BillingManager(this) { entitled ->
            runOnUiThread { onEntitlementChanged(entitled) }
        }
        billing.start()

        findViewById<TextView>(R.id.tvHowToGuide).setOnClickListener {
            showHowToDialog()
        }

        findViewById<TextView>(R.id.tvUsageGuide).setOnClickListener {
            showUsageGuideDialog()
        }

        setupModeSelection()
        setupSideTabSize()
        setupSideTabPosition()
        updateUI()
    }

    private fun setupSideTabPosition() {
        val left = findViewById<TextView>(R.id.tvPosLeft)
        val right = findViewById<TextView>(R.id.tvPosRight)
        val height = findViewById<TextView>(R.id.tvPosHeight)
        fun style(tv: TextView, selected: Boolean) {
            tv.setTextColor(if (selected) 0xFF90CAF9.toInt() else 0x66FFFFFF)
            tv.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        fun render() {
            val onLeft = prefs.getBoolean("side_tab_left", false)
            style(left, onLeft)
            style(right, !onLeft)
        }
        fun choose(onLeft: Boolean) {
            val svc = BlindAccessibilityService.instance
            if (svc != null) svc.setSideTabLeft(onLeft)
            else prefs.edit().putBoolean("side_tab_left", onLeft).apply()
            render()
        }
        left.setOnClickListener { choose(true) }
        right.setOnClickListener { choose(false) }
        height.setTextColor(0xFF90CAF9.toInt())
        height.setOnClickListener {
            val svc = BlindAccessibilityService.instance
            val msg = when {
                svc == null -> R.string.move_need_service
                !svc.startMove() -> R.string.move_unavailable
                else -> R.string.move_start_toast
            }
            android.widget.Toast.makeText(this, getString(msg), android.widget.Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun setupSideTabSize() {
        val options = listOf(
            "large" to findViewById<TextView>(R.id.tvSizeLarge),
            "medium" to findViewById<TextView>(R.id.tvSizeMedium),
            "small" to findViewById<TextView>(R.id.tvSizeSmall)
        )
        fun render() {
            val current = prefs.getString("side_tab_size", "large")
            options.forEach { (key, tv) ->
                val selected = key == current
                tv.setTextColor(if (selected) 0xFF90CAF9.toInt() else 0x66FFFFFF)
                tv.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        options.forEach { (key, tv) ->
            tv.setOnClickListener {
                prefs.edit().putString("side_tab_size", key).apply()
                render()
                BlindAccessibilityService.instance?.onSideTabSizeChanged()
            }
        }
        render()
    }

    private fun onEntitlementChanged(entitled: Boolean) {
        if (entitled) {
            if (justPurchased) {
                justPurchased = false
                setMode("plus")
            }
        } else if (isAutoMode()) {
            // Plus not owned but mode was Plus (e.g. carried over) -> revert to Basic
            prefs.edit().putString("mode", "basic").apply()
            BlindAccessibilityService.instance?.onModeChanged()
        }
        setupModeSelection()
    }

    private fun selectPlusOrPurchase() {
        if (billing.isEntitled()) {
            setMode("plus")
        } else {
            justPurchased = true
            if (!billing.launchPurchase(this)) {
                justPurchased = false
                android.widget.Toast.makeText(
                    this, getString(R.string.billing_unavailable),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        val banner = findViewById<View>(R.id.updateBanner)
        banner.setOnClickListener { AppUpdate.openStore(this) }
        AppUpdate.check(this) { newVersion ->
            if (!isFinishing) banner.visibility = if (newVersion > 0) View.VISIBLE else View.GONE
        }
    }

    private fun isAutoMode(): Boolean {
        return prefs.getString("mode", "basic") == "plus"
    }

    private fun setMode(mode: String) {
        prefs.edit().putString("mode", mode).apply()
        setupModeSelection()
        BlindAccessibilityService.instance?.onModeChanged()
    }

    private fun setupModeSelection() {
        val isPlus = isAutoMode()

        val cardBasic = findViewById<LinearLayout>(R.id.cardBasic)
        val cardPlus = findViewById<LinearLayout>(R.id.cardPlus)
        val btnBasic = findViewById<TextView>(R.id.btnSelectBasic)
        val btnPlus = findViewById<TextView>(R.id.btnSelectPlus)

        if (isPlus) {
            cardBasic.setBackgroundResource(R.drawable.bg_card)
            cardPlus.setBackgroundResource(R.drawable.bg_card_selected)
            btnBasic.setBackgroundResource(R.drawable.bg_btn_select)
            btnBasic.text = getString(R.string.mode_select)
            btnBasic.setTextColor(0xB3FFFFFF.toInt())
            btnPlus.setBackgroundResource(R.drawable.bg_btn_selected)
            btnPlus.text = getString(R.string.mode_in_use)
            btnPlus.setTextColor(0xFFFFFFFF.toInt())
        } else {
            cardBasic.setBackgroundResource(R.drawable.bg_card_selected)
            cardPlus.setBackgroundResource(R.drawable.bg_card)
            btnBasic.setBackgroundResource(R.drawable.bg_btn_selected)
            btnBasic.text = getString(R.string.mode_in_use)
            btnBasic.setTextColor(0xFFFFFFFF.toInt())
            btnPlus.setBackgroundResource(R.drawable.bg_btn_select)
            btnPlus.text = if (::billing.isInitialized && billing.isEntitled()) {
                getString(R.string.mode_select)
            } else {
                getString(R.string.mode_unlock_price)
            }
            btnPlus.setTextColor(0xB3FFFFFF.toInt())
        }

        btnBasic.setOnClickListener {
            if (isAutoMode()) setMode("basic")
        }
        btnPlus.setOnClickListener {
            if (!isAutoMode()) selectPlusOrPurchase()
        }
        cardBasic.setOnClickListener {
            if (isAutoMode()) setMode("basic")
        }
        cardPlus.setOnClickListener {
            if (!isAutoMode()) selectPlusOrPurchase()
        }
    }

    private fun updateUI() {
        val isServiceActive = BlindAccessibilityService.instance != null
        val btnOn = findViewById<Button>(R.id.btnOn)
        val btnOff = findViewById<Button>(R.id.btnOff)

        btnOn.isEnabled = true
        btnOn.alpha = 1f
        btnOff.isEnabled = true
        btnOff.alpha = 1f

        btnOn.setOnClickListener {
            if (isServiceActive) {
                finish()
            } else {
                openAccessibilitySettings()
            }
        }
        btnOff.setOnClickListener {
            finish()
        }

        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val isServiceActive = BlindAccessibilityService.instance != null
        val hasPhonePermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val hasBatteryExemption = pm.isIgnoringBatteryOptimizations(packageName)

        val tvAccessibility = findViewById<TextView>(R.id.tvPermAccessibility)
        val tvPhoneState = findViewById<TextView>(R.id.tvPermPhoneState)
        val tvBattery = findViewById<TextView>(R.id.tvPermBattery)
        val rowAccessibility = findViewById<LinearLayout>(R.id.rowAccessibility)
        val rowPhoneState = findViewById<LinearLayout>(R.id.rowPhoneState)
        val rowBattery = findViewById<LinearLayout>(R.id.rowBattery)

        if (isServiceActive) {
            tvAccessibility.text = getString(R.string.perm_active)
            tvAccessibility.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvAccessibility.text = getString(R.string.perm_inactive)
            tvAccessibility.setTextColor(0xFFFF9800.toInt())
        }

        if (!isAutoMode()) {
            tvPhoneState.text = getString(R.string.perm_not_needed)
            tvPhoneState.setTextColor(0x80FFFFFF.toInt())
        } else if (hasPhonePermission) {
            tvPhoneState.text = getString(R.string.perm_granted)
            tvPhoneState.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvPhoneState.text = getString(R.string.perm_not_granted)
            tvPhoneState.setTextColor(0xFFFF9800.toInt())
        }

        if (hasBatteryExemption) {
            tvBattery.text = getString(R.string.perm_granted)
            tvBattery.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvBattery.text = getString(R.string.perm_not_granted)
            tvBattery.setTextColor(0xFFFF9800.toInt())
        }

        rowAccessibility.setOnClickListener {
            if (!isServiceActive) {
                openAccessibilitySettings()
            }
        }
        rowPhoneState.setOnClickListener {
            if (isAutoMode() && !hasPhonePermission) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_PHONE_STATE),
                    REQUEST_PHONE_STATE
                )
            }
        }
        rowBattery.setOnClickListener {
            if (!hasBatteryExemption) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(packageName, BlindAccessibilityService::class.java.name)
            val extraKey = ":settings:fragment_args_key"
            intent.putExtra(extraKey, componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(extraKey, componentName.flattenToString())
            })
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun showHowToDialog() {
        AlertDialog.Builder(this, R.style.Theme_CallBlind_Dialog)
            .setTitle(getString(R.string.howto_title))
            .setMessage(getString(R.string.howto_content))
            .setPositiveButton(getString(R.string.usage_guide_close), null)
            .show()
    }

    private fun showUsageGuideDialog() {
        AlertDialog.Builder(this, R.style.Theme_CallBlind_Dialog)
            .setTitle(getString(R.string.usage_guide_title))
            .setMessage(getString(R.string.usage_guide_content))
            .setPositiveButton(getString(R.string.usage_guide_close), null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PHONE_STATE && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            BlindAccessibilityService.instance?.registerCallStateFromActivity()
            updateUI()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    companion object {
        private const val REQUEST_PHONE_STATE = 100
    }
}

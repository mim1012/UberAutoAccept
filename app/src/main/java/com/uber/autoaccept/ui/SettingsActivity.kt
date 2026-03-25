package com.uber.autoaccept.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.uber.autoaccept.BuildConfig
import com.uber.autoaccept.R
import com.uber.autoaccept.update.UpdateChecker
import com.uber.autoaccept.update.UpdateDialog
import com.uber.autoaccept.utils.ShizukuHelper
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var airportDistanceSeekBar: SeekBar
    private lateinit var airportDistanceText: TextView
    private lateinit var saveButton: Button
    private lateinit var remoteLoggingSwitch: Switch
    private lateinit var pickupKeywordsContainer: LinearLayout
    private lateinit var pickupKeywordInput: EditText
    private lateinit var addPickupKeywordButton: Button
    private lateinit var condition1Checkbox: android.widget.CheckBox
    private lateinit var condition2Checkbox: android.widget.CheckBox
    private lateinit var condition3Checkbox: android.widget.CheckBox
    private lateinit var condition4Checkbox: android.widget.CheckBox
    private lateinit var appVersionText: TextView
    private lateinit var shizukuStatusText: TextView
    private lateinit var checkUpdateButton: Button

    private val pickupKeywords = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "설정"

        airportDistanceSeekBar = findViewById(R.id.airport_distance_seekbar)
        airportDistanceText = findViewById(R.id.airport_distance_text)
        saveButton = findViewById(R.id.save_button)
        remoteLoggingSwitch = findViewById(R.id.remote_logging_switch)
        pickupKeywordsContainer = findViewById(R.id.pickup_keywords_container)
        pickupKeywordInput = findViewById(R.id.pickup_keyword_input)
        addPickupKeywordButton = findViewById(R.id.add_pickup_keyword_button)
        condition1Checkbox = findViewById(R.id.condition1_checkbox)
        condition2Checkbox = findViewById(R.id.condition2_checkbox)
        condition3Checkbox = findViewById(R.id.condition3_checkbox)
        condition4Checkbox = findViewById(R.id.condition4_checkbox)

        appVersionText = findViewById(R.id.app_version_text)
        shizukuStatusText = findViewById(R.id.shizuku_status_text)
        checkUpdateButton = findViewById(R.id.check_update_button)

        setupDistanceSeekBar()
        setupPickupKeywords()
        loadSettings()
        setupAppInfo()

        saveButton.setOnClickListener {
            saveSettings()
            sendBroadcast(Intent("com.uber.autoaccept.RELOAD_CONFIG").setPackage(packageName))
            Log.i("SettingsActivity", "설정 저장 완료 → RELOAD_CONFIG 브로드캐스트 전송")
            Toast.makeText(this, "설정이 저장되었습니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupAppInfo() {
        appVersionText.text = "앱 버전: ${BuildConfig.VERSION_NAME}"

        val shizukuAvailable = ShizukuHelper.isAvailable()
        val shizukuPermission = if (shizukuAvailable) ShizukuHelper.hasPermission() else false
        shizukuStatusText.text = when {
            !shizukuAvailable -> "Shizuku: 미설치 또는 미실행 (호환: ${BuildConfig.SHIZUKU_COMPAT_VERSION})"
            !shizukuPermission -> "Shizuku: 권한 없음"
            else -> "Shizuku: 활성화됨"
        }
        shizukuStatusText.setTextColor(
            when {
                shizukuAvailable && shizukuPermission -> Color.parseColor("#4CAF50")
                shizukuAvailable -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            }
        )

        checkUpdateButton.setOnClickListener {
            lifecycleScope.launch {
                checkUpdateButton.isEnabled = false
                checkUpdateButton.text = "확인 중..."
                try {
                    val result = UpdateChecker.check(this@SettingsActivity, forceCheck = true)
                    if (result.hasUpdate && result.latestVersion != null) {
                        UpdateDialog.show(this@SettingsActivity, result)
                    } else {
                        Toast.makeText(this@SettingsActivity, "최신 버전입니다", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "확인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    checkUpdateButton.isEnabled = true
                    checkUpdateButton.text = "업데이트 확인"
                }
            }
        }
    }

    private fun setupPickupKeywords() {
        addPickupKeywordButton.setOnClickListener {
            val kw = pickupKeywordInput.text.toString().trim()
            if (kw.isEmpty()) {
                Toast.makeText(this, "키워드를 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pickupKeywords.contains(kw)) {
                Toast.makeText(this, "이미 존재하는 키워드입니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickupKeywords.add(kw)
            pickupKeywordInput.text.clear()
            renderPickupKeywords()
        }
    }

    private fun renderPickupKeywords() {
        pickupKeywordsContainer.removeAllViews()
        pickupKeywords.forEach { kw ->
            val tag = TextView(this).apply {
                text = "✕  $kw"
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1976D2"))
                setPadding(24, 12, 24, 12)
                gravity = Gravity.CENTER_VERTICAL
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
                layoutParams = params
                setOnClickListener {
                    pickupKeywords.remove(kw)
                    renderPickupKeywords()
                }
            }
            pickupKeywordsContainer.addView(tag)
        }
    }

    private fun setupDistanceSeekBar() {
        airportDistanceSeekBar.max = 150 // 0.0 ~ 15.0 km (0.1 단위)

        airportDistanceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val distance = progress / 10.0
                airportDistanceText.text = String.format("최대 고객 거리: %.1f km", distance)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)

        // 기존 airport_pickup_max_distance 값으로 마이그레이션
        val maxDist = prefs.getFloat("max_customer_distance",
            prefs.getFloat("airport_pickup_max_distance", 5.0f))
        airportDistanceSeekBar.progress = (maxDist * 10).toInt()

        remoteLoggingSwitch.isChecked = prefs.getBoolean("remote_logging_enabled", true)

        condition1Checkbox.isChecked = prefs.getBoolean("condition1_enabled", true)
        condition2Checkbox.isChecked = prefs.getBoolean("condition2_enabled", true)
        condition3Checkbox.isChecked = prefs.getBoolean("condition3_enabled", true)
        condition4Checkbox.isChecked = prefs.getBoolean("condition4_enabled", true)

        val savedKeywords = prefs.getStringSet("pickup_keywords", null)
        pickupKeywords.clear()
        pickupKeywords.addAll(savedKeywords?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: listOf("특별시"))
        renderPickupKeywords()
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)

        val maxDist = airportDistanceSeekBar.progress / 10.0f

        prefs.edit().apply {
            putFloat("max_customer_distance", maxDist)
            putBoolean("remote_logging_enabled", remoteLoggingSwitch.isChecked)
            putStringSet("pickup_keywords", pickupKeywords.toSet())
            putBoolean("condition1_enabled", condition1Checkbox.isChecked)
            putBoolean("condition2_enabled", condition2Checkbox.isChecked)
            putBoolean("condition3_enabled", condition3Checkbox.isChecked)
            putBoolean("condition4_enabled", condition4Checkbox.isChecked)
            apply()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

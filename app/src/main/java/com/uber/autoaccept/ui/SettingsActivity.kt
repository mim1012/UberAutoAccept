package com.uber.autoaccept.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
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
    private lateinit var conditionRadioGroup: RadioGroup
    private lateinit var appVersionText: TextView
    private lateinit var shizukuStatusText: TextView
    private lateinit var checkUpdateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "설정"

        airportDistanceSeekBar = findViewById(R.id.airport_distance_seekbar)
        airportDistanceText = findViewById(R.id.airport_distance_text)
        saveButton = findViewById(R.id.save_button)
        remoteLoggingSwitch = findViewById(R.id.remote_logging_switch)
        conditionRadioGroup = findViewById(R.id.condition_radio_group)

        appVersionText = findViewById(R.id.app_version_text)
        shizukuStatusText = findViewById(R.id.shizuku_status_text)
        checkUpdateButton = findViewById(R.id.check_update_button)

        setupDistanceSeekBar()
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

        val maxDist = prefs.getFloat("max_customer_distance",
            prefs.getFloat("airport_pickup_max_distance", 5.0f))
        airportDistanceSeekBar.progress = (maxDist * 10).toInt()

        remoteLoggingSwitch.isChecked = prefs.getBoolean("remote_logging_enabled", true)

        val selectedCondition = prefs.getInt("selected_condition", 4)
        val radioId = when (selectedCondition) {
            1 -> R.id.condition1_radio
            2 -> R.id.condition2_radio
            3 -> R.id.condition3_radio
            else -> R.id.condition4_radio
        }
        conditionRadioGroup.check(radioId)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)

        val maxDist = airportDistanceSeekBar.progress / 10.0f

        val selectedCondition = when (conditionRadioGroup.checkedRadioButtonId) {
            R.id.condition1_radio -> 1
            R.id.condition2_radio -> 2
            R.id.condition3_radio -> 3
            R.id.condition4_radio -> 4
            else -> 4
        }

        prefs.edit().apply {
            putFloat("max_customer_distance", maxDist)
            putBoolean("remote_logging_enabled", remoteLoggingSwitch.isChecked)
            putInt("selected_condition", selectedCondition)
            apply()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

package com.uber.autoaccept.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.uber.autoaccept.R
import com.uber.autoaccept.model.FilterMode

class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var settingsButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.status_text)
        enableButton = findViewById(R.id.enable_button)
        settingsButton = findViewById(R.id.settings_button)
        
        enableButton.setOnClickListener {
            openAccessibilitySettings()
        }
        
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        updateStatus()
    }
    
    override fun onResume() {
        super.onResume()
        updateStatus()
    }
    
    private fun updateStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        
        if (isEnabled) {
            statusText.text = "✅ 서비스 활성화됨\n\n현재 설정:\n${getConfigSummary()}"
            enableButton.text = "접근성 설정 열기"
        } else {
            statusText.text = "❌ 서비스 비활성화됨\n\n아래 버튼을 눌러 접근성 서비스를 활성화하세요."
            enableButton.text = "서비스 활성화"
        }
    }
    
    private fun getConfigSummary(): String {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)
        val modeString = prefs.getString("filter_mode", FilterMode.BOTH.name) ?: FilterMode.BOTH.name
        val minDistance = prefs.getFloat("min_customer_distance", 1.0f)
        val maxDistance = prefs.getFloat("max_customer_distance", 3.0f)
        
        val modeText = when (modeString) {
            FilterMode.AIRPORT.name -> "인천공항 모드"
            FilterMode.SEOUL_ENTRY.name -> "서울 진입 모드"
            FilterMode.BOTH.name -> "두 모드 모두"
            else -> "비활성화"
        }
        
        return "모드: $modeText\n고객 거리: ${minDistance}km ~ ${maxDistance}km"
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/.service.UberAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }
    
    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

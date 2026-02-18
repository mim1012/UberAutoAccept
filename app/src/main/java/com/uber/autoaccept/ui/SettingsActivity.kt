package com.uber.autoaccept.ui

import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.uber.autoaccept.R
import com.uber.autoaccept.model.FilterMode

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var modeSpinner: Spinner
    private lateinit var minDistanceSeekBar: SeekBar
    private lateinit var maxDistanceSeekBar: SeekBar
    private lateinit var minDistanceText: TextView
    private lateinit var maxDistanceText: TextView
    private lateinit var saveButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "설정"
        
        modeSpinner = findViewById(R.id.mode_spinner)
        minDistanceSeekBar = findViewById(R.id.min_distance_seekbar)
        maxDistanceSeekBar = findViewById(R.id.max_distance_seekbar)
        minDistanceText = findViewById(R.id.min_distance_text)
        maxDistanceText = findViewById(R.id.max_distance_text)
        saveButton = findViewById(R.id.save_button)
        
        setupModeSpinner()
        setupDistanceSeekBars()
        loadSettings()
        
        saveButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "설정이 저장되었습니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun setupModeSpinner() {
        val modes = arrayOf("인천공항 모드", "서울 진입 모드", "두 모드 모두", "비활성화")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = adapter
    }
    
    private fun setupDistanceSeekBars() {
        minDistanceSeekBar.max = 50 // 0.0 ~ 5.0 km (0.1 단위)
        maxDistanceSeekBar.max = 50
        
        minDistanceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val distance = progress / 10.0
                minDistanceText.text = String.format("최소 거리: %.1f km", distance)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        maxDistanceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val distance = progress / 10.0
                maxDistanceText.text = String.format("최대 거리: %.1f km", distance)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)
        
        val modeString = prefs.getString("filter_mode", FilterMode.BOTH.name) ?: FilterMode.BOTH.name
        val modeIndex = when (modeString) {
            FilterMode.AIRPORT.name -> 0
            FilterMode.SEOUL_ENTRY.name -> 1
            FilterMode.BOTH.name -> 2
            else -> 3
        }
        modeSpinner.setSelection(modeIndex)
        
        val minDistance = prefs.getFloat("min_customer_distance", 1.0f)
        val maxDistance = prefs.getFloat("max_customer_distance", 3.0f)
        
        minDistanceSeekBar.progress = (minDistance * 10).toInt()
        maxDistanceSeekBar.progress = (maxDistance * 10).toInt()
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)
        
        val mode = when (modeSpinner.selectedItemPosition) {
            0 -> FilterMode.AIRPORT
            1 -> FilterMode.SEOUL_ENTRY
            2 -> FilterMode.BOTH
            else -> FilterMode.DISABLED
        }
        
        val minDistance = minDistanceSeekBar.progress / 10.0f
        val maxDistance = maxDistanceSeekBar.progress / 10.0f
        
        prefs.edit().apply {
            putString("filter_mode", mode.name)
            putFloat("min_customer_distance", minDistance)
            putFloat("max_customer_distance", maxDistance)
            apply()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

package com.uber.autoaccept.ui

import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.uber.autoaccept.R
import com.uber.autoaccept.model.FilterMode

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var modeSpinner: Spinner
    private lateinit var seoulDistanceSeekBar: SeekBar
    private lateinit var airportDistanceSeekBar: SeekBar
    private lateinit var seoulDistanceText: TextView
    private lateinit var airportDistanceText: TextView
    private lateinit var saveButton: Button
    private lateinit var remoteLoggingSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "설정"

        modeSpinner = findViewById(R.id.mode_spinner)
        seoulDistanceSeekBar = findViewById(R.id.seoul_distance_seekbar)
        airportDistanceSeekBar = findViewById(R.id.airport_distance_seekbar)
        seoulDistanceText = findViewById(R.id.seoul_distance_text)
        airportDistanceText = findViewById(R.id.airport_distance_text)
        saveButton = findViewById(R.id.save_button)
        remoteLoggingSwitch = findViewById(R.id.remote_logging_switch)

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
        seoulDistanceSeekBar.max = 100   // 0.0 ~ 10.0 km (0.1 단위)
        airportDistanceSeekBar.max = 150 // 0.0 ~ 15.0 km (0.1 단위)

        seoulDistanceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val distance = progress / 10.0
                seoulDistanceText.text = String.format("최대 고객 거리: %.1f km", distance)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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

        val modeString = prefs.getString("filter_mode", FilterMode.BOTH.name) ?: FilterMode.BOTH.name
        val modeIndex = when (modeString) {
            FilterMode.AIRPORT.name -> 0
            FilterMode.SEOUL_ENTRY.name -> 1
            FilterMode.BOTH.name -> 2
            else -> 3
        }
        modeSpinner.setSelection(modeIndex)

        val seoulDist = prefs.getFloat("seoul_pickup_max_distance", 3.0f)
        val airportDist = prefs.getFloat("airport_pickup_max_distance", 7.0f)

        seoulDistanceSeekBar.progress = (seoulDist * 10).toInt()
        airportDistanceSeekBar.progress = (airportDist * 10).toInt()

        remoteLoggingSwitch.isChecked = prefs.getBoolean("remote_logging_enabled", true)
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)

        val mode = when (modeSpinner.selectedItemPosition) {
            0 -> FilterMode.AIRPORT
            1 -> FilterMode.SEOUL_ENTRY
            2 -> FilterMode.BOTH
            else -> FilterMode.DISABLED
        }

        val seoulDist = seoulDistanceSeekBar.progress / 10.0f
        val airportDist = airportDistanceSeekBar.progress / 10.0f

        prefs.edit().apply {
            putString("filter_mode", mode.name)
            putFloat("seoul_pickup_max_distance", seoulDist)
            putFloat("airport_pickup_max_distance", airportDist)
            putBoolean("remote_logging_enabled", remoteLoggingSwitch.isChecked)
            apply()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

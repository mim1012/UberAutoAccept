package com.uber.autoaccept.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.uber.autoaccept.R
import com.uber.autoaccept.auth.AuthManager
import com.uber.autoaccept.service.FloatingWidgetService
import com.uber.autoaccept.service.ServiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val PHONE_PERMISSION_REQUEST = 1002
        private const val MAX_AUTH_RETRIES = 3
    }

    private lateinit var licenseText: TextView
    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var startStopButton: Button
    private lateinit var settingsButton: Button

    private lateinit var authManager: AuthManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var floatingServiceRunning = false
    private var isAuthenticated = false
    private var authRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        licenseText = findViewById(R.id.license_text)
        statusText = findViewById(R.id.status_text)
        enableButton = findViewById(R.id.enable_button)
        startStopButton = findViewById(R.id.start_stop_button)
        settingsButton = findViewById(R.id.settings_button)

        authManager = AuthManager.getInstance(this)

        enableButton.setOnClickListener { openAccessibilitySettings() }
        startStopButton.setOnClickListener {
            if (floatingServiceRunning) stopFloatingWidget() else startFloatingWidget()
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 인증 전까지 UI 비활성화
        disableAllInteractions()

        // 전화번호 권한 확인 후 인증
        checkPhonePermissionAndAuthenticate()
    }

    override fun onResume() {
        super.onResume()

        // 인증 만료 체크 (Vortex 패턴)
        if (isAuthenticated && (!authManager.isAuthorized || !authManager.isCacheValid())) {
            isAuthenticated = false
            disableAllInteractions()
            if (floatingServiceRunning) {
                stopFloatingWidget()
            }
            AlertDialog.Builder(this)
                .setTitle("인증 만료")
                .setMessage("인증이 만료되었습니다.\n앱을 재시작하여 재인증해주세요.")
                .setCancelable(false)
                .setPositiveButton("앱 종료") { _, _ -> finish() }
                .show()
            return
        }

        if (isAuthenticated) {
            updateStatus()
        }
    }

    private fun checkPhonePermissionAndAuthenticate() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS
                ),
                PHONE_PERMISSION_REQUEST
            )
        } else {
            performAuthentication()
        }
    }

    private fun performAuthentication() {
        licenseText.text = "인증 확인 중..."
        window.decorView.alpha = 0.5f

        authManager.authenticate(object : AuthManager.AuthCallback {
            override fun onSuccess(result: AuthManager.AuthResult) {
                mainHandler.post {
                    isAuthenticated = true
                    authRetryCount = 0
                    window.decorView.alpha = 1.0f
                    enableAllInteractions()
                    updateStatus()
                    updateLicenseInfo()
                }
            }

            override fun onFailure(error: String) {
                mainHandler.post {
                    isAuthenticated = false
                    window.decorView.alpha = 0.3f
                    disableAllInteractions()
                    showAuthFailedDialog(error)
                }
            }
        })
    }

    private fun showAuthFailedDialog(error: String) {
        authManager.clearCache()
        authRetryCount++

        if (authRetryCount >= MAX_AUTH_RETRIES) {
            AlertDialog.Builder(this)
                .setTitle("인증 실패")
                .setMessage("$error\n\n최대 재시도 횟수를 초과했습니다.\n관리자에게 문의하세요.")
                .setCancelable(false)
                .setPositiveButton("종료") { _, _ -> finish() }
                .show()
        } else {
            val remaining = MAX_AUTH_RETRIES - authRetryCount
            AlertDialog.Builder(this)
                .setTitle("인증 실패")
                .setMessage("$error\n\n등록된 기기만 사용 가능합니다.\n관리자에게 기기 등록을 요청하세요.\n(남은 재시도: ${remaining}회)")
                .setCancelable(false)
                .setPositiveButton("재시도") { _, _ -> performAuthentication() }
                .setNeutralButton("번호 직접 입력") { _, _ -> showPhoneInputDialog() }
                .setNegativeButton("종료") { _, _ -> finish() }
                .show()
        }
    }

    private fun showPhoneInputDialog() {
        val editText = EditText(this).apply {
            hint = "전화번호 입력 (01000000000)"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("전화번호 직접 입력")
            .setMessage("관리자에게 등록된 전화번호를 입력하세요")
            .setView(editText)
            .setPositiveButton("확인") { _, _ ->
                val phone = editText.text.toString().replace(Regex("[^0-9]"), "")
                if (phone.length >= 10) {
                    authManager.savedPhoneNumber = phone
                    performAuthentication()
                } else {
                    Toast.makeText(this, "올바른 전화번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun updateLicenseInfo() {
        val expires = authManager.expiresAt
        if (expires.isNotEmpty()) {
            val days = calculateRemainingDays(expires)
            licenseText.text = "✓ 라이센스 유효 (${days}일 남음)"
            licenseText.setTextColor(if (days > 7) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
        } else {
            licenseText.text = "✓ 라이센스 유효 (무기한)"
            licenseText.setTextColor(0xFF4CAF50.toInt())
        }
    }

    private fun calculateRemainingDays(expiresAt: String): Long {
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            )
            var date: Date? = null
            for (fmt in formats) {
                try { date = fmt.parse(expiresAt); if (date != null) break } catch (_: Exception) {}
            }
            if (date != null) {
                val diff = date.time - System.currentTimeMillis()
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                if (days < 0) 0 else days
            } else 0
        } catch (_: Exception) { 0 }
    }

    private fun disableAllInteractions() {
        startStopButton.isEnabled = false
        enableButton.isEnabled = false
        settingsButton.isEnabled = false
    }

    private fun enableAllInteractions() {
        enableButton.isEnabled = true
        settingsButton.isEnabled = true
        startStopButton.isEnabled = isAccessibilityServiceEnabled()
    }

    private fun startFloatingWidget() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "먼저 접근성 서비스를 활성화하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "다른 앱 위에 표시 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            return
        }
        startForegroundService(Intent(this, FloatingWidgetService::class.java))
        floatingServiceRunning = true
        updateStartStopButton()
    }

    private fun stopFloatingWidget() {
        stopService(Intent(this, FloatingWidgetService::class.java))
        ServiceState.stop()
        floatingServiceRunning = false
        updateStartStopButton()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST && Settings.canDrawOverlays(this)) {
            startFloatingWidget()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PHONE_PERMISSION_REQUEST) {
            // 허용 여부와 상관없이 인증 진행 (거부 시 기기ID 사용)
            performAuthentication()
        }
    }

    private fun updateStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
            statusText.text = "서비스 활성화됨\n\n현재 설정:\n${getConfigSummary()}"
            enableButton.text = "접근성 설정 열기"
        } else {
            statusText.text = "서비스 비활성화됨\n\n아래 버튼을 눌러 접근성 서비스를 활성화하세요."
            enableButton.text = "서비스 활성화"
        }
        startStopButton.isEnabled = isEnabled
        updateStartStopButton()
    }

    private fun updateStartStopButton() {
        if (floatingServiceRunning) {
            startStopButton.text = "중지"
            startStopButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
        } else {
            startStopButton.text = "시작"
            startStopButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
        }
    }

    private fun getConfigSummary(): String {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)
        val enabled = prefs.getString("filter_mode", "ENABLED") != "DISABLED"
        val maxDist = prefs.getFloat("max_customer_distance",
            prefs.getFloat("airport_pickup_max_distance", 5.0f))
        val modeText = if (enabled) "활성화" else "비활성화"
        return "모드: $modeText\n최대 고객 거리: ${maxDist}km 이내"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val shortForm = "${packageName}/.service.UberAccessibilityService"
        val fullForm = "${packageName}/${packageName}.service.UberAccessibilityService"
        return enabledServices.contains(shortForm) || enabledServices.contains(fullForm)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

package com.uber.autoaccept.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.view.accessibility.AccessibilityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.uber.autoaccept.R
import com.uber.autoaccept.auth.AuthManager
import com.uber.autoaccept.service.FloatingWidgetService
import com.uber.autoaccept.service.ServiceState
import com.uber.autoaccept.update.UpdateChecker
import com.uber.autoaccept.update.UpdateDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val PHONE_PERMISSION_REQUEST = 1002
        private const val MAX_AUTH_RETRIES = 3
        private const val ACTION_ENGINE_START = "com.uber.autoaccept.ACTION_ENGINE_START"
        private const val ACTION_ENGINE_STOP = "com.uber.autoaccept.ACTION_ENGINE_STOP"
    }

    private lateinit var licenseText: TextView
    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var startStopButton: Button
    private lateinit var settingsButton: Button
    private lateinit var statusAccessibility: TextView
    private lateinit var statusShizuku: TextView
    private lateinit var statusRemoteLogging: TextView
    private val statusRefreshHandler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            updateStatusCard()
            statusRefreshHandler.postDelayed(this, 2000)
        }
    }

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
        statusAccessibility = findViewById(R.id.status_accessibility)
        statusShizuku = findViewById(R.id.status_shizuku)
        statusRemoteLogging = findViewById(R.id.status_remote_logging)

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
        updateStatusCard()
        statusRefreshHandler.post(statusRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        statusRefreshHandler.removeCallbacks(statusRefreshRunnable)
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
        // savedPhoneNumber가 없으면 바로 전화번호 입력 요청

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
                    checkBatteryOptimization()
                    checkForUpdates()
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
                .setMessage("$error\n\n등록된 전화번호로 인증하려면 '번호 직접 입력'을 눌러주세요.\n(남은 재시도: ${remaining}회)")
                .setCancelable(false)
                .setPositiveButton("번호 직접 입력") { _, _ -> showPhoneInputDialog() }
                .setNeutralButton("재시도") { _, _ -> performAuthentication() }
                .setNegativeButton("종료") { _, _ -> finish() }
                .show()
        }
    }

    /** 최초 실행 시 전화번호 입력 다이얼로그 (취소 불가) */
    private fun showPhoneInputDialogFirst() {
        val editText = EditText(this).apply {
            hint = "전화번호 입력 (01000000000)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("전화번호 인증")
            .setMessage("등록된 전화번호를 입력하세요")
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton("확인") { _, _ ->
                val phone = editText.text.toString().replace(Regex("[^0-9]"), "")
                if (phone.length >= 10) {
                    authManager.savedPhoneNumber = phone
                    performAuthentication()
                } else {
                    Toast.makeText(this, "올바른 전화번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                    showPhoneInputDialogFirst()
                }
            }
            .setNegativeButton("종료") { _, _ -> finish() }
            .show()
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
        startStopButton.isEnabled = true
    }

    private fun startFloatingWidget() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "??? ??????????? ????????", Toast.LENGTH_SHORT).show()
            return
        }
        val controllerPackage = getControllingPackageName()
        if (controllerPackage != packageName) {
            val launchIntent = packageManager.getLaunchIntentForPackage(controllerPackage)
            if (launchIntent != null) {
                startActivity(launchIntent)
                Toast.makeText(this, "Switching to debug app", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Controller app not found", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            return
        }
        startForegroundService(Intent(this, FloatingWidgetService::class.java))
        sendEngineCommand(ACTION_ENGINE_START)
        floatingServiceRunning = true
        updateStartStopButton()
    }

    private fun stopFloatingWidget() {
        sendEngineCommand(ACTION_ENGINE_STOP)
        stopService(Intent(this, FloatingWidgetService::class.java))
        floatingServiceRunning = false
        updateStartStopButton()
    }

    private fun sendEngineCommand(action: String) {
        val intent = Intent(action)
        intent.setPackage(packageName)
        sendBroadcast(intent)
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
        val isEnabled = true
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
        if (packageName.endsWith(".debug")) return true
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val settingsMatch = knownControllerPackages().any { controllerPackage ->
            val shortForm = "${controllerPackage}/.service.UberAccessibilityService"
            val fullForm = "${controllerPackage}/${controllerPackage}.service.UberAccessibilityService"
            enabledServices.contains(shortForm) || enabledServices.contains(fullForm)
        }
        if (settingsMatch) return true

        val accessibilityManager =
            getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServiceIds = accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo }
            .map { "${it.packageName}/${it.name}" }
        return knownControllerPackages().any { controllerPackage ->
            enabledServiceIds.contains("${controllerPackage}/${controllerPackage}.service.UberAccessibilityService") ||
                enabledServiceIds.contains("${controllerPackage}/com.uber.autoaccept.service.UberAccessibilityService")
        }
    }

    private fun getControllingPackageName(): String {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return packageName
        return knownControllerPackages().firstOrNull { controllerPackage ->
            val shortForm = "${controllerPackage}/.service.UberAccessibilityService"
            val fullForm = "${controllerPackage}/${controllerPackage}.service.UberAccessibilityService"
            enabledServices.contains(shortForm) || enabledServices.contains(fullForm)
        } ?: packageName
    }

    private fun knownControllerPackages(): List<String> {
        val counterpart = if (packageName.endsWith(".debug")) {
            packageName.removeSuffix(".debug")
        } else {
            "$packageName.debug"
        }
        return listOf(packageName, counterpart).distinct()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("배터리 최적화 제외 필요")
                .setMessage("배터리 최적화가 켜져 있으면 서비스가 강제 종료될 수 있습니다.\n\n'허용'을 눌러 이 앱을 배터리 최적화에서 제외하세요.")
                .setCancelable(true)
                .setPositiveButton("설정으로 이동") { _, _ ->
                    startActivity(Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val result = UpdateChecker.check(this@MainActivity)
                if (result.hasUpdate && result.latestVersion != null) {
                    UpdateDialog.show(this@MainActivity, result)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Update check failed: ${e.message}")
            }
        }
    }

    private fun updateStatusCard() {
        // 접근성 서비스 (시스템 등록 + 실제 동작 여부 둘 다 체크)
        val accessibilityOn = true
        val serviceActive = ServiceState.isActive()
        val accessibilityStatus = when {
            accessibilityOn && serviceActive -> "● 접근성 서비스: 동작 중"
            accessibilityOn && !serviceActive -> "● 접근성 서비스: 등록됨 (시작 필요)"
            else -> "● 접근성 서비스: 비활성화"
        }
        val accessibilityColor = when {
            accessibilityOn && serviceActive -> 0xFF4CAF50.toInt()  // 초록
            accessibilityOn && !serviceActive -> 0xFFFF9800.toInt() // 주황
            else -> 0xFFF44336.toInt()  // 빨강
        }
        statusAccessibility.text = accessibilityStatus
        statusAccessibility.setTextColor(accessibilityColor)
        startStopButton.isEnabled = accessibilityOn

        // Shizuku
        val shizukuOn = com.uber.autoaccept.utils.ShizukuHelper.hasPermission()
        statusShizuku.text = if (shizukuOn) "● Shizuku: 활성화" else "● Shizuku: 비활성화"
        statusShizuku.setTextColor(if (shizukuOn) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())

        // 원격 로깅
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)
        val remoteLoggingOn = prefs.getBoolean("remote_logging_enabled", true)
        statusRemoteLogging.text = if (remoteLoggingOn) "● 원격 로깅: 활성화" else "● 원격 로깅: 비활성화"
        statusRemoteLogging.setTextColor(if (remoteLoggingOn) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
    }
}

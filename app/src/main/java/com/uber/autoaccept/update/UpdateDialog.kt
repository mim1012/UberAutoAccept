package com.uber.autoaccept.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UpdateDialog {

    fun show(activity: Activity, result: UpdateCheckResult) {
        if (!result.hasUpdate || result.latestVersion == null) return

        val message = buildString {
            append("현재 버전: ${com.uber.autoaccept.BuildConfig.VERSION_NAME}\n")
            append("최신 버전: ${result.latestVersion}\n")
            if (result.message != null && result.message != "Update available") {
                append("\n${result.message}")
            }
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle("새 버전 사용 가능")
            .setMessage(message)
            .setCancelable(true)
            .setNegativeButton("나중에", null)

        if (result.appUrl != null) {
            builder.setPositiveButton("다운로드") { _, _ ->
                showPasswordDialog(activity, "앱 다운로드") { password ->
                    downloadWithPassword(activity, password, "app")
                }
            }
        }

        if (result.shizukuUrl != null) {
            builder.setNeutralButton("Shizuku 다운로드") { _, _ ->
                showPasswordDialog(activity, "Shizuku 다운로드") { password ->
                    downloadWithPassword(activity, password, "shizuku")
                }
            }
        }

        builder.show()
    }

    private fun showPasswordDialog(
        activity: Activity,
        title: String,
        onPasswordConfirmed: (String?) -> Unit
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "비밀번호 입력 (없으면 비워두세요)"
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("다운로드 비밀번호를 입력하세요.")
            .setView(container)
            .setPositiveButton("확인") { _, _ ->
                val password = input.text.toString().trim().ifEmpty { null }
                onPasswordConfirmed(password)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun downloadWithPassword(activity: Activity, password: String?, urlType: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.check(activity, password = password, forceCheck = true)
            }

            if (!result.allowed) {
                Toast.makeText(
                    activity,
                    result.message ?: "접근이 거부되었습니다",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val url = when (urlType) {
                "shizuku" -> result.shizukuUrl
                else -> result.appUrl
            }

            if (url != null) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                activity.startActivity(intent)
            } else {
                Toast.makeText(activity, "다운로드 URL을 가져올 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

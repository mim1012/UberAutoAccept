package com.uber.autoaccept.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri

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
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.appUrl))
                activity.startActivity(intent)
            }
        }

        if (result.shizukuUrl != null) {
            builder.setNeutralButton("Shizuku 다운로드") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.shizukuUrl))
                activity.startActivity(intent)
            }
        }

        builder.show()
    }
}

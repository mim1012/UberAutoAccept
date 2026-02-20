package com.uber.autoaccept.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import com.uber.autoaccept.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class FloatingWidgetService : Service() {

    companion object {
        private const val TAG = "FloatingWidget"
        private const val CHANNEL_ID = "floating_widget_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        showFloatingWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reload config when widget is started
        reloadAccessibilityConfig()
        return START_STICKY
    }

    private fun showFloatingWidget() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_widget, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        val statusText = floatingView!!.findViewById<TextView>(R.id.floating_status)
        val startBtn = floatingView!!.findViewById<Button>(R.id.floating_start_btn)
        val stopBtn = floatingView!!.findViewById<Button>(R.id.floating_stop_btn)
        val closeBtn = floatingView!!.findViewById<Button>(R.id.floating_close_btn)

        startBtn.setOnClickListener {
            ServiceState.start()
            reloadAccessibilityConfig()
            Log.i(TAG, "Started")
        }

        stopBtn.setOnClickListener {
            ServiceState.stop()
            Log.i(TAG, "Stopped")
        }

        closeBtn.setOnClickListener {
            Log.i(TAG, "Closed by user")
            stopSelf()
        }

        // Observe state changes to update UI
        scope.launch {
            ServiceState.active.collect { active ->
                if (active) {
                    statusText.text = "실행 중"
                    statusText.setTextColor(0xFF4CAF50.toInt())
                    startBtn.alpha = 0.5f
                    stopBtn.alpha = 1.0f
                } else {
                    statusText.text = "정지"
                    statusText.setTextColor(0xFFF44336.toInt())
                    startBtn.alpha = 1.0f
                    stopBtn.alpha = 0.5f
                }
            }
        }

        // Drag support
        setupDrag(floatingView!!, params)

        windowManager?.addView(floatingView, params)
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    !moved // if not moved, pass click through
                }
                else -> false
            }
        }
    }

    /**
     * Send broadcast to accessibility service to reload config
     */
    private fun reloadAccessibilityConfig() {
        val intent = Intent("com.uber.autoaccept.RELOAD_CONFIG")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Accept Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating widget control"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Uber Auto Accept")
                .setContentText("플로팅 컨트롤 실행 중")
                .setSmallIcon(R.drawable.ic_launcher)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Uber Auto Accept")
                .setContentText("플로팅 컨트롤 실행 중")
                .setSmallIcon(R.drawable.ic_launcher)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceState.stop()
        scope.cancel()
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
        Log.i(TAG, "Widget destroyed")
    }
}

package com.mykara.project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Dịch vụ chạy nền chỉ để giữ tiến trình app sống (kèm thông báo tiến độ) khi người dùng rời app lúc đang xuất
 * video. Việc vẽ khung vẫn do ExportActivity làm (WebView cần hiển thị), nên khi app ở nền việc xuất tạm dừng
 * và chạy tiếp khi quay lại; dịch vụ này giúp hệ điều hành không giết app trong lúc tạm dừng.
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projectId = intent?.getStringExtra("id") ?: ""
        val n = build(this, projectId, 0, "Đang chuẩn bị…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "export"
        private const val NOTIF_ID = 4201

        private fun ensureChannel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Xuất video", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun build(c: Context, projectId: String, permille: Int, text: String): Notification {
            ensureChannel(c)
            val open = PendingIntent.getActivity(
                c, 0,
                Intent(c, ExportActivity::class.java).putExtra("id", projectId)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Mykara – đang xuất video")
                .setContentText(text)
                .setProgress(1000, permille, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }

        fun start(c: Context, projectId: String) {
            try {
                c.startForegroundService(Intent(c, ExportService::class.java).putExtra("id", projectId))
            } catch (_: Exception) {
                // không bắt buộc: không chạy được dịch vụ thì vẫn xuất bình thường khi app ở phía trước
            }
        }

        fun update(c: Context, projectId: String, permille: Int, text: String) {
            try {
                c.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(c, projectId, permille, text))
            } catch (_: Exception) {
            }
        }

        fun stop(c: Context) {
            try { c.stopService(Intent(c, ExportService::class.java)) } catch (_: Exception) {}
        }
    }
}

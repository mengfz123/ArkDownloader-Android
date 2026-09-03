package com.ark.chunkdownloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ark.chunkdownloader.MainActivity
import com.ark.chunkdownloader.R

class DownloadNotifier(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val download = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_download),
                NotificationManager.IMPORTANCE_LOW
            )
            val complete = NotificationChannel(
                CHANNEL_COMPLETE_ID,
                context.getString(R.string.channel_complete),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(download)
            manager.createNotificationChannel(complete)
        }
    }

    fun buildForegroundNotification(activeTasks: Int, summary: String): Notification {
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_downloading))
            .setContentText(if (activeTasks > 0) "$activeTasks 个任务 · $summary" else summary)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun notifyCompleted(fileName: String) {
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, fileName.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_COMPLETE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_complete))
            .setContentText(fileName)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(COMPLETE_BASE_ID + (fileName.hashCode() and 0xffff), n)
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val COMPLETE_BASE_ID = 2000
        const val CHANNEL_ID = "ark_download"
        const val CHANNEL_COMPLETE_ID = "ark_download_complete"
    }
}

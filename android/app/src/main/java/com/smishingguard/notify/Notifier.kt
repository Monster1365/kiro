package com.smishingguard.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smishingguard.MainActivity
import com.smishingguard.domain.RiskLevel

const val EXTRA_RECORD_ID = "record_id"

/**
 * 위험 알림. 문자 원문이나 URL 은 알림에 넣지 않고, 잠금 화면에서는 일반 문구만 보여준다.
 */
class Notifier(private val context: Context) {
    private val channelId = "smishing_alerts"

    init {
        val channel = NotificationChannel(channelId, "스미싱 위험 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "스미싱이 의심되는 문자를 확인했을 때 알려드려요"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Android 13+ 에서는 POST_NOTIFICATIONS 권한이 있어야 한다. */
    fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 실패해도 앱 내 결과 확인에는 영향이 없도록 예외를 삼킨다. @return 알림 생성 여부 */
    fun notifyRisk(recordId: String, level: RiskLevel): Boolean {
        if (level != RiskLevel.HIGH && level != RiskLevel.CAUTION) return false
        if (!canNotify()) return false
        return try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_RECORD_ID, recordId)
            }
            val pi = PendingIntent.getActivity(
                context, recordId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val publicVersion = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("스미싱 예방")
                .setContentText("확인이 필요한 알림이 있어요")
                .build()
            val n = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(level.headline)
                .setContentText("문자 속 링크를 누르지 마세요. 눌러서 자세히 확인하세요.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(context).notify(recordId.hashCode(), n)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }
}

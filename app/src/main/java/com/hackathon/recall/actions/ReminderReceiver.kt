package com.hackathon.recall.actions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hackathon.recall.MainActivity
import com.hackathon.recall.R
import com.hackathon.recall.RecallApp
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.i18n.docTypeName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Exact-alarm delivery: posts the expiry notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderNotifier.notify(
                    context,
                    intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1),
                    intent.getLongExtra(ReminderScheduler.EXTRA_DOC_ID, -1),
                    intent.getIntExtra(ReminderScheduler.EXTRA_OFFSET, 0),
                )
            } finally {
                pending.finish()
            }
        }
    }
}

/** Inexact delivery when the user has not allowed exact alarms. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ReminderNotifier.notify(
            applicationContext,
            inputData.getLong(ReminderScheduler.EXTRA_REMINDER_ID, -1),
            inputData.getLong(ReminderScheduler.EXTRA_DOC_ID, -1),
            inputData.getInt(ReminderScheduler.EXTRA_OFFSET, 0),
        )
        return Result.success()
    }
}

/** Re-arms reminders after a reboot, an app update, or a clock change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as RecallApp).container.reminders.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}

object ReminderNotifier {
    const val CHANNEL = "expiry"

    suspend fun notify(context: Context, reminderId: Long, docId: Long, offset: Int) {
        val container = (context.applicationContext as RecallApp).container
        val doc = container.repository.byId(docId) ?: return
        container.database.reminders().setState(reminderId, "fired")
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_expiry), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_RENEWAL_DOC_ID, docId)
        val tap = PendingIntent.getActivity(context, reminderId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val name = context.docTypeName(doc.effectiveType())
        val title = if (offset > 0) context.resources.getQuantityString(R.plurals.expires_in_days, offset, name, offset)
        else context.getString(R.string.reminder_test_title, name)
        // On a locked screen show only that a reminder exists, not which document ("Medical report expires…").
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recall)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.reminder_body))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recall)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        NotificationManagerCompat.from(context).notify(reminderId.toInt(), notification)
    }
}

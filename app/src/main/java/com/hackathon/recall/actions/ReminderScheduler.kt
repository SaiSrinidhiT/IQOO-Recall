package com.hackathon.recall.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hackathon.recall.data.RecallDb
import com.hackathon.recall.data.ReminderRow
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Expiry reminders 30, 7 and 1 days before expiry at 09:00 local time (brief F7). Exact alarms when
 * the user allows them, otherwise WorkManager (inexact, still delivered). Offsets already in the past
 * are skipped; the UI shows expired documents as expired.
 */
class ReminderScheduler(private val context: Context, private val db: () -> RecallDb) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = alarms.canScheduleExactAlarms()

    suspend fun scheduleFor(docId: Long, expiry: LocalDate, nowMillis: Long = System.currentTimeMillis()) {
        cancelFor(docId)
        val dao = db().reminders()
        for (offset in OFFSETS) {
            val fireAt = expiry.minusDays(offset.toLong()).atTime(FIRE_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (fireAt <= nowMillis) {
                dao.insert(ReminderRow(docId = docId, fireAt = fireAt, offsetDays = offset, state = "skipped_past"))
                continue
            }
            val id = dao.insert(ReminderRow(docId = docId, fireAt = fireAt, offsetDays = offset, state = "scheduled"))
            arm(id, docId, offset, fireAt)
        }
    }

    /** Debug gate for brief Phase 5: a reminder one minute from now for [docId]. */
    suspend fun scheduleTest(docId: Long, delayMillis: Long = 60_000) {
        val fireAt = System.currentTimeMillis() + delayMillis
        val id = db().reminders().insert(ReminderRow(docId = docId, fireAt = fireAt, offsetDays = 0, state = "scheduled"))
        arm(id, docId, 0, fireAt)
    }

    suspend fun cancelFor(docId: Long) {
        val dao = db().reminders()
        for (r in dao.forDoc(docId)) {
            alarms.cancel(pendingIntent(r.id, r.docId, r.offsetDays))
            WorkManager.getInstance(context).cancelUniqueWork(workName(r.id))
        }
        dao.deleteForDoc(docId)
    }

    /** After reboot or a time change: re-arm every reminder still in the future. */
    suspend fun rescheduleAll(nowMillis: Long = System.currentTimeMillis()) {
        val dao = db().reminders()
        for (r in dao.scheduled()) {
            if (r.fireAt <= nowMillis) dao.setState(r.id, "skipped_past") else arm(r.id, r.docId, r.offsetDays, r.fireAt)
        }
    }

    private fun arm(reminderId: Long, docId: Long, offset: Int, fireAt: Long) {
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent(reminderId, docId, offset))
        } else {
            val delay = Duration.ofMillis((fireAt - System.currentTimeMillis()).coerceAtLeast(0))
            val work = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(EXTRA_REMINDER_ID to reminderId, EXTRA_DOC_ID to docId, EXTRA_OFFSET to offset))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(reminderId), ExistingWorkPolicy.REPLACE, work)
        }
    }

    private fun pendingIntent(reminderId: Long, docId: Long, offset: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_REMINDER_ID, reminderId)
            .putExtra(EXTRA_DOC_ID, docId)
            .putExtra(EXTRA_OFFSET, offset)
        return PendingIntent.getBroadcast(context, reminderId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun workName(reminderId: Long) = "reminder-$reminderId"

    companion object {
        val OFFSETS = listOf(30, 7, 1)
        val FIRE_TIME: LocalTime = LocalTime.of(9, 0)
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_DOC_ID = "doc_id"
        const val EXTRA_OFFSET = "offset_days"
    }
}

package com.hackathon.recall.ingest

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hackathon.recall.RecallApp
import com.hackathon.recall.model.SourceKind
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.time.Duration

/**
 * Background indexing (brief F1). Scans MediaStore, then works through pending index_state rows in
 * batches, reporting progress and ETA. Each item's outcome is written back immediately, so a killed
 * process resumes where it stopped: the item in flight is still `pending` and is simply redone.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RecallApp).container
        c.models.awaitWarm()
        c.scanner.scan()
        val dao = c.database.indexState()
        var done = 0
        var left = pendingCount(dao)
        setProgress(workDataOf(KEY_DONE to 0, KEY_REMAINING to left, KEY_ETA_SEC to 0L))
        val started = System.nanoTime()
        while (!isStopped) {
            val batch = dao.pending(BATCH)
            if (batch.isEmpty()) break
            left = maxOf(pendingCount(dao), batch.size)
            for (row in batch) {
                if (isStopped) break
                val outcome = try {
                    val uri = Uri.parse(row.uri)
                    val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw FileNotFoundException(row.uri)
                    c.pipeline.ingest(
                        IngestPipeline.Source(
                            bytes = bytes,
                            mime = row.mimeType ?: "image/*",
                            kind = SourceKind.GALLERY,
                            uri = row.uri,
                            capturedAt = row.takenAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            relativePath = row.relativePath,
                        ),
                    )
                } catch (e: CancellationException) {
                    // User pressed Stop: leave this row pending so Resume redoes it, don't record it as failed.
                    throw e
                } catch (e: Exception) {
                    IngestPipeline.Outcome.Failed(e.javaClass.simpleName)
                }
                val updated = when (outcome) {
                    is IngestPipeline.Outcome.Saved -> row.copy(status = "done", docId = outcome.docId)
                    is IngestPipeline.Outcome.ExactDuplicate -> row.copy(status = "done", docId = outcome.existing.id)
                    is IngestPipeline.Outcome.NotADocument -> row.copy(status = "skipped_non_doc", gateLabel = outcome.label, gateMargin = outcome.margin)
                    is IngestPipeline.Outcome.Failed -> row.copy(status = "failed", error = outcome.reason, attempts = row.attempts + 1)
                }
                dao.update(updated.copy(updatedAt = System.currentTimeMillis()))
                done++
                left = (left - 1).coerceAtLeast(0)
                val perItemMs = (System.nanoTime() - started) / 1_000_000 / done
                setProgress(workDataOf(KEY_DONE to done, KEY_REMAINING to left, KEY_ETA_SEC to perItemMs * left / 1000))
            }
        }
        enqueueOnNewMedia(applicationContext)
        return Result.success()
    }

    private suspend fun pendingCount(dao: com.hackathon.recall.data.IndexStateDao): Int =
        dao.counts().firstOrNull { it.status == "pending" }?.n ?: 0

    companion object {
        const val UNIQUE = "index-gallery"
        const val TRIGGER = "index-on-new-media"
        const val KEY_DONE = "done"
        const val KEY_REMAINING = "remaining"
        const val KEY_ETA_SEC = "eta_sec"
        private const val BATCH = 25
        private const val PREFS = "settings"
        private const val KEY_PAUSED = "index_paused"

        /** True after the user pressed Stop; automatic runs (app start, new photos) stay off until Resume. */
        fun isPaused(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

        private fun setPaused(context: Context, paused: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, paused).apply()

        /** Stop scanning now. Finished photos keep their results; unfinished ones stay pending for Resume. */
        fun stop(context: Context) {
            setPaused(context, true)
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(UNIQUE)
                cancelUniqueWork(TRIGGER)
            }
        }

        /**
         * Index now. [userInitiated] (photo access just granted, Resume, user is watching the progress
         * bar) clears a pause, replaces a run that may have scanned before the grant, and skips the
         * battery constraint; replacing is safe because in-flight rows stay pending and are redone.
         */
        fun enqueue(context: Context, userInitiated: Boolean = false) {
            if (userInitiated) setPaused(context, false) else if (isPaused(context)) return
            val constraints = if (userInitiated) Constraints.NONE else Constraints.Builder().setRequiresBatteryNotLow(true).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE, if (userInitiated) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build(),
            )
        }

        /** Wake up when new images land in MediaStore, even if the app isn't running (content URI trigger). */
        fun enqueueOnNewMedia(context: Context) {
            if (isPaused(context)) return
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
                .setTriggerContentMaxDelay(Duration.ofMinutes(2))
                .setRequiresBatteryNotLow(true)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                TRIGGER, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build(),
            )
        }
    }
}

/** While the app process is alive, new photos are picked up within seconds. */
class GalleryObserver(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    fun register() {
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        IndexWorker.enqueue(context)
    }
}

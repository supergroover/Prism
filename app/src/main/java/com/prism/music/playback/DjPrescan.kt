package com.prism.music.playback

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.container
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * DJ mode's head start: once a day, on Wi-Fi while the phone charges, the songs the listener plays most and likes get
 * their quick scan ([MixTempo.quickScan]), so a DJ set of their own music starts in key order rather than reading as it
 * goes. Up to [MAX_SONGS] a run, for at most [MAX_MINUTES]; already scanned songs cost nothing.
 *
 * Only while the phone is left alone (1.8.1.1, #343): one song at a time at the lowest priority, and it stops (to try
 * again later) as soon as the screen comes on or music plays. Three at once kept a budget phone's audio decoder at
 * ~60% of its CPU while the listener used Prism.
 */
class DjPrescanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val power = applicationContext.getSystemService(PowerManager::class.java)
        fun idle() = mayRun(screenOn = power?.isInteractive != false, playing = c.player.isPlaying.value)
        if (!idle()) return Result.retry()
        val now = System.currentTimeMillis()
        val ids = (c.db.plays().mostPlayedIds(now - 90L * 86_400_000, MAX_SONGS) + c.db.songs().liked().map { it.id })
            .distinct()
            .filter { !it.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX) && !it.startsWith(com.prism.music.data.model.Song.RADIO_PREFIX) && !c.mixTempo.keyed(it) }
            .take(MAX_SONGS)
        if (ids.isEmpty()) return Result.success()
        val started = System.currentTimeMillis()
        var done = 0
        var interrupted = false
        withTimeoutOrNull(MAX_MINUTES * 60_000L) {
            for (id in ids) {
                if (!idle()) { interrupted = true; break }
                if (c.mixTempo.quickScan(id, network = true, priority = android.os.Process.THREAD_PRIORITY_LOWEST) == MixTempo.QuickResult.KEYED) done++
            }
        }
        Log.i("PrismDj", "pre-scanned $done of ${ids.size} songs in ${(System.currentTimeMillis() - started) / 1000} s" + if (interrupted) " (the phone's in use: later)" else "")
        return if (interrupted) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_SONGS = 200
        private const val MAX_MINUTES = 20

        /** Only while nobody's using the phone: the screen off and nothing playing. */
        internal fun mayRun(screenOn: Boolean, playing: Boolean) = !screenOn && !playing

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<DjPrescanWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("dj_prescan", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

package com.prism.music.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.prism.music.data.stream.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What Mix knows about a song's rhythm and edges, all in ms from the song's start.
 *
 * @property bpm tempo, as heard (an octave can be off: 85 for a 170 BPM song); Mix compares tempos octave-free.
 * @property confidence how clearly the beat stood out (a z-score of the tempo's autocorrelation peak); ambient
 *   songs and spoken intros score low, and Mix then blends without lining beats up.
 * @property firstSoundMs where the music starts (leading silence ends).
 * @property lastSoundMs where the music ends (trailing silence or a fade-out's tail begins).
 * @property firstBeatMs the first beat at or after [firstSoundMs].
 * @property outroBeatMs a beat close to [lastSoundMs]; the song's last bars lie on `outroBeatMs + k × periodMs`.
 * @property fadeOutMs how long the song takes to die away before [lastSoundMs] (its fade-out, or a quiet last
 *   passage), up to 20 s; about 0 for a song that ends on a hit. -1 when measured before this was.
 * @property quietIntroMs how long after [firstSoundMs] the song stays quiet before it comes up to full level, up to
 *   20 s; about 0 for one that starts at full tilt. -1 when measured before this was.
 */
data class Tempo(
    val bpm: Double,
    val confidence: Double,
    val firstSoundMs: Long,
    val lastSoundMs: Long,
    val firstBeatMs: Long,
    val outroBeatMs: Long,
    val fadeOutMs: Long = -1,
    val quietIntroMs: Long = -1,
) {
    val periodMs: Double get() = 60_000.0 / bpm
    /** Clear enough to line beats up on. */
    val steady: Boolean get() = confidence >= MIN_CONFIDENCE

    fun encode() = "$VERSION|$bpm|$confidence|$firstSoundMs|$lastSoundMs|$firstBeatMs|$outroBeatMs|$fadeOutMs|$quietIntroMs"

    /** Measured before the song's fade-out and intro were (Mix's Auto length measures it again). */
    val edgesUnknown: Boolean get() = fadeOutMs < 0 || quietIntroMs < 0

    companion object {
        const val MIN_CONFIDENCE = 3.0
        /** Bumped when [analyze] changes, so songs measured the old way are measured again. */
        private const val VERSION = 3

        /** Version 2 (no fade-out or intro measured) still reads; [MixTempo.measure] scans those songs again. */
        fun decode(s: String): Tempo? = runCatching {
            val p = s.split('|')
            when (p[0].toInt()) {
                VERSION -> Tempo(p[1].toDouble(), p[2].toDouble(), p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong(), p[7].toLong(), p[8].toLong())
                2 -> Tempo(p[1].toDouble(), p[2].toDouble(), p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong())
                else -> null
            }
        }.getOrNull()

        /** Whether a stored reading is from before [VERSION] 3. */
        fun isOld(s: String) = s.startsWith("2|")

        /**
         * Reads tempo and edges from a song's band energies ([VideoSync.bandFeatures]: log energy per band, one frame
         * every [frameMs]). Onsets are rises in band energy; the tempo is the autocorrelation lag (60–200 BPM) that
         * stands out most, weighted toward 120 BPM so a song's half or double time isn't picked over it; beats are
         * placed by sliding that period over the onsets near each end of the song. [offsetMs] is added to every time
         * read: a frame's energy is its whole window's, so half a window puts each frame at its window's middle.
         */
        fun analyze(bands: Array<FloatArray>, frameMs: Double, offsetMs: Double = 0.0): Tempo? {
            val frames = bands.firstOrNull()?.size ?: return null
            if (frames < 3000 / frameMs) return null
            val fps = 1000.0 / frameMs

            // Loudness per frame (all bands together), for the music's start and end.
            val loud = FloatArray(frames) { t ->
                var m = Float.NEGATIVE_INFINITY
                for (b in bands) m = max(m, b[t])
                var s = 0.0
                for (b in bands) s += exp((b[t] - m).toDouble())
                (m + ln(s)).toFloat()
            }
            val sorted = loud.sortedArray()
            val peak = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
            val floor = peak - SILENCE_BELOW_PEAK
            val smooth = 5
            fun audible(t: Int): Boolean {
                var n = 0
                for (i in t until min(frames, t + smooth)) if (loud[i] > floor) n++
                return n >= smooth - 1
            }
            val first = (0 until frames).firstOrNull(::audible) ?: return null
            val last = (frames - 1 downTo 0).firstOrNull(::audible) ?: return null
            if (last - first < 10 * fps) return null

            // Onset strength: summed rises in band energy, minus its local average, kept positive.
            val flux = FloatArray(frames)
            for (t in 1 until frames) {
                var s = 0f
                for (b in bands) { val d = b[t] - b[t - 1]; if (d > 0) s += d }
                flux[t] = s
            }
            val half = (0.2 * fps).toInt().coerceAtLeast(1)
            val pre = DoubleArray(frames + 1).also { for (i in 0 until frames) it[i + 1] = it[i] + flux[i] }
            val od = FloatArray(frames) { t ->
                val lo = max(0, t - half); val hi = min(frames, t + half + 1)
                max(0.0, flux[t] - (pre[hi] - pre[lo]) / (hi - lo)).toFloat()
            }

            // Tempo: autocorrelation over the music (up to two minutes of it, from its middle).
            val span = min(last - first, (120 * fps).toInt())
            val from = first + ((last - first) - span) / 2
            val to = from + span
            val lagMin = (fps * 60 / 200).toInt()
            val lagMax = (fps * 60 / 60).toInt() + 1
            // Autocorrelation out to four beats of the slowest tempo, so every candidate gets its multiples' support.
            val acfMax = lagMax * 4
            if (to - from <= acfMax * 2) return null
            val acf = DoubleArray(acfMax + 1)
            for (lag in lagMin..acfMax) {
                var s = 0.0
                for (t in from until to - lag) s += od[t].toDouble() * od[t + lag]
                acf[lag] = s / (to - from - lag)
            }
            fun weighted(lag: Int): Double {
                val bpm = 60 * fps / lag
                val prior = exp(-0.5 * sq(ln(bpm / 120.0) / ln(2.0) / 0.9))
                // A beat also lines up with itself two and four beats on (a bar), which a 3:2 or 4:3 cousin of
                // the tempo (a triplet feel, a syncopated hook) doesn't: those multiples back the true period.
                return (acf[lag] + 0.5 * acf[2 * lag] + 0.25 * acf[4 * lag]) * prior
            }
            val scores = (lagMin..lagMax).map { weighted(it) }
            val bestIdx = scores.indices.maxBy { scores[it] }
            val best = lagMin + bestIdx
            val mean = scores.average()
            val sd = sqrt(scores.sumOf { sq(it - mean) } / scores.size).takeIf { it > 1e-12 } ?: return null
            val confidence = (scores[bestIdx] - mean) / sd
            // Sub-frame period from a parabola through the peak and its neighbours.
            var lag = if (best in lagMin + 1 until lagMax) {
                val a = acf[best - 1]; val b = acf[best]; val c = acf[best + 1]
                val d = a - 2 * b + c
                best + (if (d < 0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0)
            } else best.toDouble()

            /** Onset strength landing on a comb of beats [period] apart, starting at [start], up to frame [b]. */
            fun comb(start: Double, b: Int, period: Double): Double {
                var s = 0.0
                var t = start
                while (t < b - 1) { s += interp(od, t); t += period }
                return s
            }

            /** The first beat in [a, b) of the comb that lands on the most onset strength. */
            fun phase(a: Int, b: Int, period: Double = lag): Double {
                var bestPhase = 0.0; var bestScore = -1.0
                var ph = 0.0
                while (ph < period) {
                    val s = comb(a + ph, b, period)
                    if (s > bestScore) { bestScore = s; bestPhase = ph }
                    ph += 0.5
                }
                return a + bestPhase
            }

            // Fast tempos are often a song's eighth notes (kick on the beat, hi-hat between). If every other "beat"
            // is clearly stronger, the song is at half that tempo and the strong ones are its beats; otherwise
            // (drum & bass: kick and snare both hit hard) the fast tempo stands. Only above 152: a rock song at
            // 145–150 (Mr. Brightside, 148) has a kick on 1 and 3 louder than its snare on 2 and 4, and was halved
            // to 74; the ballads this is for read 158–184 here (Hello, Ain't No Sunshine, Creep).
            if (60 * fps / lag > HALVE_ABOVE_BPM && 60 * fps / (2 * lag) >= 70) {
                val strong = phase(from, to, 2 * lag)
                val a = comb(strong, to, 2 * lag)
                val b = comb(strong + lag, to, 2 * lag)
                if (a > 1.25 * b) lag *= 2
            }
            // Fine tempo: the autocorrelation's peak is good to about a frame; a beat comb laid over the whole
            // stretch drifts off the onsets with even a tiny error, so the period whose best comb scores highest
            // is the exact one. (A 1% error is a beat's worth of drift over a minute, and a blend leans on the
            // ending song's beats being where its last bars put them.)
            run {
                var bestP = lag; var bestS = -1.0
                var p = lag * 0.985
                while (p <= lag * 1.015) {
                    var ph = 0.0
                    while (ph < p) {
                        val s = comb(from + ph, to, p)
                        if (s > bestS) { bestS = s; bestP = p }
                        ph += 1.0
                    }
                    p += lag * 0.0005
                }
                lag = bestP
            }
            val bpm = 60 * fps / lag

            val window = (20 * fps).toInt()
            val introAnchor = phase(first, min(last, first + window))
            // First beat at or after the music starts (a hair early still counts: onsets lead the peak slightly).
            var firstBeat = introAnchor
            while (firstBeat - lag >= first - 3) firstBeat -= lag
            while (firstBeat < first - 3) firstBeat += lag
            val outroFrom = max(first, last - window)
            val outroAnchor = phase(outroFrom, last + 1)
            var outroBeat = outroAnchor
            while (outroBeat + lag <= last) outroBeat += lag

            val (fadeOut, quietIntro) = edges(loud, first, last, fps)
            return Tempo(
                bpm = bpm,
                confidence = confidence,
                firstSoundMs = (first * frameMs + offsetMs).toLong(),
                lastSoundMs = (last * frameMs + offsetMs).toLong(),
                firstBeatMs = (firstBeat * frameMs + offsetMs).toLong(),
                outroBeatMs = (outroBeat * frameMs + offsetMs).toLong(),
                fadeOutMs = (fadeOut * frameMs).toLong(),
                quietIntroMs = (quietIntro * frameMs).toLong(),
            )
        }

        /**
         * How the song dies away and comes in, in frames: from where its level (averaged over a second) last stands
         * near the song's typical level to where its music ends, and from where its music starts to where that level
         * is first reached. [loud] is per-frame log power over the music [first]..[last].
         */
        private fun edges(loud: FloatArray, first: Int, last: Int, fps: Double): Pair<Int, Int> {
            // A second's level is its energy averaged (not its log): the quiet gaps between beats would otherwise weigh as
            // much as the beats, and hide a fade until it was nearly over.
            val ref = loud.max()
            val half = (fps / 2).toInt().coerceAtLeast(1)
            val sums = DoubleArray(loud.size + 1).also { for (i in loud.indices) it[i + 1] = it[i] + exp((loud[i] - ref).toDouble()) }
            fun level(t: Int): Double {
                val lo = max(first, t - half); val hi = min(last + 1, t + half + 1)
                return ln(((sums[hi] - sums[lo]) / (hi - lo)).coerceAtLeast(1e-30)) + ref
            }
            // The song's usual level: the median of its seconds.
            val seconds = (first..last step half).map(::level).sorted()
            val typical = seconds[seconds.size / 2]
            val reach = (EDGE_MAX_S * fps).toInt()
            // Back from the end: the last moment the song is still near its usual level begins its fade.
            var fadeFrom = last
            while (fadeFrom > max(first, last - reach) && level(fadeFrom) < typical - FADE_BELOW) fadeFrom--
            // On from the start: the intro is quiet until it first comes near the usual level.
            var fullFrom = first
            while (fullFrom < min(last, first + reach) && level(fullFrom) < typical - INTRO_BELOW) fullFrom++
            return (last - fadeFrom) to (fullFrom - first)
        }

        /** A song's edges are looked for this far in (s): a longer fade or intro counts as this long. */
        private const val EDGE_MAX_S = 20.0
        /** Under the song's typical level by this much (log power: 4.5 dB), its ending is fading. */
        private const val FADE_BELOW = 1.04
        /** Under it by this much (6 dB), its intro is still quiet. */
        private const val INTRO_BELOW = 1.38

        /** Below the song's loud parts by this much (natural log of power: 40 dB), it counts as silence. */
        private const val SILENCE_BELOW_PEAK = 9.2f
        /** A tempo read faster than this may be a song's eighth notes, and is halved when every other beat leads. */
        const val HALVE_ABOVE_BPM = 152.0

        private fun sq(x: Double) = x * x

        private fun interp(x: FloatArray, t: Double): Double {
            val i = floor(t).toInt()
            if (i < 0 || i + 1 >= x.size) return 0.0
            val f = t - i
            return x[i] * (1 - f) + x[i + 1] * f
        }
    }
}

/**
 * Scans songs, once each: their tempo and edges for Mix ([Tempo]) and their loudness for normalization
 * ([integratedLoudness]). The song's audio is read from the phone when it's there ([local]: a download, the song
 * cache, a local file), else its smallest stream is fetched whole (1–4 MB, on Wi-Fi unless mobile data is allowed);
 * it's decoded to mono ~11 kHz and measured. Results are kept per song (`mix_tempo` and `song_loudness` prefs).
 */
class MixTempo(
    private val context: Context,
    private val http: OkHttpClient,
    private val streams: StreamResolver,
    /** Where the song's audio is on the phone, if it is (may copy it to the file it's given), else null. */
    private val local: (id: String, scratch: File) -> android.net.Uri? = { _, _ -> null },
    /** Blocks while the playing song is still loading (a scan starting then competed with it); off the main thread. */
    private val yieldToPlaying: () -> Unit = {},
    /** Music is playing right now (DJ mode's quick scans then go one at a time). */
    private val playing: () -> Boolean = { false },
    /** Where each scanned song's outline goes, for the waveform progress bar (1.7.4). */
    private val waves: com.prism.music.data.SongWaves? = null,
) {
    private val prefs = context.getSharedPreferences("mix_tempo", Context.MODE_PRIVATE)
    /** Song id -> integrated loudness (LUFS; NaN when it couldn't be measured). Also marks a song as scanned. */
    private val loudPrefs = context.getSharedPreferences("song_loudness", Context.MODE_PRIVATE)
    /** Song id -> its loudest sample (dBFS), measured with its loudness since 1.8.0: how far Normalize may lift it. */
    private val peakPrefs = context.getSharedPreferences("song_peak", Context.MODE_PRIVATE)
    /** Song id -> its key ([MusicKey.encode], or "none" when it has no clear one); DJ mode's harmonic ordering. */
    private val keyPrefs = context.getSharedPreferences("song_key", Context.MODE_PRIVATE)
    private val failed = ConcurrentHashMap.newKeySet<String>()
    /** One song at a time: decoding a whole song is the heaviest thing Prism does in the background. */
    private val lock = Mutex()

    /** Forgets what was measured of [id] (another version of it plays now), so it's measured again. */
    fun forget(id: String) {
        for (p in listOf(prefs, loudPrefs, peakPrefs, keyPrefs, quickPrefs)) p.edit().remove(id).apply()
    }

    fun cached(id: String): Tempo? = prefs.getString(id, null)?.let(Tempo::decode)

    /** The song's measured loudness (integrated LUFS), once it's been scanned. */
    fun loudness(id: String): Float? = loudPrefs.getFloat(id, Float.NaN).takeIf { !it.isNaN() }

    /** The song's loudest sample (dBFS), once it's been scanned (by 1.8.0 or later). */
    fun peak(id: String): Float? = peakPrefs.getFloat(id, Float.NaN).takeIf { !it.isNaN() }

    /** Measured before peaks were (1.8.0): Normalize can lift it no more than 6 dB until it's scanned again. */
    private fun noPeak(id: String) = loudness(id) != null && !peakPrefs.contains(id)

    /** The song's key once it's been scanned for one (null for none clear, or not scanned yet). */
    fun key(id: String): MusicKey? = keyPrefs.getString(id, null)?.let(MusicKey::decode)

    /** Scanned for its key by the current [KeyDetector] (with or without a clear answer). */
    fun keyed(id: String) = keyPrefs.getString(id, null)?.let { it.startsWith("${MusicKey.VERSION}|") || it == "none:${MusicKey.VERSION}" } == true

    private fun scanned(id: String) = loudPrefs.contains(id)

    /** Scanned before the song's fade-out and intro were measured (for Mix's Auto length): scanned again once. */
    private fun old(id: String) = prefs.getString(id, null)?.let(Tempo::isOld) == true

    private class Scan(val tempo: Tempo?, val lufs: Float?, val key: MusicKey?, val peakDb: Float?)

    // ---------------------------------------------------------------- DJ mode's quick scan

    /** Song id -> "bpm|confidence|lufs" from a [QuickScan] (the full scan's figures win when there are both). */
    private val quickPrefs = context.getSharedPreferences("dj_quick", Context.MODE_PRIVATE)
    /** A few quick scans at once (each is a third of a song, at background priority); separate from the full scans. */
    private val quickGate = kotlinx.coroutines.sync.Semaphore(QUICK_AT_ONCE)

    enum class QuickResult { KEYED, NEEDS_NETWORK, FAILED }

    /** The tempo DJ mode goes by: the full scan's when it's steady, else the quick scan's when that's steady. */
    fun djBpm(id: String): Double? = cached(id)?.takeIf { it.steady }?.bpm ?: quick(id)?.takeIf { it.second >= Tempo.MIN_CONFIDENCE }?.first

    /** The loudness DJ mode goes by (its energy): measured whole, else from the quick scan. */
    fun djLoudness(id: String): Float? = loudness(id) ?: quick(id)?.third

    private fun quick(id: String): Triple<Double, Double, Float?>? = quickPrefs.getString(id, null)?.split('|')?.let { p ->
        val bpm = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        Triple(bpm, p.getOrNull(1)?.toDoubleOrNull() ?: 0.0, p.getOrNull(2)?.toFloatOrNull()?.takeIf { !it.isNaN() })
    }

    /**
     * DJ mode's look at a song: its key, tempo and loudness from three stretches of it ([QuickScan]), fetched by range
     * when it isn't on the phone ([network] allowing). A song already scanned for its key is [QuickResult.KEYED] at once.
     */
    suspend fun quickScan(id: String, network: Boolean, priority: Int = android.os.Process.THREAD_PRIORITY_BACKGROUND): QuickResult {
        if (keyed(id)) return QuickResult.KEYED
        if (id in failed) return QuickResult.FAILED
        // While music plays, one at a time (Reddit 1.7: "DJ mode is so laggy": three decodes beside playback were too
        // much for mid-range phones); while paused, a few at once.
        return quickGate.withPermit { if (playing()) playingGate.withLock { quickScanNow(id, network, priority) } else quickScanNow(id, network, priority) }
    }

    /** One quick scan at a time while music plays ([quickScan]). */
    private val playingGate = kotlinx.coroutines.sync.Mutex()

    private suspend fun quickScanNow(id: String, network: Boolean, priority: Int): QuickResult {
        if (keyed(id)) return QuickResult.KEYED
        return run {
            withContext(Dispatchers.IO) {
                val started = System.nanoTime()
                val before = android.os.Process.getThreadPriority(android.os.Process.myTid())
                android.os.Process.setThreadPriority(priority)
                runCatching { yieldToPlaying() }
                AudioDiagnostics.log("background", "DJ quick scan of $id started")
                try {
                    val (r, bytes) = quickRead(id, network) ?: return@withContext QuickResult.NEEDS_NETWORK
                    keyPrefs.edit().putString(id, r.key?.encode() ?: "none:${MusicKey.VERSION}").apply()
                    quickPrefs.edit().putString(id, "${r.tempo?.bpm ?: 0.0}|${r.tempo?.confidence ?: 0.0}|${r.lufs ?: Float.NaN}").apply()
                    Log.i(
                        TAG, "quick-scanned $id in ${(System.nanoTime() - started) / 1_000_000} ms (${bytes / 1024} KB): key " +
                            (r.key?.let { "${it.name} (${it.camelot})" } ?: "unclear") + ", " +
                            (r.tempo?.let { "%.1f BPM (confidence %.1f)".format(it.bpm, it.confidence) } ?: "no clear beat"),
                    )
                    QuickResult.KEYED
                } catch (e: Exception) {
                    Log.w(TAG, "quick scan of $id failed: $e")
                    failed += id
                    QuickResult.FAILED
                } finally {
                    android.os.Process.setThreadPriority(before)
                    AudioDiagnostics.log("background", "DJ quick scan of $id ended after ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            }
        }
    }

    /** The quick scan's result and the bytes it downloaded; null when the song isn't on the phone and [network] says no. */
    private fun quickRead(id: String, network: Boolean): Pair<QuickScan.Result, Long>? {
        val scratch = File(context.cacheDir, "djq_$id.part")
        val ex = MediaExtractor()
        var ranged: RangedSource? = null
        try {
            var durationMs = 0L
            val uri = local(id, scratch)
            if (uri != null) ex.setDataSource(context, uri, null)
            else {
                if (!network || id.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX) || id.startsWith(com.prism.music.data.model.Song.RADIO_PREFIX)) return null
                val a = streams.analysisAudio(id)
                if (a.contentLength <= 0) throw java.io.IOException("the stream's length is unknown")
                ranged = RangedSource(http, a.url, a.contentLength)
                ex.setDataSource(ranged)
                durationMs = a.durationSec * 1000
            }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw java.io.IOException("no audio track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000
            if (durationMs <= 0) throw java.io.IOException("the song's length is unknown")
            val (pieces, sr) = QuickScan.decode(ex, track, QuickScan.windows(durationMs)) ?: throw java.io.IOException("couldn't decode its audio")
            return QuickScan.analyse(pieces, sr) to (ranged?.fetched ?: 0L)
        } finally {
            ex.release()
            ranged?.close()
            scratch.delete()
        }
    }

    /**
     * Scans the song now if it hasn't been ([network]: whether its audio may be downloaded when it isn't on the phone),
     * and returns its tempo; null when it has none that's clear, or it can't be scanned yet. [edges]: a song scanned
     * before its fade-out and intro were measured is scanned again (only Crossfade's automatic length needs them), and
     * with [key], one scanned before its key was (DJ mode), with [wave], one with no outline for the waveform bar, and
     * with [peak], one scanned before its peak was (Normalize).
     */
    suspend fun measure(
        id: String, network: Boolean = true, edges: Boolean = true, key: Boolean = false, wave: Boolean = false, peak: Boolean = false,
    ): Tempo? {
        fun done() = scanned(id) && !(edges && old(id)) && !(key && !keyed(id)) && !(wave && waves?.has(id) == false) &&
            !(peak && noPeak(id))
        if (done() || id in failed) return cached(id)
        return lock.withLock {
            if (done()) return@withLock cached(id)
            withContext(Dispatchers.IO) {
                val started = System.nanoTime()
                // Decoding a whole song is heavy: at background priority, so the playing song's own loading comes first.
                val before = android.os.Process.getThreadPriority(android.os.Process.myTid())
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runCatching { yieldToPlaying() }
                AudioDiagnostics.log("background", "song scan of $id started (Normalize/Crossfade/DJ)")
                val r = try {
                    runCatching { scan(id, network) }.onFailure { Log.w(TAG, "scan of $id failed: $it") }
                } finally {
                    android.os.Process.setThreadPriority(before)
                    AudioDiagnostics.log("background", "song scan of $id ended after ${(System.nanoTime() - started) / 1_000_000} ms")
                }
                val s = r.getOrNull()
                when {
                    r.isFailure -> { failed += id; cached(id) }
                    s == null -> cached(id) // not on the phone, and no network for it now
                    else -> {
                        prefs.edit().putString(id, s.tempo?.encode() ?: "none").apply()
                        loudPrefs.edit().putFloat(id, s.lufs ?: Float.NaN).apply()
                        peakPrefs.edit().putFloat(id, s.peakDb ?: Float.NaN).apply()
                        keyPrefs.edit().putString(id, s.key?.encode() ?: "none:${MusicKey.VERSION}").apply()
                        val ms = (System.nanoTime() - started) / 1_000_000
                        val t = s.tempo
                        Log.i(TAG, "scanned $id in $ms ms: loudness ${s.lufs?.let { "%.1f LUFS".format(it) } ?: "unknown"}, " +
                            (if (t == null) "no clear music" else "%.1f BPM (confidence %.1f), music %d–%d ms, first beat %d ms"
                                .format(t.bpm, t.confidence, t.firstSoundMs, t.lastSoundMs, t.firstBeatMs)) +
                            ", key ${s.key?.let { "${it.name} (${it.camelot}, confidence %.3f)".format(it.confidence) } ?: "unclear"}")
                        t
                    }
                }
            }
        }
    }

    /** Null when the song's audio isn't on the phone and [network] doesn't allow fetching it. */
    private fun scan(id: String, network: Boolean): Scan? {
        val file = File(context.cacheDir, "mix_$id.part")
        try {
            val uri = local(id, file) ?: run {
                if (!network || id.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX) || id.startsWith(com.prism.music.data.model.Song.RADIO_PREFIX)) return null
                Log.d(TAG, "downloading $id to scan it")
                val a = streams.analysisAudio(id)
                http.newCall(Request.Builder().url(a.url).apply {
                    if (a.contentLength > 0) header("Range", "bytes=0-${a.contentLength - 1}")
                }.build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("audio returned HTTP ${r.code}")
                    file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                }
                android.net.Uri.fromFile(file)
            }
            val (pcm, rate, peak) = decodeAll(uri) ?: throw java.io.IOException("couldn't decode its audio")
            val lufs = integratedLoudness(pcm, rate)?.let { it + MONO_TO_STEREO_DB }
            // The waveform progress bar's outline, from the same audio (1.7.4).
            waves?.let { w -> runCatching { w.put(id, com.prism.music.data.SongWaves.outline(pcm)) } }
            // Debug builds keep the decoded audio when asked (adb shell touch …/files/mix_dump), for tuning [Tempo.analyze].
            if (com.prism.music.BuildConfig.DEBUG) context.getExternalFilesDir(null)?.let { dir ->
                if (File(dir, "mix_dump").exists()) runCatching {
                    val bb = java.nio.ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (x in pcm) bb.putFloat(x)
                    File(dir, "mix_${id}_$rate.f32").writeBytes(bb.array())
                }
            }
            // The analysis steps are seconds of work each on a slow phone: each waits its turn too ([ease]).
            ease()
            val bands = VideoSync.bandFeatures(pcm, rate)
            val frameMs = (rate / 100) * 1000.0 / rate
            ease()
            val key = runCatching { KeyDetector.detect(pcm, rate) }.getOrNull()
            ease()
            val peakDb = peak.takeIf { it > 0f }?.let { (20 * kotlin.math.log10(it.toDouble())).toFloat() }
            return Scan(Tempo.analyze(bands, frameMs, offsetMs = VideoSync.WINDOW / 2 * 1000.0 / rate), lufs, key, peakDb)
        } finally {
            file.delete()
        }
    }

    /** The whole file as mono PCM, decimated to roughly 11 kHz, its rate, and its loudest sample before (0 … 1). */
    private fun decodeAll(uri: android.net.Uri): Triple<FloatArray, Int, Float>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            dec.configure(fmt, null, null, 0)
            dec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var factor = max(1, rate / 11025)
            var out = FloatArray(1 shl 20)
            var count = 0
            var acc = 0f
            var n = 0
            var peak = 0f
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val size = runCatching { ex.readSampleData(dec.getInputBuffer(i)!!, 0) }.getOrDefault(-1)
                        if (size < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                ease()
                val o = dec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (count == 0) factor = max(1, rate / 11025)
                } else if (o >= 0) {
                    val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames = info.size / (channels * (if (isFloat) 4 else 2))
                    for (f in 0 until frames) {
                        var m = 0f
                        for (c in 0 until channels) {
                            val v = if (isFloat) buf.float else buf.short / 32768f
                            if (v > peak) peak = v else if (-v > peak) peak = -v
                            m += v
                        }
                        acc += m / channels
                        if (++n == factor) {
                            if (count == out.size) out = out.copyOf(out.size * 2)
                            out[count++] = acc / factor
                            acc = 0f; n = 0
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (count > MAX_SECONDS * (rate / factor)) break
                } else if (inputDone && o == MediaCodec.INFO_TRY_AGAIN_LATER && count > 0) {
                    break
                }
            }
            val sr = rate / factor
            if (count < sr * 20) return null
            return Triple(out.copyOf(count), sr, peak)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    companion object {
        private const val TAG = "PrismMix"
        /** DJ mode's quick scans running at once. */
        private const val QUICK_AT_ONCE = 3

        /** When the sound last ran dry (SystemClock.elapsedRealtime; set by the playback service). */
        @Volatile var lastUnderrunAt = 0L

        /**
         * A scan's decoding steps aside while the sound has run dry in the last few seconds (GitHub #103: on a budget
         * phone, a song scan of a minute's work kept running while the output starved, again and again). Called from
         * the decode loops, off the main thread.
         */
        fun ease() {
            var waited = 0
            while (android.os.SystemClock.elapsedRealtime() - lastUnderrunAt < EASE_AFTER_UNDERRUN_MS && waited < MAX_EASE_MS) {
                Thread.sleep(250); waited += 250
            }
            // And on a phone with few cores, while the listener scrolls or taps (GitHub #343: a Redmi A3 lagged
            // scrolling playlists while the next songs were decoded beside it).
            waited = 0
            while (waited < MAX_TOUCH_WAIT_MS && stepsAside(slowPhone, touchDown, android.os.SystemClock.uptimeMillis() - touchedAt, urgentScans.get())) {
                Thread.sleep(100); waited += 100
            }
        }

        /** Whether this phone's scans make way for the listener's touches: few cores, little memory or Performance mode. */
        @Volatile var slowPhone: () -> Boolean = { false }
        @Volatile private var touchDown = false
        @Volatile private var touchedAt = -TOUCH_QUIET_MS
        /** Scans the song's end is waiting for (Crossfade near the end of a song): no scan steps aside meanwhile. */
        private val urgentScans = java.util.concurrent.atomic.AtomicInteger()

        /** Every touch on Prism's screen (MainActivity.dispatchTouchEvent): a volatile write or two, nothing more. */
        fun touched(action: Int) {
            touchedAt = android.os.SystemClock.uptimeMillis()
            when (action) {
                android.view.MotionEvent.ACTION_DOWN -> touchDown = true
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> touchDown = false
            }
        }

        /**
         * A scan waits while a finger is on the screen and for [TOUCH_QUIET_MS] after the last touch (a fling goes on
         * after the finger's lifted), on slow phones only, and never while a song's end is waiting for a scan.
         */
        internal fun stepsAside(slow: () -> Boolean, down: Boolean, sinceTouchMs: Long, urgent: Int): Boolean =
            (down || sinceTouchMs < TOUCH_QUIET_MS) && urgent == 0 && slow()

        /** Cores, memory and Performance mode, as [slowPhone] reads them. */
        internal fun isSlowPhone(cores: Int, mayBeSlow: Boolean, performanceMode: Boolean) = cores <= 4 || mayBeSlow || performanceMode

        /** [block] is a scan a song's end waits for: other scans stop stepping aside for touches until it's done. */
        internal suspend fun <T> urgently(block: suspend () -> T): T {
            urgentScans.incrementAndGet()
            try { return block() } finally { urgentScans.decrementAndGet() }
        }

        private const val TOUCH_QUIET_MS = 2_000L
        /** A minute at most per step: a scan still finishes while someone scrolls on and on. */
        private const val MAX_TOUCH_WAIT_MS = 60_000

        private const val EASE_AFTER_UNDERRUN_MS = 4_000L
        /** At most this long per step, so a scan still finishes on a phone that keeps running dry. */
        private const val MAX_EASE_MS = 4_000
        /** Longer than this (a DJ set, an hour-long mix) isn't measured past this point. */
        private const val MAX_SECONDS = 15 * 60
        /**
         * The scan hears the song in mono (left and right averaged), which reads about 3 dB quieter than both channels
         * counted apart, as LUFS does (exactly 3 dB for a centred sound, more for a wide one).
         */
        private const val MONO_TO_STEREO_DB = 3.0f

        /**
         * Integrated loudness after ITU-R BS.1770 / EBU R128 (what Spotify and YouTube normalize by): the signal
         * K-weighted (a high shelf ~+4 dB above 1.7 kHz, a high-pass at 38 Hz), its power taken over 400 ms blocks every
         * 100 ms, and averaged over the blocks above -70 LUFS and no more than 10 LU below their mean (so silence and
         * quiet passages don't drag it down). For one channel; null for silence or under half a second.
         */
        fun integratedLoudness(pcm: FloatArray, rate: Int): Float? {
            val block = (0.4 * rate).toInt()
            val hop = (0.1 * rate).toInt().coerceAtLeast(1)
            if (rate <= 0 || pcm.size < block + hop) return null
            // K-weighting: two biquads, worked out for this sample rate.
            val shelf = biquadHighShelf(rate, 1681.974450955533, 3.999843853973347, 0.7071752369554196)
            val pass = biquadHighPass(rate, 38.13547087602444, 0.5003270373238773)
            val sq = DoubleArray(pcm.size + 1)
            var s1 = 0.0; var s2 = 0.0; var p1 = 0.0; var p2 = 0.0
            for (i in pcm.indices) {
                // Direct form II transposed, one stage into the next.
                val x = pcm[i].toDouble()
                val y1 = shelf[0] * x + s1
                s1 = shelf[1] * x - shelf[3] * y1 + s2
                s2 = shelf[2] * x - shelf[4] * y1
                val y2 = pass[0] * y1 + p1
                p1 = pass[1] * y1 - pass[3] * y2 + p2
                p2 = pass[2] * y1 - pass[4] * y2
                sq[i + 1] = sq[i] + y2 * y2
            }
            val powers = ArrayList<Double>()
            var start = 0
            while (start + block <= pcm.size) {
                powers += (sq[start + block] - sq[start]) / block
                start += hop
            }
            fun lufs(power: Double) = -0.691 + 10 * kotlin.math.log10(power)
            val loud = powers.filter { it > 0 && lufs(it) > -70 }
            if (loud.isEmpty()) return null
            val relative = lufs(loud.average()) - 10
            val gated = loud.filter { lufs(it) > relative }
            return lufs(gated.ifEmpty { loud }.average()).toFloat()
        }

        /** [b0, b1, b2, a1, a2] (a0 = 1) for a high shelf (RBJ's cookbook, as BS.1770's stage 1 at any sample rate). */
        private fun biquadHighShelf(rate: Int, fc: Double, gainDb: Double, q: Double): DoubleArray {
            val a = Math.pow(10.0, gainDb / 40)
            val w = 2 * Math.PI * fc / rate
            val cw = kotlin.math.cos(w)
            val alpha = kotlin.math.sin(w) / (2 * q)
            val sa = 2 * kotlin.math.sqrt(a) * alpha
            val b0 = a * ((a + 1) + (a - 1) * cw + sa)
            val b1 = -2 * a * ((a - 1) + (a + 1) * cw)
            val b2 = a * ((a + 1) + (a - 1) * cw - sa)
            val a0 = (a + 1) - (a - 1) * cw + sa
            val a1 = 2 * ((a - 1) - (a + 1) * cw)
            val a2 = (a + 1) - (a - 1) * cw - sa
            return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        /** [b0, b1, b2, a1, a2] (a0 = 1) for a high-pass (BS.1770's stage 2, the "RLB" curve). */
        private fun biquadHighPass(rate: Int, fc: Double, q: Double): DoubleArray {
            val w = 2 * Math.PI * fc / rate
            val cw = kotlin.math.cos(w)
            val alpha = kotlin.math.sin(w) / (2 * q)
            val a0 = 1 + alpha
            return doubleArrayOf((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
        }
    }
}

package com.prism.music.playback

import com.prism.music.tr
import com.prism.music.data.friendlyMessage
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import com.prism.music.data.prefs.SessionButton
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.prism.music.MainActivity
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.toEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.math.roundToInt

data class VideoLookup(val id: String?, val lookupFailed: Boolean = false)

/**
 * Whether a song may cross into the next by their albums ([from] and [to] album ids): not while an album plays through
 * ([albumPlaying]), and with "Keep an album's songs whole" ([keepAlbums], #247) not wherever two of an album's songs
 * meet (a playlist, shuffle, a radio). An album played as a DJ set ([dj]) is a set: it blends.
 */
internal fun blendsAcrossAlbum(from: String?, to: String?, albumPlaying: Boolean, keepAlbums: Boolean, dj: Boolean): Boolean {
    val sameAlbum = from != null && from == to
    return dj || !(sameAlbum && (albumPlaying || keepAlbums))
}

@Serializable
private data class SavedQueue(
    val songs: List<Song>, val index: Int, val positionMs: Long, val title: String,
    // Shuffle (with its order) and repeat, so a restart keeps them (#101: shuffle was off again after Prism restarted).
    val shuffle: Boolean = false, val shuffleOrder: List<Int> = emptyList(), val repeat: Int = Player.REPEAT_MODE_OFF,
)

@OptIn(UnstableApi::class)
open class PlaybackService : MediaLibraryService(), Player.Listener {
    private val c by lazy { container }
    lateinit var player: ExoPlayer
        private set
    private lateinit var session: MediaLibrarySession
    private val tree by lazy { MediaTree(c) }
    lateinit var effects: AudioEffects
        private set
    /** Headphone correction (AutoEq): first, so the spatializer and the limiter work on the corrected sound. */
    /** Sing: the song's vocals taken out as it plays (first, on the music as it came from the decoder). */
    private val vocals by lazy { com.prism.music.playback.sing.VocalStemProcessor(c.sing) }
    private val headphoneEq = ParametricEqProcessor()
    /** Skip silence for songs: their start and end only (#210); episodes keep Media3's skipper. */
    private val edges = EdgeSilenceProcessor()
    private val spatial = SpatialAudioProcessor()
    /** "Pause when muted" (#157). */
    private val muteWatch by lazy { MuteWatch(this, player) { c.settings.current.behavior.pauseOnMute } }
    private val normalizer = NormalizerProcessor()
    private var songLoudness: Float? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val likeCommand = SessionCommand("prism.LIKE", Bundle.EMPTY)
    private val shuffleCommand = SessionCommand("prism.SHUFFLE", Bundle.EMPTY)
    private val repeatCommand = SessionCommand("prism.REPEAT", Bundle.EMPTY)
    private val lyricsCommand = SessionCommand("prism.CAR_LYRICS", Bundle.EMPTY)
    private val radioCommand = SessionCommand("prism.RADIO", Bundle.EMPTY)
    private val dislikeCommand = SessionCommand("prism.DISLIKE", Bundle.EMPTY)
    // 1.7.4: buttons the listener can put beside play (Settings → Player → Buttons → Notification and car).
    private val back10Command = SessionCommand("prism.BACK_10", Bundle.EMPTY)
    private val ahead30Command = SessionCommand("prism.AHEAD_30", Bundle.EMPTY)
    private val customCommands get() = listOf(likeCommand, shuffleCommand, repeatCommand, lyricsCommand, radioCommand, dislikeCommand, back10Command, ahead30Command)
    /** What the media session (Android Auto, the notification) sees: the player, with lyrics laid over the title and cover. */
    private lateinit var sessionPlayer: LyricsOverlayPlayer
    private lateinit var carLyrics: CarLyrics
    /** Android Auto's connections to Prism. */
    private val carControllers = HashSet<MediaSession.ControllerInfo>()
    private var playStartedAt = 0L
    private var accumulatedMs = 0L
    private var trackedSong: Song? = null
    /** What [trackedSong] is playing as part of, read when it starts (the queue may change before its play is counted). */
    private var trackedSource: String? = null
    /** The tracked play doesn't count towards taste (an excluded song, playlist or artist). */
    private var trackedExcluded = false
    private val retries = HashMap<String, Int>()
    /** When each music video last got its streams asked for afresh after an error ([videoRetryAllowed]). */
    private val videoRetriedAt = HashMap<String, Long>()
    private var saveJob: Job? = null
    private var extrasJob: Job? = null


    override fun onCreate() {
        super.onCreate()
        PlaybackServices.running = this::class.java
        if (com.prism.music.BuildConfig.DEBUG) {
            PlaybackServices.failForTest = { onPlayerError(it) }
            PlaybackServices.taskRemovedForTest = { onTaskRemoved(null) }
        }
        AudioDiagnostics.init(this)
        // Prism Car: the favorite and recent radio stations (their addresses) known before a queue with one comes back.
        if (com.prism.music.BuildConfig.CAR_BUILD) c.radioStations
        // Media3's own audio warnings (spurious AudioTrack timestamps, position jumps…) go into the diagnostics too.
        androidx.media3.common.util.Log.setLogger(object : androidx.media3.common.util.Log.Logger {
            private val system = androidx.media3.common.util.Log.Logger.DEFAULT
            override fun d(tag: String, message: String, throwable: Throwable?) = system.d(tag, message, throwable)
            override fun i(tag: String, message: String, throwable: Throwable?) = system.i(tag, message, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) {
                system.w(tag, message, throwable)
                AudioDiagnostics.log("media3", "W $tag: $message" + (throwable?.let { " ($it)" } ?: ""))
            }
            override fun e(tag: String, message: String, throwable: Throwable?) {
                system.e(tag, message, throwable)
                AudioDiagnostics.log("media3", "E $tag: $message" + (throwable?.let { " ($it)" } ?: ""))
            }
        })
        effects = AudioEffects(this)
        // Lossless mode keeps hi-res files (24-bit FLAC) at full resolution instead of rounding to 16-bit.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput || c.settings.current.lossless)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    // The visualizer's tap last (1.7.4): what you hear; it passes the sound through untouched and
                    // only copies it while a visualizer is on screen. Songs' silence is trimmed after Sing (which
                    // counts the song's own frames) and before the effects, through a chain that tells the player what
                    // was left out (#210).
                    .setAudioProcessorChain(
                        EdgeSilenceProcessor.Chain(
                            edges, arrayOf(vocals, edges, headphoneEq, spatial, normalizer, androidx.media3.exoplayer.audio.TeeAudioProcessor(VisualizerTap)),
                        )
                    )
                    .build()

            override fun buildAudioRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener, out: ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                FlacFloatGuardRenderer.install(out, context, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener)
            }
        }
        player = ExoPlayer.Builder(this, renderers)
            // Start (and resume after a switch to video) once 1 s is buffered rather than 2.5 s. Ahead of that, the whole
            // song: it used to keep only 30–60 s, so a signal drop longer than that (a tunnel, a lift, a weak spot) stalled
            // a streamed song even though the rest of it is only a few MB. Kept loading in one go (min = max), and the
            // song cache keeps what's loaded. 32 MB at most, so a music video (far bigger per minute) stays a minute or two.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(WHOLE_SONG_MS, WHOLE_SONG_MS, 1_000, 2_000)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .build()
            )
            .setMediaSourceFactory(
                PrismMediaSourceFactory(
                    c.playbackDataSourceFactory(), c.videoDataSourceFactory(),
                    localFiles(),
                    localAudio = { item -> losslessFileFor(item) },
                    liveDsf = c.liveDataSourceFactory(),
                    radioDsf = c.radioDataSourceFactory(),
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // Matching the display to the video's frame rate also throttled the whole
        // app (lyrics included) to 60 Hz; the UI should stay at the panel's full rate.
        player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        // Shuffled, Previous goes back to the song played last (see HistoryShuffleOrder).
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        player.addListener(this)
        player.addAnalyticsListener(StatsListener())
        player.addAnalyticsListener(DiagnosticsListener())
        applySkipSilence()

        sessionPlayer = LyricsOverlayPlayer(player)
        carLyrics = CarLyrics(this, c, player, sessionPlayer, scope)
        carLyrics.start()
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            // Covers for the notification, lock screen and car come through Prism's image loader, so saved ones show offline (#54).
            .setBitmapLoader(androidx.media3.session.CacheBitmapLoader(CoilBitmapLoader(this, scope)))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    // On a TV, Prism TV's own screen (1.8.0).
                    Intent(this, if (com.prism.music.ui.tv.TvDevice.isTv(this)) com.prism.music.TvActivity::class.java else com.prism.music.PrismActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
        // Wear OS (a watch's media controls) has its own keys for keeping the skip
        // buttons: without them it puts the first custom buttons (like, shuffle) where
        // previous/next go. Media3 only sets the phone/Auto equivalents. Set after build():
        // only a change of session extras reaches the platform session's extras.
        session.setSessionExtras(Bundle().apply {
            putBoolean("android.support.wearable.media.extra.RESERVE_SLOT_SKIP_TO_PREVIOUS", true)
            putBoolean("android.support.wearable.media.extra.RESERVE_SLOT_SKIP_TO_NEXT", true)
        })
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_notification)
        })
        // Keep the media notification (and the system media controls) up for a
        // paused or restored queue, not only while audio is actively playing.
        // Not after the listener swiped it away, until something plays again ([closedFromNotification]).
        setShowNotificationForIdlePlayer(
            if (notificationClosed()) MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER
            else MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS,
        )
        // Android refuses a foreground service started from the background unless the start is exempt (a media button,
        // the system's own media controls); a play from another app then can't make sound. Logged for #187.
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() =
                AudioDiagnostics.log("control", "Android refused to bring playback to the foreground (started from the background)")
        })

        effects.attach(player.audioSessionId)
        c.onStreamResolved = { id -> scope.launch { applyStreamStats(id) } }
        // The equalizer as set, or the preset picked for the output playing now (the car's, the earbuds'; A3).
        scope.launch {
            kotlinx.coroutines.flow.combine(c.settings.flow.map { it.eq }, c.headphoneEq.output, c.eqPresets.outputs, c.eqPresets.saved) { eq, out, map, saved ->
                com.prism.music.data.prefs.EqPresetStore.effective(eq, out?.key, map, saved)
            }.distinctUntilChanged().collect { eqNow = it; effects.apply(it) }
        }
        scope.launch { c.settings.flow.map { it.skipSilence to it.sound.skipSilenceEpisodes }.distinctUntilChanged().collect { applySkipSilence() } }
        scope.launch { c.settings.flow.map { it.sound.normalizeTarget to it.sound.normalizeAlbums }.distinctUntilChanged().drop(1).collect { applyNormalization() } }
        sessionPlayer.gentle = ::gentle
        scope.launch { c.settings.flow.map { it.behavior.pauseOnMute }.distinctUntilChanged().collect { muteWatch.update() } }
        scope.launch { c.settings.flow.map { it.audioDiagnostics }.distinctUntilChanged().collect { on -> AudioDiagnostics.setEnabled(on) { AudioDiagnostics.about(c) } } }
        // The buttons beside play follow Settings → Player → Buttons as soon as they're changed.
        scope.launch { c.settings.flow.map { it.sessionButtons }.distinctUntilChanged().drop(1).collect { updateButtons() } }
        // The waveform progress bar picked: the playing song (and what's next) get their outlines now, not at the next song.
        scope.launch {
            c.settings.flow.map { it.scrubberStyle == com.prism.music.data.prefs.ScrubberStyle.WAVE }.distinctUntilChanged().drop(1).collect { on ->
                if (on && ::player.isInitialized) scanAhead(player.currentMediaItem, *upcoming(QUEUE_AHEAD).toTypedArray())
            }
        }
        scope.launch { c.settings.flow.map { it.autoplay }.distinctUntilChanged().drop(1).collect { onAutoplayChanged(it) } }
        // "Keep playing videos" switched off: songs already lined up as their videos go back to songs.
        scope.launch { c.settings.flow.map { it.videoContinues }.distinctUntilChanged().drop(1).collect { if (it) armNextVideo() else disarmVideos() } }
        scope.launch { c.settings.flow.map { it.spatial to it.spatialAmount }.distinctUntilChanged().collect { (on, amount) -> spatial.configure(on, amount) } }
        scope.launch { c.settings.flow.map { it.normalize }.distinctUntilChanged().collect { applyNormalization() } }
        // Prism's own volume (#116), when the player's slider is set to it; full otherwise (the phone's volume rules).
        scope.launch {
            c.settings.flow.map { if (it.volumeControl == com.prism.music.data.prefs.VolumeControl.PRISM) it.appVolume else 1f }.distinctUntilChanged()
                .collect { NormalizerProcessor.appVolume = NormalizerProcessor.volumeFor(it) }
        }
        // The correction for whatever headphones are playing (none for the speaker, the car, or anything not picked for).
        scope.launch {
            c.headphoneEq.active.collect { b ->
                headphoneEq.configure(b?.profile, b?.strength ?: 1f)
            }
        }
        scope.launch { c.library.likedIds.collect { updateButtons() } }
        // Sing: getting the playing and next songs ready, and the key moved for the singer.
        scope.launch {
            kotlinx.coroutines.flow.combine(c.sing.on, c.sing.semitones) { on, st -> on to st }.collect { (on, _) ->
                val pitch = applyPitch()
                AudioDiagnostics.log("sing", "key ${c.sing.semitones.value} (pitch %.3f), Sing ${if (on) "on" else "off"}".format(pitch))
                if (on) singAhead()
            }
        }
        // "Get songs ready while Sing is off" switched on: start with the playing song.
        scope.launch { c.settings.flow.map { it.singAlwaysReady }.distinctUntilChanged().collect { if (it) singAhead() } }
        // Smart Shuffle: picks go in when it's turned on, and leave when it's turned off.
        scope.launch { c.queue.smartShuffle.drop(1).collect { on -> if (on) smartAhead() else dropSmartPicks() } }
        // DJ mode: the set is worked out when it's turned on, and again when its energy curve changes.
        scope.launch { c.dj.on.collect { on -> if (on) djAhead() else { djJob?.cancel(); djSoon?.cancel() } } }
        scope.launch { c.dj.rescan.drop(1).collect { if (c.dj.onNow) djAhead() } }
        scope.launch { c.settings.flow.map { it.djFlow }.distinctUntilChanged().drop(1).collect { resequence() } }
        scope.launch {
            c.settings.flow.map { Triple(it.carLyrics, it.carLyricsStyle, it.carLyricsOnPhone) }.distinctUntilChanged().collect {
                carLyrics.refresh(); updateButtons()
            }
        }
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                // An episode's place is saved every few seconds while it plays.
                if (++tick % 5 == 0 && player.isPlaying) saveEpisodePosition()
                // Widgets' progress bars move on every 15 s (more often would cost battery for little); with the screen
                // off nobody sees them, so they catch up the moment it's back on instead.
                if (tick % 15 == 0 && player.isPlaying) pushWidgets(progressOnly = true)
                else if (widgetsBehind && screenOn()) pushWidgets()
                // Diagnostics: every 10 s of playing, how far ahead it's loaded and what Prism's own processing cost.
                if (tick % 10 == 0 && player.isPlaying && AudioDiagnostics.enabled) AudioDiagnostics.log(
                    "tick",
                    "at ${player.currentPosition / 1000} s, ${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000} s loaded ahead; " +
                        (AudioDiagnostics.summary() ?: "no processing measured"),
                )
                if (player.mediaItemCount > 0) c.nerdStats.value = c.nerdStats.value.copy(
                    bufferMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
                    effects = listOfNotNull(
                        effects.activeSummary.takeIf { it.isNotBlank() && it != "None" },
                        headphoneEq.active?.let { "Headphone correction: ${it.name}" },
                    ).joinToString(" · ").ifEmpty { "None" },
                    audioSessionId = player.audioSessionId,
                )
            }
        }
        mix = MixEngine(
            main = player,
            scope = scope,
            tempo = c.mixTempo,
            // DJ mode blends every song into the next, beat-matched, at Crossfade's automatic length.
            // Not while casting: the renderer plays one song at a time (and the phone's own player is muted).
            mixMs = { c.settings.current.let { s -> if ((s.mix || c.dj.onNow) && !c.cast.casting) s.mixSeconds * 1000L else null } },
            auto = { c.settings.current.mixAuto || c.dj.onNow },
            beatMatch = { c.settings.current.mixBeatMatch || c.dj.onNow },
            harmonic = { from, to ->
                if (!c.dj.onNow) null else {
                    val a = c.mixTempo.key(from); val b = c.mixTempo.key(to)
                    if (a?.sure == true && b?.sure == true) MusicKey.clash(a, b) else null
                }
            },
            allowed = ::mayBlend,
            // Its song's end is waiting for it: no scan makes way for the listener's touches meanwhile (#343).
            scanFromPhone = { id -> scope.launch { MixTempo.urgently { c.mixTempo.measure(id, network = false) } } },
            buildTail = ::buildTailPlayer,
            onSettled = ::restoreMusicSpeed,
            pitch = ::pitchNow,
            curve = { c.settings.current.sound.crossfadeCurve },
            state = c.mixState,
        ).also { it.start() }
        // Prism Connect: the speaker or TV being cast to plays what this player plays, and its volume is the session's.
        sessionPlayer.remote = object : LyricsOverlayPlayer.Remote {
            override val volume: Int? get() = if (c.cast.casting) c.cast.volume else null
            override fun setVolume(v: Int) = c.cast.setVolume(v)
            override fun adjust(delta: Int) = c.cast.adjustVolume(delta)
        }
        c.cast.attach(player, object : com.prism.music.connect.Cast.Hooks {
            override fun beforeCasting() {
                mix.stopBlend()
                if (c.sing.onNow) c.sing.setOn(false)
                // The renderer gets the sound only: a video goes back to the song, and episodes play at 1×.
                videoWanted = false
                if (player.currentMediaItem?.videoModeId != null) scope.launch { setVideoMode(false) }
                if (player.playbackParameters != androidx.media3.common.PlaybackParameters.DEFAULT) player.playbackParameters = androidx.media3.common.PlaybackParameters.DEFAULT
            }

            override fun afterCasting() {
                val now = player.currentMediaItem?.toSong()
                if (now?.isEpisode == true && episodeSpeed != 1f) player.setPlaybackSpeed(episodeSpeed)
                else if (now != null && !now.isEpisode && !now.isLive && musicSpeed != 1f) player.setPlaybackSpeed(musicSpeed)
                applyPitch()
            }

            override fun remoteChanged() = sessionPlayer.remoteChanged()
        })
        restoreQueue()
        c.player.attach(this)
    }

    private lateinit var mix: MixEngine
    /** The equalizer in use now (as set, or the output's own preset). */
    private var eqNow = com.prism.music.data.prefs.EqSettings()

    /**
     * Whether Mix may blend these two: not an album running into its own next track (albums are made to flow on
     * as they are), not live streams or music videos, and not while playing along with others (Listen Together
     * keeps everyone on the same song).
     */
    private fun mayBlend(from: MediaItem, to: MediaItem): Boolean {
        val a = from.toSong() ?: return false
        val b = to.toSong() ?: return false
        if (a.isLive || b.isLive || from.videoModeId != null || to.videoModeId != null) return false
        // Talk doesn't blend.
        if (a.isEpisode || b.isEpisode) return false
        // Singing along: each song plays whole, with its own vocals taken out.
        if (c.sing.onNow) return false
        if (c.cast.casting) return false
        if (c.queue.followingHost || c.together.state.value !is com.prism.music.playback.together.TogetherState.Idle) return false
        // Only while an album plays through (a radio or playlist that puts two of an album's songs side by side blends).
        // An album played as a DJ set is a set: it blends.
        return blendsAcrossAlbum(a.album?.id, b.album?.id, c.queue.source.value.kind == QueueKind.ALBUM, c.settings.current.mixKeepAlbums, c.dj.onNow)
    }

    /**
     * The second player Mix plays a song's ending on while the next one fades in: the same sources, the same
     * effects (spatial audio, the ending song's normalization, and the equalizer through the shared audio
     * session), and no audio focus of its own.
     */
    private fun buildTailPlayer(shift: ShiftProcessor): ExoPlayer {
        val s = c.settings.current
        val tailSpatial = SpatialAudioProcessor().apply { configure(s.spatial, s.spatialAmount) }
        val tailNormalizer = NormalizerProcessor().apply { setGainDb(normalizeGainDb) }
        // The same headphone correction as the main player, so a blend doesn't change the sound.
        val tailEq = ParametricEqProcessor().apply { c.headphoneEq.active.value.let { configure(it?.profile, it?.strength ?: 1f) } }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput || s.lossless)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(shift, tailEq, tailSpatial, tailNormalizer))
                    .build()

            override fun buildAudioRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener, out: ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                FlacFloatGuardRenderer.install(out, context, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener)
            }
        }
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                PrismMediaSourceFactory(
                    c.playbackDataSourceFactory(), c.videoDataSourceFactory(),
                    localFiles(),
                    localAudio = { item -> losslessFileFor(item) },
                    liveDsf = c.liveDataSourceFactory(),
                    radioDsf = c.radioDataSourceFactory(),
                )
            )
            // The whole ending (a blend plus its lead-in is under 20 s) is loaded as soon as the tail is made, seconds before
            // it plays along: with only part of it loaded, a slow connection made the tail stop to load mid-blend.
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(60_000, 60_000, 1_500, 1_500).build())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            .build()
            .also {
                it.skipSilenceEnabled = false
                // The ending song in the key it was playing in (Sing): the blend used to drop back to the original key.
                pitchNow().takeIf { p -> p != 1f }?.let { p -> it.playbackParameters = androidx.media3.common.PlaybackParameters(1f, p) }
                // With the equalizer on, the tail gets its own audio session and its own copy of the equalizer (Reddit R3:
                // "crossfade breaks while the equalizer is on"). Two players on one session share one effect chain, which
                // the platform rebuilds as the tail's track comes and goes. Without the equalizer it shares the main
                // session, as before (other apps' effects such as Dolby follow that one).
                val eq = eqNow
                if (eq.enabled) {
                    val session = androidx.media3.common.util.Util.generateAudioSessionIdV21(this)
                    it.setAudioSessionId(session)
                    val tailEffects = AudioEffects(this, announce = false).apply { apply(eq); attach(session) }
                    it.addAnalyticsListener(object : AnalyticsListener {
                        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) = tailEffects.shutdown()
                    })
                    AudioDiagnostics.log("mix", "tail on its own session $session with the equalizer (${eq.preset})")
                } else it.setAudioSessionId(player.audioSessionId)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    /** Whether this service is in the foreground now (only known on Android 10+; below, it says yes). */
    private fun inForeground(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || foregroundServiceType != 0

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.i("PrismControl", "swiped away: wants to play ${player.playWhenReady}, ${player.mediaItemCount} songs, stop on swipe ${c.settings.current.stopOnSwipeAway}")
        saveQueue(now = true)
        // Settings → Playback → "Stop when swiped away" (#45): closing Prism from Recents ends the music too (some
        // phones, Samsung's among them, otherwise keep it playing with no app on screen).
        if (c.settings.current.stopOnSwipeAway) {
            flushPlayTime()
            saveEpisodePosition()
            pauseAllPlayersAndStopSelf()
            return
        }
        // Paused (#274): the notification goes too. stopSelf alone left it, and the service, while anything else was
        // still connected (the system's media controls, a watch, a widget), sometimes one that couldn't be swiped away.
        // The queue is saved above, and Play from anywhere picks it up again. Marked closed first: the service shows a
        // stopped player's notification otherwise (idle mode ALWAYS), until something plays again.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            flushPlayTime()
            saveEpisodePosition()
            android.util.Log.i("PrismControl", "swiped away while paused: stopping, notification closed until something plays")
            AudioDiagnostics.log("control", "swiped away while paused: Prism stops until something plays")
            setNotificationClosed(true)
            player.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (PlaybackServices.running == this::class.java) PlaybackServices.running = null
        PlaybackServices.failForTest = null
        PlaybackServices.taskRemovedForTest = null
        flushPlayTime()
        saveEpisodePosition()
        saveQueue(now = true)
        player.currentMediaItem?.toSong()?.let { song ->
            com.prism.music.widget.Widgets.update(this, song, false, song.id in c.library.likedIds.value, player.currentPosition, player.duration.takeIf { it > 0 })
        }
        mix.release()
        muteWatch.release()
        c.cast.detach(player)
        c.player.detach()
        carLyrics.stop()
        // Released on the effects thread, after anything still queued there: a stalled audio server can't hold this up.
        effects.shutdown()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Player events

    /** Id of the song being swapped in place (song ↔ video, audio ↔ live): the same listen goes on. */
    private var swappingInPlace: String? = null

    /** Replaces the item at [idx] with another form of the same song, without counting a new play. */
    private fun swapInPlace(idx: Int, item: MediaItem) {
        swappingInPlace = item.mediaId
        try { player.replaceMediaItem(idx, item) } finally { swappingInPlace = null }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Song ↔ video and the like swap the item but it's still the same listen: the play time
        // keeps adding up, and nothing that starts a new song (history, lyrics, prefetch) runs again.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem != null &&
            mediaItem.mediaId == swappingInPlace && mediaItem.mediaId == trackedSong?.id
        ) {
            applyLiveVideo()
            updateButtons()
            scheduleSave()
            return
        }
        // Next pressed (or a song picked) before this one had its say: a skip, for recommendations' sake.
        flushPlayTime(skipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        applyLiveVideo()
        val song = mediaItem?.toSong() ?: return
        // Disliked songs are passed over when the queue reaches them on its own (tapping one still plays it), and so are
        // artists the listener doesn't want played when a radio or autoplay brought them (a playlist they picked plays as it is).
        val picked = c.queue.source.value.kind == QueueKind.RADIO || player.currentMediaItemIndex >= c.queue.autoplayStart.value.let { if (it < 0) Int.MAX_VALUE else it }
        val unwanted = c.library.isDisliked(song.id) || (picked && c.exclusions.blocks(song))
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !c.queue.followingHost && unwanted && player.hasNextMediaItem()) {
            untrack()
            player.seekToNextMediaItem()
            return
        }
        // #216: with the explicit filter on, an explicit song that got into the queue anyway (a mix, the DJ, a Look's
        // shuffle) is passed over for the next one that may play; with none left, the music stops there.
        val explicitMode = c.settings.current.behavior.explicitContent
        if (!c.queue.followingHost && !com.prism.music.data.ExplicitFilter.plays(explicitMode, song)) {
            // Passed over unheard: the seek's own transition mustn't count the song that just finished a second time,
            // and with none left nothing is credited while the explicit song sits there ([onPlayWhenReadyChanged] keeps it paused).
            untrack()
            val tl = player.currentTimeline
            var i = tl.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            while (i != C.INDEX_UNSET) {
                val next = player.getMediaItemAt(i).toSong()
                if (next == null || com.prism.music.data.ExplicitFilter.plays(explicitMode, next)) { player.seekTo(i, 0); return }
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
            player.pause()
            c.player.reportError(tr(R.string.explicit_none_left))
            return
        }
        // Offline, songs that aren't on the phone are passed over too (#55): they'd play as far as any cached start
        // goes and then cut to the next song.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !song.isLive && !c.network.online.value && !c.availableOffline(song.id)) {
            // Straight to the next one that is (in play order), not just the next: that one may not be here either.
            val tl = player.currentTimeline
            var i = tl.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            while (i != C.INDEX_UNSET) {
                val next = player.getMediaItemAt(i)
                if (next.toSong()?.isLive == true || c.availableOffline(next.mediaId)) { untrack(); player.seekTo(i, 0); return }
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
        trackedSong = song
        trackedSource = sourceOf(mediaItem)
        trackedRecommended = picked || mediaItem.mediaId in c.queue.smartPicks.value ||
            c.queue.source.value.originId?.let { com.prism.music.data.foryou.ForYou.isMixId(it) } == true
        // Not while history is paused (#296): no "now playing" on Last.fm either.
        if (!com.prism.music.data.HistoryPause.paused(c.settings.current.behavior)) c.lastFm.started(song)
        trackedExcluded = c.exclusions.outOfTaste(song, c.queue.source.value.originId)
        // Podcast episodes pick up where they were left, and play at the speed picked for episodes.
        if (song.isEpisode) {
            val resume = c.episodes.positionOf(song.id)
            if (resume > 0 && player.currentPosition < 5_000) player.seekTo(resume)
            // (At 1× while casting: the speaker or TV plays at its own speed.)
            val speed = if (c.cast.casting) 1f else episodeSpeed
            if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
        } else {
            // Songs at the speed picked with the Speed button (1.7.4; 1× unless picked, always 1× live or casting).
            val speed = if (c.cast.casting || song.isLive) 1f else musicSpeed
            // Not mid-blend: the blend sets the tempo itself, and the picked speed comes back once it settles.
            if (player.playbackParameters.speed != speed && (lastWasEpisode || !mixing())) player.setPlaybackSpeed(speed)
            // Watching a podcast isn't a wish for music videos: music after it starts as the song.
            if (lastWasEpisode) videoWanted = false
        }
        lastWasEpisode = song.isEpisode
        applyPitch()
        applySkipSilence()
        // Already playing: a seamless move on (gapless, or a Mix blend); nothing to time.
        startedLoading = if (player.isPlaying) null else song.id to SystemClock.elapsedRealtime()
        retries.remove(song.id)
        // A queued song that's playing now isn't "next in queue" any more.
        if (song.id in c.queue.userQueued.value) c.queue.userQueued.value = c.queue.userQueued.value - song.id
        if (player.isPlaying) playStartedAt = SystemClock.elapsedRealtime()
        c.nerdStats.value = NerdStats(videoId = song.id, outputDevice = AudioEffects.outputDevice(this))
        c.streamDetails[song.id]?.let { applyStreamStats(song.id) }
        // Recently played (#296: not while history is paused).
        if (song.isRadio) c.radioStations.played(song.id.removePrefix(Song.RADIO_PREFIX))
        else if (!com.prism.music.data.HistoryPause.paused(c.settings.current.behavior)) scope.launch(Dispatchers.IO) { c.library.recordPlayed(song) }
        scope.launch { c.bestParts.onSongStarted(song) }
        singAhead()
        djAhead()
        smartAhead()
        loadExtras(song)
        // Audio only (the Song / Video switch is off): nothing plays as its video, whatever lined it up (#48).
        // Posted: swapping the item from inside this callback would report its transition after the swap was over.
        if (!c.settings.current.showVideoSwitch && mediaItem.videoModeId != null && !song.isLive) scope.launch {
            if (!c.settings.current.showVideoSwitch && player.currentMediaItem?.mediaId == song.id) setVideoMode(false)
        }
        // Every new song starts as the song; the track that just finished goes back to audio too,
        // so returning to it doesn't land in its video.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || !c.settings.current.showVideoSwitch) {
            for (i in 0 until player.mediaItemCount) {
                if (i == player.currentMediaItemIndex) continue
                val other = player.getMediaItemAt(i)
                if (other.videoModeId != null) other.toSong()?.let { player.replaceMediaItem(i, it.toMediaItem(null)) }
            }
        }
        continueVideo()
        maybeAutoplay()
        prefetchNext()
        songCoverForVideo(song)
        albumForSong(song)
        updateButtons()
        PlayTileService.update(this)
        carLyrics.refresh()
        scheduleSave()
    }

    /** Podcast episodes' playback speed (the player's ⋯ sets it); music always plays at 1×. */
    var episodeSpeed = 1f
        set(v) {
            field = v
            if (player.currentMediaItem?.toSong()?.isEpisode == true && !c.cast.casting) player.setPlaybackSpeed(v)
        }
    private var lastWasEpisode = false

    /**
     * Songs' playback speed (1.7.4, the Speed button the listener can put under the player); 1× unless they pick another.
     * A crossfade blends at the songs' own speed and settles back to 1×, after which [onPlaybackParametersChanged] puts
     * this speed back.
     */
    var musicSpeed = 1f
        set(v) {
            field = v
            val song = player.currentMediaItem?.toSong() ?: return
            if (!song.isEpisode && !song.isLive && !c.cast.casting) player.setPlaybackSpeed(v)
        }

    /** Songs' own pitch in semitones (1.7.8, R12; the Speed & pitch sheet), apart from Sing's key. */
    var musicPitch = 0
        set(v) { field = v; if (::player.isInitialized) applyPitch() }

    /** The pitch to play at now: Sing's key and the listener's own pitch, for songs (episodes, live and casting at 1). */
    private fun pitchNow(): Float {
        val song = player.currentMediaItem?.toSong()
        val own = if (song == null || song.isEpisode || song.isLive || c.cast.casting) 0 else musicPitch
        return singPitch() * Math.pow(2.0, own / 12.0).toFloat()
    }

    /** Puts [pitchNow] on the player (the speed stays); returns it. */
    private fun applyPitch(): Float {
        val pitch = pitchNow()
        if (player.playbackParameters.pitch != pitch) player.playbackParameters = androidx.media3.common.PlaybackParameters(player.playbackParameters.speed, pitch)
        return pitch
    }

    /**
     * Skip silence for what's playing: songs and podcast episodes each have their own switch (1.7.8, A4). Songs only
     * lose the silence at their start and end ([edges], #210); an episode's pauses are shortened too (Media3's skipper).
     */
    private fun applySkipSilence() {
        val s = c.settings.current
        edges.enabled = s.skipSilence
        // 1.8.0's one choice has no "songs only": songs trimmed reads as "Songs and podcasts" (SoundChoices), so it is.
        val on = player.currentMediaItem?.toSong()?.isEpisode == true && (s.sound.skipSilenceEpisodes || s.skipSilence)
        if (player.skipSilenceEnabled != on) player.skipSilenceEnabled = on
    }

    private var fadeJob: Job? = null

    /**
     * "Fade on pause and play" (1.7.8, A5): a pause fades the sound out over a quarter of a second first, a play starts
     * silent and comes up. Through the player's own volume (heard at once). Not mid-crossfade (the blend owns the volume)
     * or while casting. Returns false when the change should simply be made.
     */
    internal fun gentle(play: Boolean): Boolean {
        if (!c.settings.current.sound.pauseFade || mixing() || c.cast.casting) return false
        if (play == player.playWhenReady && fadeJob?.isActive != true) return false
        fadeJob?.cancel()
        fadeJob = scope.launch {
            if (!play) {
                val from = player.volume
                for (i in 1..PAUSE_FADE_STEPS) {
                    player.volume = from * (1f - i.toFloat() / PAUSE_FADE_STEPS).let { it * it }
                    delay(PAUSE_FADE_STEP_MS)
                }
                player.pause()
                player.volume = 1f
            } else {
                val from = if (player.playWhenReady) player.volume else 0f
                player.volume = from
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                for (i in 1..PAUSE_FADE_STEPS) {
                    delay(PAUSE_FADE_STEP_MS)
                    player.volume = from + (1f - from) * (i.toFloat() / PAUSE_FADE_STEPS).let { it * it }
                }
                player.volume = 1f
            }
        }
        return true
    }

    private fun mixing() = ::mix.isInitialized && mix.blending

    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
        if (playbackParameters.speed == 1f) restoreMusicSpeed()
    }

    /**
     * The picked speed back on a song, once nothing else is steering it: after a blend, which ends at 1× either by
     * settling (still marked as blending while it sets 1×) or without changing the speed at all.
     */
    private fun restoreMusicSpeed() {
        if (musicSpeed == 1f || mixing() || player.playbackParameters.speed == musicSpeed) return
        val song = player.currentMediaItem?.toSong() ?: return
        if (!song.isEpisode && !song.isLive && !c.cast.casting) player.setPlaybackSpeed(musicSpeed)
    }

    /** Remembers how far into the playing episode the listener is (see [com.prism.music.data.EpisodeProgress]). */
    private fun saveEpisodePosition() {
        val song = player.currentMediaItem?.toSong()?.takeIf { it.isEpisode } ?: return
        c.episodes.save(song, player.currentPosition, player.duration.takeIf { it > 0 } ?: song.durationSec * 1000L)
    }

    /** The song that just came up and when, until it's heard: how long starting a song takes (logcat tag PrismStream). */
    private var startedLoading: Pair<String, Long>? = null

    /** Whether the listener swiped the notification away and nothing has played since. */
    private fun notificationClosed() = getSharedPreferences("playback_service", MODE_PRIVATE).getBoolean("notification_closed", false)

    private fun setNotificationClosed(closed: Boolean) {
        if (notificationClosed() == closed) return
        getSharedPreferences("playback_service", MODE_PRIVATE).edit().putBoolean("notification_closed", closed).apply()
        setShowNotificationForIdlePlayer(
            if (closed) MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER
            else MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS,
        )
    }

    /**
     * The paused song's notification was swiped away: that's the listener closing Prism. Where they were is saved, the
     * notification stays away until something plays, and the service stops (it had stayed in the foreground, listed in
     * Android's "background activity" until stopped there).
     */
    private fun closedFromNotification() {
        if (player.isPlaying) return
        android.util.Log.i("PrismControl", "the notification was swiped away: stopping until something plays")
        AudioDiagnostics.log("control", "the notification was swiped away: Prism stops until something plays")
        setNotificationClosed(true)
        flushPlayTime()
        saveEpisodePosition()
        saveQueue(now = true)
        pauseAllPlayersAndStopSelf()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) setNotificationClosed(false)
        PlayTileService.update(this)
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) startedLoading?.let { (id, at) ->
            if (id == player.currentMediaItem?.mediaId) android.util.Log.i("PrismStream", "$id playing ${now - at} ms after it came up (${c.streamDetails[id]?.first ?: "?"})")
            startedLoading = null
        }
        if (!isPlaying) saveEpisodePosition()
        pushWidgets()
        if (isPlaying) playStartedAt = now
        else if (playStartedAt > 0) {
            accumulatedMs += now - playStartedAt
            playStartedAt = 0
            scheduleSave()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // #216: the explicit song left current when none that may play came after it stays paused, whatever presses Play.
        if (!playWhenReady || c.queue.followingHost) return
        val song = player.currentMediaItem?.toSong() ?: return
        if (!com.prism.music.data.ExplicitFilter.plays(c.settings.current.behavior.explicitContent, song)) {
            player.pause()
            c.player.reportError(tr(R.string.explicit_none_left))
        }
    }

    /** The song [prefetchNext] last got ready. */
    private var warmedNext: String? = null

    /**
     * The queue changed under the current song (autoplay's suggestions landed, something was queued or moved):
     * if a different song is next now, get that one ready instead. Playing a single song used to leave its first
     * radio song cold, since the suggestions arrive after the song has started.
     */
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        // The processors that follow songs across gapless moves count by the timeline as it is now (a queue change
        // doesn't flush them), and learn a song's length once it's known.
        edges.timelineChanged(timeline)
        vocals.timelineChanged(timeline)
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        // A spoken request's songs are in: they play (the phone's VoicePlayActivity leaves that to here, so a request
        // that found nothing doesn't resume the old queue).
        if (voiceAnsweredAt > 0 && SystemClock.elapsedRealtime() - voiceAnsweredAt < VOICE_START_MS) {
            voiceAnsweredAt = 0
            scope.launch { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
        }
        // What's lined up changed (songs added, moved, removed, the queue emptied): kept for the next start. It was only
        // saved on a song change, play / pause, shuffle or repeat, so an emptied or edited queue could come back as it was.
        scheduleSave()
        val i = player.nextMediaItemIndex
        val next = if (i == C.INDEX_UNSET) null else player.getMediaItemAt(i).mediaId
        if (next != null && next != warmedNext && player.currentMediaItem != null) prefetchNext()
        // Songs added while DJ mode is on (autoplay's, queued ones) join the set; DJ mode's own moves don't count.
        if (c.dj.onNow && !djMoving) {
            djSoon?.cancel()
            djSoon = scope.launch { delay(1_500); djAhead() }
        }
    }

    /** When a spoken request last found something to play (elapsed realtime), till its songs are in; 0: none waiting. */
    private var voiceAnsweredAt = 0L

    /** When playback stalled waiting for data (logged with how long, tag PrismStream). */
    private var stalledAt = 0L

    override fun onPlaybackStateChanged(playbackState: Int) {
        val now = SystemClock.elapsedRealtime()
        if (playbackState == Player.STATE_BUFFERING && player.playWhenReady && startedLoading == null) stalledAt = now
        else if (playbackState == Player.STATE_READY && stalledAt > 0) {
            android.util.Log.i("PrismStream", "${player.currentMediaItem?.mediaId} stalled ${now - stalledAt} ms at ${player.currentPosition} ms")
            stalledAt = 0
        }
        if (playbackState == Player.STATE_READY) checkLength()
    }

    /** The song whose length was last checked against what's playing ([checkLength]). */
    private var lengthChecked: String? = null

    /**
     * Notes in diagnostics when what plays runs much longer or shorter than the song (Reddit, 1.7.9: an album song
     * played as someone's guitar cover). The release check's night hunt looks for these lines; nothing else changes.
     */
    private fun checkLength() {
        val item = player.currentMediaItem ?: return
        if (item.mediaId == lengthChecked) return
        val song = item.toSong() ?: return
        val playing = player.duration
        if (playing <= 0 || song.durationSec <= 0 || song.isLive || song.isEpisode || song.isLocal) return
        lengthChecked = item.mediaId
        val diff = playing / 1000 - song.durationSec
        if (kotlin.math.abs(diff) > LENGTH_MISMATCH_SEC) AudioDiagnostics.log(
            "song",
            "length mismatch: ${song.id} \"${song.title}\" is ${song.durationSec} s but ${playing / 1000} s plays" +
                (c.versions.of(song.id)?.let { " (picked version $it)" } ?: c.soundFrom[song.id]?.let { " (from its video $it)" } ?: "") +
                (if (item.videoModeId != null) " (video mode)" else ""),
        )
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        vocals.shuffle = shuffleModeEnabled
        edges.shuffle = shuffleModeEnabled
        // Shuffle and DJ mode both decide the order: the listener's shuffle wins.
        if (shuffleModeEnabled && c.dj.onNow) c.dj.setOn(false)
        // Shuffle off (from anywhere: the notification, the car) is Smart Shuffle off too.
        if (!shuffleModeEnabled && c.queue.smartShuffle.value) c.queue.smartShuffle.value = false
        updateButtons()
        // Shuffling picks a different next song.
        prefetchNext()
        scheduleSave()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        vocals.repeatMode = repeatMode
        edges.repeatMode = repeatMode
        updateButtons()
        scheduleSave()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        effects.attach(audioSessionId)
    }

    override fun onPlayerError(error: PlaybackException) {
        val id = player.currentMediaItem?.mediaId ?: return
        // A radio station (Prism Car): a dropped stream is picked up again, twice; one that won't play is passed over.
        // None of a song's remedies (fresh YouTube links, its cache or download) apply to it.
        if (id.startsWith(Song.RADIO_PREFIX)) {
            val tries = retries[id] ?: 0
            if (tries < 2 && error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
                retries[id] = tries + 1
                AudioDiagnostics.log("radio", "$id: the stream dropped (${error.errorCodeName}); connecting again")
                scope.launch {
                    delay(1_000)
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                }
                return
            }
            AudioDiagnostics.log("radio", "$id won't play (${error.errorCodeName})")
            failAndSkip(error)
            return
        }
        // An Apple Lossless file this phone has no decoder for (#135): the lossless add-on's file is set aside and the
        // song's stream plays; a file of your own says so and stays put rather than being skipped.
        val format = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.takeIf { it.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
        if (format?.sampleMimeType?.contains("alac", true) == true) {
            val idx = player.currentMediaItemIndex
            if (id.startsWith(Song.LOCAL_PREFIX)) {
                AudioDiagnostics.log("error", "$id is ALAC and this phone can't decode it (${error.errorCodeName})")
                c.player.reportError(tr(R.string.this_phone_can_t_play_apple))
                return
            }
            // Only once per file: one that can't be told apart (or was already set aside) fails as usual, never loops.
            val file = c.localLossless.matched[id]?.uri
            if (file == null || !c.localLossless.unplayable.add(file)) { failAndSkip(error); return }
            AudioDiagnostics.log("error", "$id's ALAC file won't decode here: its stream plays instead")
            player.seekToDefaultPosition(idx)
            player.prepare()
            player.play()
            return
        }
        // It turned out to be a live stream (a "Listen together" station): play it again as one, from its HLS manifest.
        val live = generateSequence(error as Throwable) { it.cause }.filterIsInstance<com.prism.music.data.stream.LiveStreamException>().firstOrNull()
        if (live != null) {
            val idx = player.currentMediaItemIndex
            val song = player.currentMediaItem?.toSong()
            if (song != null && !song.isLive) {
                swapInPlace(idx, song.copy(isLive = true).toMediaItem())
                applyLiveVideo()
                player.seekToDefaultPosition(idx)
                player.prepare()
                player.play()
                return
            }
        }
        val httpCode = generateSequence(error as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        // A music video that won't load gets one more go with its streams asked for afresh (YouTube now and then turns
        // a direct link away, and the next answer comes the other way, through NewPipe); after that it falls back to
        // the song itself — never skip ahead.
        player.currentMediaItem?.videoModeId?.let { videoId ->
            val parse = generateSequence(error as Throwable) { it.cause }.any { it is androidx.media3.common.ParserException }
            // A dropped connection or a timeout (a long seek on a slow TV, a fast skip) is worth one more go too. One
            // go a minute per video: it used to be one per session, so a second refused link hours later (a seek far
            // into the video) sent it back to the song for good.
            val network = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            val at = SystemClock.elapsedRealtime()
            if (videoRetryAllowed(videoRetriedAt[videoId], at) && (httpCode == 403 || httpCode == 410 || parse || network)) {
                videoRetriedAt[videoId] = at
                AudioDiagnostics.log("video", "video $videoId failed (${httpCode ?: if (parse) "unreadable" else error.errorCodeName}): trying its streams afresh")
                c.streams.invalidate(videoId, refused = true)
                val pos = player.currentPosition
                player.prepare()
                player.seekTo(pos)
                player.play()
                return
            }
            scope.launch { revertVideo(id) }
            return
        }
        val attempts = retries[id] ?: 0
        // A download whose file can't be read (#183: "Skipping atom with length > 2147483647", stitched from two formats
        // by a download that resumed in another one): it's fetched again, and the song streams meanwhile.
        // Also the reader tripping over the bytes (1.7.9's check, "Woke Up Older": a cached part that mixed two versions
        // of the song threw ArrayIndexOutOfBounds in the loader, "Unexpected exception loading stream"): every tap after
        // read the same part and failed, since only a ParserException let it go.
        val unreadable = generateSequence(error as Throwable) { it.cause }.any {
            it is androidx.media3.common.ParserException || it is androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException
        }
        if (unreadable && retries["file:$id"] == null && c.downloads.redownload(id)) {
            retries["file:$id"] = 1
            AudioDiagnostics.log("song", "$id: its download can't be read (${(error.cause ?: error).message?.take(80)}); downloading it again and streaming it now")
            val pos = player.currentPosition
            scope.launch {
                // The download's removal lands on the download manager's thread first, so the player reads from the
                // network. A fixed 0.6 s wasn't always enough (the owner's phone, 1.7.9's check): the broken file was
                // read again and the song skipped.
                withTimeoutOrNull(8_000) { while (withContext(Dispatchers.IO) { c.downloadedWhole(id) }) delay(100) }
                player.prepare()
                player.seekTo(pos)
                player.play()
            }
            return
        }
        // A streamed song whose bytes can't be read (Reddit, 1.7.7: "Element 134 must be in a TrackEntry", one song every
        // time): its cached part came from another format than today's stream, and the two were joined. Its cached bytes
        // go and it's asked for afresh, once.
        if (unreadable && retries["cache:$id"] == null && !id.startsWith(Song.LOCAL_PREFIX)) {
            retries["cache:$id"] = 1
            AudioDiagnostics.log("song", "$id: its cached part can't be read (${(error.cause ?: error).message?.take(80)}); playing it afresh")
            val pos = player.currentPosition
            scope.launch {
                withContext(Dispatchers.IO) { c.forgetCachedAudio(id) }
                c.streams.invalidate(id)
                player.prepare()
                player.seekTo(pos)
                player.play()
            }
            return
        }
        // The phone took the audio decoder away mid-song (1.7.9's night check: screen off, Prism in the background,
        // "releaseOutputBuffer() is valid only at Executing states; currently at Released state" at 0:58.9, and the
        // song was skipped). The song itself is fine: it plays on from where it stopped with a new decoder, twice at most.
        val codecKey = "codec:$id"
        val codecTries = retries[codecKey] ?: 0
        if (decoderLost(error) && codecTries < 2) {
            retries[codecKey] = codecTries + 1
            AudioDiagnostics.log("song", "$id: the phone released its decoder (${error.errorCodeName}); playing on from ${player.currentPosition / 1000} s")
            val pos = player.currentPosition
            scope.launch {
                delay(300)
                player.prepare()
                player.seekTo(pos)
                player.play()
            }
            return
        }
        if (attempts < 2 && (httpCode == 403 || httpCode == 410 || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        ) {
            retries[id] = attempts + 1
            c.streams.invalidate(id, refused = httpCode == 403)
            val pos = player.currentPosition
            player.prepare()
            player.seekTo(pos)
            player.play()
            return
        }
        // #93: a song YouTube won't play where the listener is, though its music video plays there: the video's sound
        // takes its place (the song stays the song: its lyrics, likes and history are its own).
        val song = player.currentMediaItem?.toSong()
        // Reddit R2: also a song whose stream YouTube keeps refusing ("BUZZ" skipped while its video played), since the
        // video has to be the song's length (below), so it's the same recording.
        if (song != null && !song.isVideo && !song.isLocal && !song.isLive && !song.isEpisode && !c.soundFrom.containsKey(id) && wontPlayHere(error, refused = httpCode == 403)) {
            val idx = player.currentMediaItemIndex
            scope.launch {
                val video = videoIdFor(song).id?.takeIf { it != id }
                // Only a video of the same length: one with a long intro or a second song in it (Reddit R1, "Death &
                // Romance" as its 8-minute video) would play something else under this song's title and lyrics.
                val videoSec = video?.let { v -> withContext(Dispatchers.IO) { runCatching { c.streams.durationSec(v) }.getOrDefault(0L) } } ?: 0L
                // The video must resolve too (offline or blocked, it wouldn't play either, and it'd stay swapped in).
                val fits = videoSec > 0 && (song.durationSec <= 0 || kotlin.math.abs(videoSec - song.durationSec) <= SOUND_FROM_SLACK_SEC)
                if (player.currentMediaItemIndex != idx || player.currentMediaItem?.mediaId != id) return@launch
                if (video != null && !fits) AudioDiagnostics.log("song", "$id isn't available here; its music video $video runs ${videoSec}s, not ${song.durationSec}s: not played in its place")
                if (video == null || !fits) { failAndSkip(error); return@launch }
                c.soundFrom[id] = video
                val here = unavailableHere(error)
                AudioDiagnostics.log("song", "$id ${if (here) "isn't available here" else "won't play (${(error.cause ?: error).message})"}: playing its music video $video's sound")
                c.player.reportError(tr(R.string.so_its_music_video_s_sound, song.title, if (here) tr(R.string.isn_t_available_where_you_are) else tr(R.string.wouldn_t_play)))
                player.prepare()
                player.play()
            }
            return
        }
        failAndSkip(error)
    }

    /** Says why a song didn't play, and moves on to the next one. */
    private fun failAndSkip(error: PlaybackException) {
        c.player.reportError((error.cause ?: error).friendlyMessage(tr(R.string.playback_failed)))
        if (player.hasNextMediaItem()) {
            scope.launch {
                delay(1500)
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    // ------------------------------------------------------------ Features

    /**
     * Normalization gain for the current song: YouTube's loudness figure says how far the track
     * sits from its reference level, so loud songs come down and quiet ones go up (as far as their
     * peaks allow, [NormalizerProcessor.gainFor]; the limiter keeps those from clipping).
     */
    /** The normalization gain now applied (the ending song's, when a Mix blend's tail takes it over). */
    private var normalizeGainDb = 0f

    /**
     * The playing song is still just starting (loading, or in its first [SETTLE_MS] of sound): a better loudness figure
     * may still set its volume. After that the volume stays put for the rest of the song.
     */
    private fun settling() = player.currentPosition < SETTLE_MS

    /**
     * "Albums as they are" (A2): while an album plays through, every song gets the same gain, set by the loudest of its
     * songs measured so far, so a quiet interlude stays quiet next to the single. Null otherwise.
     */
    private fun albumLoudness(): Pair<Float, Float?>? {
        if (!c.settings.current.sound.normalizeAlbums || c.queue.source.value.kind != QueueKind.ALBUM) return null
        val album = trackedSong?.album?.id ?: return null
        val songs = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }.filter { it.toSong()?.album?.id == album }
            .filter { c.mixTempo.loudness(it.mediaId) != null }
        val loudest = songs.maxOfOrNull { c.mixTempo.loudness(it.mediaId)!! } ?: return null
        // The album's highest peak bounds how far all of it can be lifted (unknown while any song's is).
        val peaks = songs.map { c.mixTempo.peak(it.mediaId) }
        return loudest to (if (null in peaks) null else peaks.maxOf { it!! })
    }

    private fun applyNormalization() {
        // The song's own measured loudness (integrated LUFS, from [MixTempo]'s scan) is the real figure. YouTube's
        // loudnessDb hardly differs between a loud master and a quiet one, so it only stands in until the song is
        // measured.
        val measured = trackedSong?.id?.let { c.mixTempo.loudness(it) }
        val yt = songLoudness
        // The level picked (A2; -14 LUFS unless changed), and with "Albums as they are" one level for a whole album.
        val target = c.settings.current.sound.normalizeTarget.toFloat()
        val album = albumLoudness()
        val gain = when {
            !c.settings.current.normalize -> 0f
            album != null -> NormalizerProcessor.gainFor(target, album.first, album.second)
            measured != null -> NormalizerProcessor.gainFor(target, measured, trackedSong?.id?.let { c.mixTempo.peak(it) })
            yt != null -> NormalizerProcessor.gainFor(target, TARGET_LUFS + yt)
            else -> 0f
        }
        // Once the song is under way, a better figure eases in over two seconds, and a small one isn't worth a change
        // heard mid-song; before its sound starts, the right level is set at once.
        val underWay = player.isPlaying && player.currentPosition > 400
        if (underWay && kotlin.math.abs(gain - normalizeGainDb) < MIN_MIDSONG_CHANGE_DB) return
        normalizer.setGainDb(gain, if (underWay) 2f else 0.1f)
        if (gain != normalizeGainDb) AudioDiagnostics.log("normalize", "gain %.1f dB (%s) at %d s".format(gain, if (measured != null) "measured" else if (yt != null) "YouTube's figure" else "none", player.currentPosition / 1000))
        normalizeGainDb = gain
        c.nerdStats.value = c.nerdStats.value.copy(
            loudnessDb = (measured ?: yt)?.toDouble(), loudnessMeasured = measured != null, normalizationGainDb = gain,
        )
        android.util.Log.i(
            "PrismNormalize",
            (if (measured != null) "measured %.1f LUFS".format(measured) else "YouTube's figure ${yt ?: "unknown"} dB") +
                " -> gain %.1f dB (normalize ${c.settings.current.normalize})".format(gain),
        )
    }

    /**
     * Scans the playing and next songs ([MixTempo]): how their music starts and ends and their beat, for Crossfade, and
     * their loudness, for normalization. From the phone when the song's there (downloaded, cached, a local file),
     * else a small download, on Wi-Fi unless mobile data is allowed. Each song is scanned once.
     *
     * Not until the playing song is under way: a scan fetches a whole song (and asks YouTube for its streams) and
     * decodes it, and run as a song changed it held up that song's own start, which buffered for longer.
     */
    private fun scanAhead(vararg items: MediaItem?) {
        val s = c.settings.current
        // The waveform progress bar is drawn from the scan too (1.7.4).
        val wave = s.scrubberStyle == com.prism.music.data.prefs.ScrubberStyle.WAVE
        if (!s.mix && !s.normalize && !wave) return
        val network = s.mixTempoOnCellular || getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
        val songs = items.mapNotNull { it?.toSong() }.filter { !it.isLive && !it.isEpisode }.distinctBy { it.id }
        if (songs.isEmpty()) return
        // Songs scanned before fade-outs and intros were measured are scanned again only for Crossfade's automatic length.
        val edges = s.mix && s.mixAuto
        val playing = player.currentMediaItem?.mediaId
        scanJob?.cancel()
        scanJob = scope.launch {
            if (playing != null) untilUnderWay(playing, UNDER_WAY_MS)
            // YouTube's loudness figure for each queued song not known yet: quick, and enough to start it at the right
            // volume if it comes up before it's been measured (a skip).
            if (s.normalize) for (song in songs) {
                if (song.id == playing || song.isLocal || c.loudness.contains(song.id) || c.mixTempo.loudness(song.id) != null) continue
                withContext(Dispatchers.IO) { runCatching { c.ytm.playerExtras(song.id, c.streams.signatureTimestamp()).loudnessDb } }
                    .getOrNull()?.let { c.loudness.edit().putFloat(song.id, it.toFloat()).apply() }
            }
            // Then measured, the queued songs first (their volume is set as they start); the playing one last (only
            // Crossfade needs it, near its end).
            // With the waveform bar, the playing song goes first: its bar is waiting for it.
            for (song in songs.sortedBy { if (wave) it.id != playing else it.id == playing }) {
                c.mixTempo.measure(song.id, network, edges, wave = wave, peak = s.normalize)
                // Measured while it's just begun: its own figure takes over (later in the song, a jump would be heard).
                if (trackedSong?.id == song.id && settling()) applyNormalization()
            }
        }
    }

    private fun loadExtras(song: Song) {
        extrasJob?.cancel()
        // Remembered loudness applies straight away (and offline); the network refreshes it.
        songLoudness = c.loudness.getFloat(song.id, Float.NaN).takeIf { !it.isNaN() }
        applyNormalization()
        // A file on the phone has nothing on YouTube to fetch or report.
        if (song.isLocal) return
        extrasJob = scope.launch {
            val ex = runCatching { withContext(Dispatchers.IO) { c.ytm.playerExtras(song.id, c.streams.signatureTimestamp()) } }.getOrNull()
            ex?.loudnessDb?.toFloat()?.let { loud ->
                c.loudness.edit().putFloat(song.id, loud).apply()
                // Only while the song is just starting: later, the volume jumping mid-song is worse than a figure
                // that's a little off (it's kept, so the song plays at it from the start next time).
                if (trackedSong?.id == song.id && settling()) { songLoudness = loud; applyNormalization() }
            }
            if (ex != null && c.settings.current.isLoggedIn) {
                // #263: with "Same for your YouTube Music history" on, the account hears of the play once it counts here
                // too (this job is cancelled by the next song, so a song skipped first isn't sent). Off: at once, as before.
                val after = com.prism.music.data.PlayCount.accountAfterMs(c.settings.current.behavior)
                if (after > 0) while (trackedSong?.id == song.id && heardMs() < after) delay(500)
                // #296: nothing goes to the account's history while history is paused.
                if (trackedSong?.id == song.id && !com.prism.music.data.HistoryPause.paused(c.settings.current.behavior)) {
                    withContext(Dispatchers.IO) {
                        val sent = runCatching { c.ytm.registerPlayback(ex) }.getOrDefault(false)
                        // YouTube Music (live) then knows this one at the top of the account's history as Prism's own
                        // (only when YouTube took it: else a play elsewhere would be taken for Prism's).
                        if (sent) runCatching { com.prism.music.data.imports.YouTubeLive.noteSent(c.app, song.id, com.prism.music.data.imports.YouTubeLive.keyOf(song)) }
                    }
                }
            }
        }
    }

    /**
     * With Autoplay on, keeps the queue topped up with YouTube Music radio
     * suggestions as it nears its end: seeded by the song for a single-song
     * play, or by the last track of an album / playlist.
     */
    fun maybeAutoplay() {
        val src = c.queue.source.value
        // A song's radio (Quick picks, "Radio" in a song's menu) keeps going whether or not Autoplay is on: that's what it is.
        val radio = src.isSongRadio
        if ((!c.settings.current.autoplay && !radio) || player.mediaItemCount == 0 || c.queue.followingHost) return
        // No music radio after a podcast episode, nor after a radio station (Prism Car).
        if (player.currentMediaItem?.toSong()?.let { it.isEpisode || it.isRadio } == true) return
        if (src.kind != QueueKind.SINGLE && !radio && player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.mediaItemCount - player.currentMediaItemIndex > 3 || c.queue.autoplayLoading) return
        val seed = src.seedId ?: player.getMediaItemAt(player.mediaItemCount - 1).mediaId.takeIf { it.isNotBlank() } ?: return
        // Where the songs come from (Settings → Playback & sound → Autoplay from): your own music (Prism's radio, made on
        // the phone from your plays, likes, downloads and files), YouTube Music's radio, or both. A song's radio (Quick
        // picks, "Radio") is YouTube's; with no connection, or after a file on the phone, only your own music can follow.
        val offline = !c.network.isOnline()
        val mode = c.settings.current.autoplaySource
        val seedLocal = seed.startsWith(Song.LOCAL_PREFIX)
        val useYours = offline || seedLocal || (!radio && mode != com.prism.music.data.prefs.AutoplaySource.YOUTUBE)
        val useYouTube = !offline && !seedLocal && (radio || mode != com.prism.music.data.prefs.AutoplaySource.YOURS)
        val seeds = (maxOf(0, player.mediaItemCount - 3) until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.filter { it.isNotBlank() }
        c.queue.autoplayLoading = true
        scope.launch {
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val mine = if (!useYours) emptyList() else runCatching {
                withContext(Dispatchers.IO) { c.yourMusic.radio(seeds, existing, 15, offlineOnly = offline) }
            }.onFailure { android.util.Log.w("PrismRadio", "Prism radio failed", it) }.getOrNull().orEmpty()
                .let { com.prism.music.data.foryou.LocalRadio.besideYouTube(it, onlyYours = !useYouTube || mode == com.prism.music.data.prefs.AutoplaySource.YOURS) }
                .map { it.song }
            // "Your music": YouTube only fills in when your own music has too little to follow with.
            val wantYouTube = useYouTube && (radio || mode != com.prism.music.data.prefs.AutoplaySource.YOURS || mine.size < 5)
            val r = if (!wantYouTube) null else runCatching {
                withContext(Dispatchers.IO) { c.radio(seed, "RDAMVM$seed", continuation = c.queue.autoplayContinuation) }
            }.getOrNull()
            c.queue.autoplayLoading = false
            // Dropped if the queue was replaced, or Autoplay was switched off, while loading.
            if ((r == null && mine.isEmpty()) || c.queue.source.value !== src || (!c.settings.current.autoplay && !radio)) return@launch
            if (r != null) c.queue.autoplayContinuation = r.continuation
            // Not what was heard in the last few hours, nor what the listener turned down (skipped at once when a
            // suggestion): YouTube's radio for a favourite brings the same songs back day after day otherwise.
            val heardLately = withContext(Dispatchers.IO) {
                runCatching { c.db.plays().eventsSince(System.currentTimeMillis() - RADIO_REST_MS).map { it.songId }.toSet() }.getOrDefault(emptySet())
            }
            val fb = c.recFeedback
            val theirs = r?.songs.orEmpty().filter { it.id !in heardLately && !fb.turnedDown(it.id, known = false) }
            val freshShare = when {
                radio || mine.isEmpty() -> 1.0
                theirs.isEmpty() -> 0.0
                // Both: YouTube's radio leads, your own songs that belong with it in between (about two in five).
                else -> 0.6
            }
            // An artist a few times a batch at most, never twice running.
            val songs = com.prism.music.data.foryou.Variety.arrange(mine, theirs, 25, freshShare, perArtist = 3) { it.primaryArtist.lowercase() }
            android.util.Log.i("PrismRadio", "autoplay after $seed: ${mine.size} of yours, ${theirs.size} from YouTube ($mode${if (offline) ", offline" else ""})")
            val explicit = c.settings.current.behavior.explicitContent
            val add = songs.distinctBy { it.id }.filter {
                it.id !in existing && !c.library.isDisliked(it.id) && !c.exclusions.blocks(it) && com.prism.music.data.ExplicitFilter.plays(explicit, it)
            }
            if (add.isNotEmpty()) {
                if (c.queue.autoplayStart.value < 0) c.queue.autoplayStart.value = player.mediaItemCount
                player.addMediaItems(add.map { it.toMediaItem() })
                fb.served(add.map { it.id })
            }
        }
    }

    /** Switching Autoplay off takes its suggestions back out of the queue; on, fetches them. */
    private fun onAutoplayChanged(enabled: Boolean) {
        if (enabled) { maybeAutoplay(); return }
        if (c.queue.source.value.isSongRadio) return
        val start = c.queue.autoplayStart.value
        if (start >= 0 && start < player.mediaItemCount) {
            val from = maxOf(start, player.currentMediaItemIndex + 1)
            if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
        }
        c.queue.autoplayStart.value = -1
        c.queue.autoplayContinuation = null
    }

    /**
     * The song's music video id. Null with [lookupFailed] = true means YouTube
     * couldn't be reached (worth trying again); otherwise there simply isn't one.
     */
    suspend fun videoIdFor(song: Song): VideoLookup {
        // An episode is a YouTube video already: its video is itself.
        if (song.isVideo || song.isEpisode) return VideoLookup(song.id)
        // A file on the phone has no music video, and neither has the listener's own upload (#101: the lookup found the
        // official recording's video for it by title).
        if (song.isLocal || song.isUpload) return VideoLookup(null)
        // A saved video plays offline, so it's known without asking YouTube.
        c.downloads.downloadedVideoId(song.id)?.let { return VideoLookup(it) }
        c.queue.counterparts[song.id]?.let { return VideoLookup(it) }
        if (song.id in c.queue.noVideo) return VideoLookup(null)
        val found = runCatching { withContext(Dispatchers.IO) { c.ytm.findMusicVideo(song) } }
        found.getOrNull()?.let { c.queue.counterparts[song.id] = it; return VideoLookup(it) }
        found.exceptionOrNull()?.let { android.util.Log.w("PrismVideo", "video lookup for ${song.id} failed: $it") }
        if (found.isSuccess) c.queue.noVideo.add(song.id)
        return VideoLookup(null, lookupFailed = found.isFailure)
    }

    /**
     * Gets a song's video ready before it's asked for: the video's streams are
     * resolved (the slow part of switching), and on Wi-Fi the lyric/position
     * offset is measured, so the video button switches straight away.
     */
    fun prefetchVideo(song: Song, videoId: String) {
        if (song.isLocal || videoId in prefetched) return
        prefetched += videoId
        scope.launch(Dispatchers.IO) {
            // Not alongside the song's own start: it was one of the resolves slowing that down.
            c.yieldToPlaying(null)
            runCatching { c.streams.resolve(videoId, withVideo = true) }.onFailure { prefetched -= videoId }
            val metered = getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
            if (metered || videoId == song.id || c.videoSync.cached(song.id, videoId) != null) return@launch
            // Measured once the song is under way: a long video ("Alright" runs three minutes past its song) means
            // minutes of audio to fetch and analyse, which slowed the song's own start when it ran at once.
            withContext(Dispatchers.Main) { untilUnderWay(song.id) }
            runCatching { c.videoSync.lagMs(song.id, videoId) }
        }
    }

    /** Returns once [songId] has played [afterMs] (~10 s; or something else is playing: it was lined up as the next song). */
    private suspend fun untilUnderWay(songId: String, afterMs: Long = 10_000) {
        delay(minOf(3_000, afterMs))
        repeat(60) {
            if (player.currentMediaItem?.mediaId != songId || (player.isPlaying && player.currentPosition > afterMs)) return
            delay(1_000)
        }
    }

    /** The next [n] songs in the order they'll play (shuffled or not). */
    private fun upcoming(n: Int): List<MediaItem> {
        val tl = player.currentTimeline
        val out = ArrayList<MediaItem>(n)
        // An empty queue has no next song (an empty timeline still answers index + 1).
        if (tl.isEmpty) return out
        var i = player.currentMediaItemIndex
        while (out.size < n) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i >= tl.windowCount) break
            out += player.getMediaItemAt(i)
        }
        return out
    }

    /** The queued songs' scans and loudness figures, under way (a newer queue replaces it). */
    private var scanJob: kotlinx.coroutines.Job? = null
    /** Whole queued songs being loaded ahead (Settings → Load ahead; a newer queue replaces it). */
    private var preloadJob: kotlinx.coroutines.Job? = null

    /**
     * Resolves the next song's stream while this one plays (the slow part of starting a song), so
     * skipping ahead or moving on starts straight away. Downloaded songs don't need it.
     */
    private fun prefetchNext() {
        val i = player.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        songForNextVideo(player.getMediaItemAt(i))
        val next = player.getMediaItemAt(i).mediaId.takeIf { it.isNotBlank() } ?: return
        warmedNext = next
        // The next few queued songs' loudness (for normalization) and edges (for Crossfade), ready well before they
        // play, so each starts at its own volume from its first second. Once this song is under way ([scanAhead]).
        scanAhead(player.currentMediaItem, *upcoming(QUEUE_AHEAD).toTypedArray())
        // Settings → Load ahead: the next song or three loaded whole, once this one is under way.
        val preload = c.settings.current.preload
        preloadJob?.cancel()
        if (preload.songs > 0) {
            val playing = player.currentMediaItem?.mediaId
            val ids = upcoming(preload.songs).map { it.mediaId }.filter { it.isNotBlank() && !it.startsWith(Song.RADIO_PREFIX) && !c.downloads.isDownloaded(it) }
            preloadJob = scope.launch {
                if (playing != null) untilUnderWay(playing, UNDER_WAY_MS)
                for (id in ids) withContext(Dispatchers.IO) { c.warmUp(id, whole = true, cellular = c.settings.current.preloadCellular) }
            }
        }
        if (next.startsWith(Song.LOCAL_PREFIX) || next.startsWith(Song.RADIO_PREFIX)) return
        // Its "Most replayed" curve (for the best part), ready before it plays, and the one after's (a double skip).
        val coming = listOfNotNull(player.getMediaItemAt(i).toSong(), upcoming(2).getOrNull(1)?.toSong())
        scope.launch { coming.forEach { s -> c.bestParts.prefetch(s) } }
        if (c.downloads.isDownloaded(next)) { armNextVideo(); return }
        // The song after it is resolved too (no audio fetched): skipping twice used to land on one that wasn't, which
        // took ~2 s to start. Each song is resolved once either way, only earlier.
        val afterNext = upcoming(2).getOrNull(1)?.mediaId
            ?.takeIf { it.isNotBlank() && !it.startsWith(Song.LOCAL_PREFIX) && !it.startsWith(Song.RADIO_PREFIX) && !c.downloads.isDownloaded(it) }
        scope.launch(Dispatchers.IO) {
            c.warmUp(next, cellular = c.settings.current.preloadCellular)
            afterNext?.let { c.streams.prefetch(it) }
            // A live stream coming up is lined up as one, so it starts without a failed attempt first.
            if (runCatching { c.streams.isLive(next) }.getOrDefault(false)) withContext(Dispatchers.Main) {
                val at = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == next && it != player.currentMediaItemIndex }
                val song = at?.let { player.getMediaItemAt(it).toSong() }
                if (at != null && song != null && !song.isLive) {
                    player.replaceMediaItem(at, song.copy(isLive = true).toMediaItem(player.getMediaItemAt(at).videoModeId?.let { song.id }))
                }
            }
        }
        armNextVideo()
    }

    /** Music videos whose song is being looked up for [songForNextVideo]. */
    private val findingSongs = HashSet<String>()

    /**
     * "Songs, not videos" everywhere (#80): a music video coming up next (a liked video, an album or playlist's video
     * track) is swapped for its song before it plays, the song's title, album and cover with it; the video stays the
     * Video button's. One lined up as its video on purpose (Keep playing videos) is left alone, and so is one with no
     * song. Offline, a downloaded video keeps its own sound unless its song is on the phone too. (The replaced item
     * changes the timeline, so [prefetchNext] then gets the song ready.)
     */
    private fun songForNextVideo(item: MediaItem) {
        if (item.videoModeId != null) return
        // A radio or mix's videos too, with just "Songs, not videos" on (#138): YouTube stopped pairing them with their songs
        // when the queue is made (2026-10), so a Discovery Mix came through as videos. Each is matched as it comes up next.
        val fromRadio = c.settings.current.radioSongsOnly && run {
            val at = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == item.mediaId } ?: -1
            val auto = c.queue.autoplayStart.value
            c.queue.source.value.kind == QueueKind.RADIO || (auto in 0..at)
        }
        if (!c.player.songsEverywhere() && !fromRadio) return
        val video = item.toSong()?.takeIf { it.isVideo && !it.isLive && !it.isEpisode && !it.isLocal } ?: return
        if (!findingSongs.add(video.id)) return
        scope.launch {
            try {
                fun queued() = (0 until player.mediaItemCount).firstOrNull {
                    it != player.currentMediaItemIndex && player.getMediaItemAt(it).let { m -> m.mediaId == video.id && m.videoModeId == null }
                }
                val song = c.player.songFor(video) ?: run {
                    // #328: no song of its length, so its own sound plays; its song's cover and album are there from
                    // the start, not seconds after it begins ([songCoverForVideo]). Same address: nothing reloads.
                    if (c.versions.of(video.id) != null) return@launch
                    val found = runCatching { withContext(Dispatchers.IO) { c.ytm.songForVideo(video, anyLength = true) } }.getOrNull() ?: return@launch
                    val cover = found.thumbnail?.takeIf { it != video.thumbnail } ?: return@launch
                    val at = queued() ?: return@launch
                    val now = player.getMediaItemAt(at).toSong() ?: return@launch
                    player.replaceMediaItem(at, VideoCover.withSong(now, found, cover).toMediaItem())
                    return@launch
                }
                if (!c.network.online.value && !c.availableOffline(song.id)) return@launch
                val at = queued() ?: return@launch
                player.replaceMediaItem(at, song.toMediaItem())
            } finally {
                findingSongs.remove(video.id)
            }
        }
    }

    /**
     * The listener picked the video (and hasn't gone back to the song since). With "Keep playing videos"
     * on, each new song then plays as its video too.
     */
    var videoWanted = false
        set(v) {
            field = v
            if (v) armNextVideo() else disarmVideos()
        }

    private fun keepVideos() = videoWanted && c.settings.current.videoContinues && c.settings.current.showVideoSwitch

    /**
     * A new song started while videos are wanted. The next song is usually lined up as its video
     * already ([armNextVideo]); if it wasn't ready (a quick skip), it switches here. Songs without a
     * video just play as songs, quietly.
     */
    private fun continueVideo() {
        if (!keepVideos()) return
        val item = player.currentMediaItem ?: return
        val song = item.toSong() ?: return
        if (item.videoModeId != null) { watchVideo(song.id); return }
        scope.launch { setVideoMode(true) }
    }

    /** Lines the next song up as its video, so moving on to it is seamless. */
    private fun armNextVideo() {
        if (!keepVideos()) return
        val i = player.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        val item = player.getMediaItemAt(i)
        if (item.videoModeId != null) return
        val song = item.toSong() ?: return
        scope.launch {
            val vid = if (song.isLive) song.id else videoIdFor(song).id ?: return@launch
            val at = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return@launch
            val next = player.getMediaItemAt(at)
            if (!keepVideos() || next.mediaId != song.id || next.videoModeId != null) return@launch
            val nextSong = next.toSong() ?: return@launch
            if (failedVideos.remove(vid)) c.streams.invalidate(vid)
            player.replaceMediaItem(at, nextSong.toMediaItem(if (nextSong.isLive) nextSong.id else vid))
            if (!nextSong.isLive) prefetchVideo(nextSong, vid)
        }
    }

    /** Back to songs: anything lined up as a video (other than what's playing) goes back to audio. */
    private fun disarmVideos() {
        for (i in 0 until player.mediaItemCount) {
            if (i == player.currentMediaItemIndex) continue
            val other = player.getMediaItemAt(i)
            if (other.videoModeId != null) other.toSong()?.let { player.replaceMediaItem(i, it.toMediaItem(null)) }
        }
    }

    private val prefetched = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Videos that failed to play; their streams are re-resolved on the next try. */
    private val failedVideos = HashSet<String>()

    /** Swaps the current item between its audio track and its music video. Returns an error message, if any. */
    suspend fun setVideoMode(enabled: Boolean): String? {
        val idx = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return null
        val song = item.toSong() ?: return null
        if (enabled == (item.videoModeId != null)) return null
        if (song.isLive) {
            // The picture is already in the stream: same item, video track on (or off), no seeking about.
            swapInPlace(idx, song.toMediaItem(if (enabled) song.id else null))
            applyLiveVideo()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            videoWatchdog?.cancel()
            if (enabled) watchVideo(song.id)
            return null
        }
        val vid = if (enabled) {
            val found = videoIdFor(song)
            found.id ?: return if (found.lookupFailed) tr(R.string.couldn_t_reach_youtube_for_the) else tr(R.string.no_music_video_for_this_song)
        } else null
        if (player.currentMediaItemIndex != idx) return null
        // Asked twice meanwhile (the service as the song started, and a screen showing video): the first already
        // swapped it, so the second doesn't load it again.
        if ((player.currentMediaItem?.videoModeId != null) == enabled) return null
        if (vid != null && failedVideos.remove(vid)) c.streams.invalidate(vid)
        // Videos often have an intro, so the same moment falls at a different time: carry the
        // listener to the matching spot. Uses the measured offset if it's ready (it's prefetched);
        // otherwise switch now and correct the position as soon as it's known.
        val known = when {
            vid != null -> c.videoSync.cached(song.id, vid)
            else -> item.videoModeId?.let { c.videoSync.cached(song.id, it) }?.let { -it }
        }
        val now = player.currentPosition
        val pos = (now + (known ?: 0L)).coerceAtLeast(0)
        val playWhenReady = player.playWhenReady
        swapInPlace(idx, song.toMediaItem(vid))
        player.seekTo(idx, pos)
        player.playWhenReady = playWhenReady
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        videoWatchdog?.cancel()
        if (vid != null) {
            val switchedAt = android.os.SystemClock.elapsedRealtime()
            if (known == null && vid != song.id && now > 1_500) scope.launch {
                val lag = c.videoSync.lagMs(song.id, vid) ?: return@launch
                val current = player.currentMediaItem
                // Only if the listener is still on this video, and only right after switching: later, it's a jump in
                // the middle of the video (#203: the measure took 14 s and moved it 7.9 s on). The lyrics follow the
                // video by the same measure either way (PlayerConnection's video sync).
                if (kotlin.math.abs(lag) >= 1_000 && current?.mediaId == song.id && current.videoModeId == vid &&
                    android.os.SystemClock.elapsedRealtime() - switchedAt < LATE_VIDEO_SEEK_MS
                ) player.seekTo(player.currentPosition + lag)
            }
            watchVideo(song.id)
        }
        return null
    }

    private var videoWatchdog: Job? = null

    /**
     * A music video playing as a song shows its song's album cover, not a frame of the video (Reddit, 2026-10: "Carl
     * Sagan" by Night Moves played with a still from its video), when its song can be found (YouTube's pairing, or a
     * search for the same title by the same artist, once per video). Only the cover changes: the item keeps its address (and
     * its video mode), so Media3 swaps it without a pause, and the video and its sound play on as they were.
     */
    /**
     * A song that came without its album (#312: Speed dial's tiles, and other Home cards, carry only a title and the
     * artist) gets it from YouTube's own queue for it, whose first item is the song with its album: the player's
     * album link, "From the album" and the history row then have it. Same address, so it plays on untouched.
     */
    private fun albumForSong(song: Song) {
        if (!AlbumFill.wanted(song)) return
        scope.launch {
            val found = runCatching { withContext(Dispatchers.IO) { c.ytm.next(song.id, "RDAMVM${song.id}").songs.take(3) } }.getOrNull() ?: return@launch
            val now = player.currentMediaItem
            if (now?.mediaId != song.id) return@launch
            val current = now.toSong() ?: return@launch
            val filled = AlbumFill.withAlbum(current, found) ?: return@launch
            swapInPlace(player.currentMediaItemIndex, filled.toMediaItem(now.videoModeId))
            withContext(Dispatchers.IO) { runCatching { c.db.songs().upsert(filled.toEntity(c.db.songs().get(filled.id))) } }
        }
    }

    /**
     * #305: the queue's songs that [shows] the cover just picked (or put back) for, made again with it, so the
     * notification, the lock screen and the car show it. Only the cover changes: the same address, so nothing pauses.
     */
    fun refreshCovers(shows: (Song) -> Boolean) {
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val song = item.toSong() ?: continue
            if (shows(song)) swapInPlace(i, song.toMediaItem(item.videoModeId))
        }
    }

    private fun songCoverForVideo(song: Song) {
        if (!song.isVideo || song.isLive || song.isLocal || song.isEpisode || song.isUpload) return
        // A version picked to play in its place shows as that one already (#328).
        if (c.versions.of(song.id) != null) return
        val item = player.currentMediaItem ?: return
        // A video found as a song's video already shows its song's cover; a video with no song found when it was
        // tapped (PlayerConnection.playQueue waits 4 s for it) is the item itself, shown as its video or, after Song, as its sound.
        if (item.videoModeId != null && item.videoModeId != song.id) return
        scope.launch {
            // Its song if one was found to play (the session's), else the same title by the same artist at any length:
            // only for the cover, so it's never kept as the song to play in its place.
            val found = c.player.songFor(song) ?: runCatching { withContext(Dispatchers.IO) { c.ytm.songForVideo(song, anyLength = true) } }.getOrNull()
            android.util.Log.i("PrismVideo", "cover for video ${song.id} \"${song.title}\": song ${found?.id} ${found?.thumbnail}")
            if (found == null) return@launch
            val cover = found.thumbnail?.takeIf { it != song.thumbnail } ?: return@launch
            val idx = player.currentMediaItemIndex
            val now = player.currentMediaItem
            // Still this video; its picture or sound goes on as it is (the same address: same video mode).
            if (now?.mediaId != song.id) return@launch
            val current = now.toSong() ?: return@launch
            swapInPlace(idx, VideoCover.withSong(current, found, cover).toMediaItem(now.videoModeId))
        }
    }

    /**
     * No picture after a fair wait: fall back to the song (the button stays usable). While the video is still loading
     * (a slow TV after a fast skip or a long seek) it waits on, up to [VIDEO_WAIT_MAX_MS]; the song playing on without
     * a picture is the failure.
     */
    private fun watchVideo(songId: String) {
        videoWatchdog?.cancel()
        videoWatchdog = scope.launch {
            var waited = 0L
            while (true) {
                delay(VIDEO_WATCH_STEP_MS)
                waited += VIDEO_WATCH_STEP_MS
                val cur = player.currentMediaItem
                if (cur?.mediaId != songId || cur.videoModeId == null) return@launch
                when (videoWatchVerdict(player.videoSize.width > 0, player.playbackState == Player.STATE_BUFFERING, waited)) {
                    VideoWatch.FINE -> return@launch
                    VideoWatch.WAIT -> Unit
                    VideoWatch.GIVE_UP -> { revertVideo(songId); return@launch }
                }
            }
        }
    }

    /**
     * A live stream's HLS variants carry video and audio together, so in audio mode its video track is turned off
     * (nothing to decode, and the player can take a lighter variant); other items leave video on for music videos.
     */
    private fun applyLiveVideo() {
        val item = player.currentMediaItem
        val off = item != null && item.toSong()?.isLive == true && item.videoModeId == null
        val params = player.trackSelectionParameters
        if ((C.TRACK_TYPE_VIDEO in params.disabledTrackTypes) != off) {
            player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, off).build()
        }
    }

    /** Switches back to the audio version after a video failure; the video can be tried again. */
    private suspend fun revertVideo(songId: String) {
        videoWatchdog?.cancel()
        val idx = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        val song = current.toSong() ?: return
        if (song.isLive) {
            swapInPlace(idx, song.toMediaItem(null))
            applyLiveVideo()
            if (player.playbackState == Player.STATE_IDLE) { player.prepare(); player.play() }
            c.player.onVideoFailed(songId)
            c.player.reportError(tr(R.string.the_live_video_didn_t_load))
            return
        }
        current.videoModeId?.let { failedVideos += it; prefetched -= it }
        android.util.Log.w("PrismVideo", "video ${current.videoModeId} for $songId didn't load (${player.playerError?.errorCodeName ?: "no picture in time"}); back to the song")
        val lag = current.videoModeId?.takeIf { it != song.id }?.let { c.videoSync.cached(song.id, it) } ?: 0L
        val pos = (player.currentPosition - lag).coerceAtLeast(0)
        swapInPlace(idx, song.toMediaItem(null))
        player.seekTo(idx, pos)
        player.prepare()
        player.play()
        c.player.onVideoFailed(songId)
        c.player.reportError(tr(R.string.the_music_video_didn_t_load))
    }

    /** Whether the song being heard was Prism's or YouTube's suggestion (radio, autoplay, a mix, Smart Shuffle). */
    private var trackedRecommended = false

    /** How long the song being tracked has been heard so far (what [flushPlayTime] would count now). */
    private fun heardMs(): Long = accumulatedMs + (if (playStartedAt > 0) SystemClock.elapsedRealtime() - playStartedAt else 0L)

    /** Nothing is being heard as a play: a song passed over at once ([onMediaItemTransition]) isn't tracked. */
    private fun untrack() {
        trackedSong = null
        trackedRecommended = false
    }

    private fun flushPlayTime(skipped: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (playStartedAt > 0) accumulatedMs += now - playStartedAt
        playStartedAt = if (::player.isInitialized && player.isPlaying) now else 0
        val song = trackedSong
        val source = trackedSource
        val excluded = trackedExcluded
        val ms = accumulatedMs
        accumulatedMs = 0
        // History paused (#296): a private stretch, as Spotify's private session: no scrobbles, nothing learned from it.
        val paused = com.prism.music.data.HistoryPause.paused(c.settings.current.behavior)
        // Last.fm counts every stretch heard (#133); it scrobbles once the play is long enough.
        if (song != null && ms > 0 && !paused) c.lastFm.heard(song, ms)
        // What the listener made of a suggestion (1.7.9): skipped within its first moments, it's left out of them for a while;
        // heard through, it's welcome. Under ten seconds a play isn't kept at all, so this is the only trace of a quick skip.
        if (song != null && trackedRecommended && !song.isEpisode && !song.isLive && !paused) {
            val length = song.durationSec * 1000L
            when {
                skipped && ms < minOf(30_000L, if (length > 0) length * 3 / 10 else 30_000L) -> c.recFeedback.skipped(song.id, song.primaryArtist)
                length > 0 && ms >= length * 7 / 10 -> c.recFeedback.heard(song.id, song.primaryArtist)
            }
        }
        // Replay, smart downloads and search taste are about music: episodes aren't counted. Long enough to be a play
        // (#263: ten seconds unless the listener picked otherwise), and not while history is paused (#296).
        if (song != null && !song.isRadio && com.prism.music.data.HistoryPause.keepsPlay(c.settings.current.behavior, ms, song.isEpisode)) {
            scope.launch(Dispatchers.IO) {
                c.db.plays().insert(PlayEventEntity(songId = song.id, timestamp = System.currentTimeMillis(), playedMs = ms, source = source, excluded = excluded))
            }
        }
    }

    /**
     * Sing on (or set to get songs ready while it's off): the playing song's vocals first, then the next song's, so
     * it's ready when it starts.
     */
    private fun singAhead() {
        if (!c.sing.onNow && !c.settings.current.singAlwaysReady) return
        val songs = listOfNotNull(player.currentMediaItem, *upcoming(1).toTypedArray()).filter { it.videoModeId == null }.mapNotNull { it.toSong() }
        // Where the playing song is: Sing switched on part way through starts there (1.8.x).
        c.sing.prepare(songs, player.currentPosition)
    }

    /** What a play of [item] is counted as (play_events.source): the kind of queue it's in. */
    private fun sourceOf(item: MediaItem): String = when {
        c.sing.onNow -> "sing"
        c.dj.onNow -> "dj"
        item.mediaId in c.queue.smartPicks.value -> "smart"
        else -> c.queue.source.value.kind.name.lowercase()
    }

    // ------------------------------------------------------------------ Smart Shuffle

    private var smartJob: kotlinx.coroutines.Job? = null
    /** Recommendations ready to slip in (from radios of the queue's own songs). */
    private val smartPool = ArrayDeque<Song>()
    private var smartPoolFor: QueueSource? = null

    /**
     * Smart Shuffle: a recommendation after every three or four of the queue's own songs, in the shuffled order (each
     * goes in at the queue index after one of the songs coming up, which [HistoryShuffleOrder] keeps beside it). Only
     * the next dozen songs are looked at, so it keeps step as the queue plays; Smart Shuffle off takes the ones not
     * played yet back out.
     */
    private fun smartAhead() {
        smartJob?.cancel()
        if (!c.queue.smartShuffle.value || !player.shuffleModeEnabled || !c.queue.smartAllowed()) return
        smartJob = scope.launch {
            val picks = c.queue.smartPicks.value
            val tl = player.currentTimeline
            var i = player.currentMediaItemIndex
            if (i == C.INDEX_UNSET) return@launch
            // Own songs since the last pick, counting the one playing.
            var run = if (player.getMediaItemAt(i).mediaId in picks) 0 else 1
            val anchors = ArrayList<Int>()
            var seen = 0
            while (seen < SMART_LOOK_AHEAD && anchors.size < 2) {
                val next = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
                if (next == C.INDEX_UNSET) break
                seen++
                if (player.getMediaItemAt(next).mediaId in picks) { run = 0; i = next; continue }
                run++
                // Three or four own songs between picks, alternately.
                val gap = 3 + (picks.size + anchors.size) % 2
                val after = tl.getNextWindowIndex(next, Player.REPEAT_MODE_OFF, true)
                if (run >= gap && (after == C.INDEX_UNSET || player.getMediaItemAt(after).mediaId !in picks)) { anchors += next; run = 0 }
                i = next
            }
            if (anchors.isEmpty()) return@launch
            val src = c.queue.source.value
            if (smartPoolFor !== src) { smartPool.clear(); smartPoolFor = src }
            if (smartPool.size < anchors.size) fillSmartPool()
            // Highest index first, so the other anchors' indices stay put.
            for (a in anchors.sortedDescending()) {
                if (!c.queue.smartShuffle.value || c.queue.source.value !== src) return@launch
                val song = smartPool.removeFirstOrNull() ?: break
                val at = a + 1
                if (c.queue.autoplayStart.value >= at) c.queue.autoplayStart.value += 1
                c.queue.smartPicks.value = c.queue.smartPicks.value + song.id
                player.addMediaItem(at, song.toMediaItem())
            }
        }
    }

    /** More recommendations: radios from a few of the queue's own songs, less what's queued, disliked or excluded. */
    private suspend fun fillSmartPool() {
        val inQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val own = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            .filter { it.mediaId !in c.queue.smartPicks.value }.mapNotNull { it.toSong() }.filter { !it.isLocal && !it.isEpisode && !it.isLive }
        if (own.isEmpty()) return
        val recent = c.library.recent.value.take(50).map { it.id }.toSet()
        for (seed in own.shuffled().take(2)) {
            val r = runCatching { withContext(Dispatchers.IO) { c.radio(seed.id, "RDAMVM${seed.id}") } }.getOrNull() ?: continue
            r.songs.filter { s ->
                s.id !in inQueue && s.id !in recent && !s.isVideo && !s.isLive && !c.library.isDisliked(s.id) && !c.exclusions.blocks(s) &&
                    smartPool.none { it.id == s.id } && !c.recFeedback.turnedDown(s.id, known = false) &&
                    // One offered in the last day or two (and not taken up) waits its turn.
                    (c.recFeedback.servedDaysAgo(s.id) ?: 99) > 1
            }.shuffled().take(6).forEach { smartPool += it }
        c.recFeedback.served(smartPool.map { it.id })
        }
    }

    /** Smart Shuffle off: its picks still to come leave the queue (the one playing stays). */
    private fun dropSmartPicks() {
        smartJob?.cancel()
        val picks = c.queue.smartPicks.value
        if (picks.isEmpty()) return
        val cur = player.currentMediaItemIndex
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == cur || player.getMediaItemAt(i).mediaId !in picks) continue
            player.removeMediaItem(i)
            if (c.queue.autoplayStart.value > i) c.queue.autoplayStart.value -= 1
        }
        c.queue.smartPicks.value = player.currentMediaItem?.mediaId?.takeIf { it in picks }?.let { setOf(it) } ?: emptySet()
    }

    // ------------------------------------------------------------------ DJ mode

    private var djJob: kotlinx.coroutines.Job? = null
    private var djSoon: kotlinx.coroutines.Job? = null
    /** DJ mode is moving songs itself (the timeline changes that causes aren't new songs). */
    private var djMoving = false

    /**
     * DJ mode: the songs ahead get a quick scan each (key, tempo, loudness from three stretches of the song,
     * [MixTempo.quickScan]), a few at once, the next ones first, and are put in DJ order ([resequence]) as the scans come
     * in. A song not on the phone is fetched (only the stretches) on Wi-Fi, or on mobile data when "Measure songs on
     * mobile data" is on; the queue says when songs are waiting for Wi-Fi, and offers mobile data.
     */
    private fun djAhead() {
        djJob?.cancel()
        if (!c.dj.onNow) return
        if (player.shuffleModeEnabled) player.shuffleModeEnabled = false
        djJob = scope.launch {
            val s = c.settings.current
            val network = c.dj.dataAllowed || s.mixTempoOnCellular ||
                getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
            resequence()
            val cur = player.currentMediaItemIndex
            // The songs after the playing one, in queue order (the next ones matter first); the playing one last.
            val ahead = (cur + 1 until minOf(player.mediaItemCount, cur + 2 + DJ_WINDOW))
                .map { player.getMediaItemAt(it) }.filter { djTrack(it) != null }.map { it.mediaId }
            val ids = (ahead + listOfNotNull(player.currentMediaItem?.takeIf { djTrack(it) != null }?.mediaId)).distinct()
            var looked = 0; var keyed = 0; var needNetwork = 0
            fun publish() = c.dj.setProgress(DjProgress(looked, ids.size, keyed, needNetwork))
            for (id in ids) if (c.mixTempo.keyed(id)) { looked++; keyed++ }
            publish()
            val started = SystemClock.elapsedRealtime()
            kotlinx.coroutines.coroutineScope {
                for (id in ids.filter { !c.mixTempo.keyed(it) }) launch {
                    val r = c.mixTempo.quickScan(id, network)
                    looked++
                    when (r) {
                        MixTempo.QuickResult.KEYED -> keyed++
                        MixTempo.QuickResult.NEEDS_NETWORK -> needNetwork++
                        MixTempo.QuickResult.FAILED -> {}
                    }
                    publish()
                    c.dj.bump()
                    if (r == MixTempo.QuickResult.KEYED) resequenceSoon()
                }
            }
            android.util.Log.i("PrismDj", "looked at ${ids.size} songs in ${SystemClock.elapsedRealtime() - started} ms: $keyed keyed, $needNetwork waiting for Wi-Fi")
        }
    }

    /** A queue item as DJ mode sees it (null for what it leaves where it is: live streams, podcasts, videos). */
    private fun djTrack(item: MediaItem, id: String = item.mediaId): DjTrack? {
        val song = item.toSong() ?: return null
        if (song.isLive || song.isEpisode || item.videoModeId != null) return null
        return DjTrack(id, c.mixTempo.key(song.id), c.mixTempo.djBpm(song.id), c.mixTempo.djLoudness(song.id), song.artists.firstOrNull()?.name)
    }

    /**
     * Puts the songs ahead in DJ order: the playing song and the next stay (the next is already being got ready, and
     * may be blending in), as do songs the listener queued themselves; up to [DJ_WINDOW] after those are ordered to
     * follow on from the next song. Songs not scanned yet keep their places after the scanned ones, and join the
     * order as their scans land. Only moves songs (nothing reloads).
     */
    private fun resequence() {
        if (!c.dj.onNow || player.shuffleModeEnabled) return
        val cur = player.currentMediaItemIndex
        if (cur == C.INDEX_UNSET || cur + 1 >= player.mediaItemCount) return
        val queued = c.queue.userQueued.value
        var base = cur + 2
        while (base < player.mediaItemCount && player.getMediaItemAt(base).mediaId in queued) base++
        // Within the queue's own songs, or within autoplay's, so neither list in the queue gets the other's songs.
        val autoplay = c.queue.autoplayStart.value.takeIf { it > 0 } ?: Int.MAX_VALUE
        val limit = if (base < autoplay) minOf(autoplay, player.mediaItemCount) else player.mediaItemCount
        val end = minOf(limit, base + DJ_WINDOW)
        if (end - base < 2) return
        val anchor = djTrack(player.getMediaItemAt(cur + 1)) ?: return
        val items = (base until end).map { player.getMediaItemAt(it) }
        val tracks = items.mapIndexed { i, item -> if (c.mixTempo.keyed(item.mediaId)) djTrack(item, id = i.toString()) else null }
        val known = tracks.filterNotNull()
        if (known.size < 2) return
        val set = DjSequencer.order(anchor, known, c.settings.current.djFlow)
        android.util.Log.d("PrismDj", "set after ${anchor.id}: " + (listOf(anchor) + set).joinToString(" → ") { t ->
            (t.key?.takeIf { it.sure }?.camelot ?: "?") + "/" + (t.bpm?.toInt() ?: "?")
        } + " (+${items.size - known.size} not scanned)")
        val ordered = set.map { it.id.toInt() }
        val desired = ordered + items.indices.filter { tracks[it] == null }
        if (desired == items.indices.toList()) return
        // One change for the whole new order (Reddit/owner 1.7.2: the queue lagged with DJ mode on): it used to be one
        // move per song, each a separate queue change sent on to the player screen, the session and the car.
        djMoving = true
        try {
            player.replaceMediaItems(base, end, desired.map { items[it] })
        } finally {
            djMoving = false
        }
    }

    private var resequenceJob: kotlinx.coroutines.Job? = null

    /** [resequence] once things settle: scans landing one after another reorder the queue once, not each time. */
    private fun resequenceSoon() {
        if (resequenceJob?.isActive == true) return
        resequenceJob = scope.launch { delay(RESEQUENCE_GAP_MS); resequence() }
    }

    /** The pitch music plays at: Sing's key while Sing is on (−6…+6 semitones), else the song's own. */
    private fun singPitch(): Float = if (c.sing.onNow) Math.pow(2.0, c.sing.semitones.value / 12.0).toFloat() else 1f

    /** A Wear OS watch's companion app (Pixel Watch, Wear OS) is on the phone ([updateButtons]). */
    private val watchPaired by lazy {
        WATCH_COMPANIONS.any { p -> runCatching { packageManager.getPackageInfo(p, 0) }.isSuccess }
    }

    /**
     * Buttons beside play/pause/skip, in the listener's order (Settings → Player → Buttons, 1.7.4; like, shuffle, repeat,
     * lyrics, start radio and "not for me" by default). The phone's media controls show the first two; the watch keeps
     * previous/next beside play (see the session extras); Android Auto shows them all.
     */
    private fun updateButtons() {
        if (!::session.isInitialized) return
        val id = player.currentMediaItem?.mediaId
        val liked = id != null && id in c.library.likedIds.value
        fun button(icon: Int, name: String, command: SessionCommand) =
            CommandButton.Builder(icon).setDisplayName(name).setSessionCommand(command).setSlots(CommandButton.SLOT_OVERFLOW).build()
        val station = player.currentMediaItem?.mediaId?.takeIf { it.startsWith(Song.RADIO_PREFIX) }?.removePrefix(Song.RADIO_PREFIX)
        val custom = if (station != null) {
            // A radio station (Prism Car): the heart keeps it among the favorite stations; a song's other buttons (start
            // radio, not for me, seeking) mean nothing for it.
            val favorite = c.radioStations.isFavorite(station)
            listOf(button(if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
                if (favorite) tr(R.string.car_radio_remove_favorite) else tr(R.string.car_radio_add_favorite), likeCommand))
        } else c.settings.current.sessionButtons.map { b ->
            when (b) {
                SessionButton.LIKE -> button(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED, if (liked) tr(R.string.remove_from_liked) else tr(R.string.widget_like), likeCommand)
                SessionButton.SHUFFLE -> button(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF, if (player.shuffleModeEnabled) tr(R.string.shuffle_off) else tr(R.string.shuffle), shuffleCommand)
                SessionButton.REPEAT -> when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> button(CommandButton.ICON_REPEAT_ALL, tr(R.string.repeat_one), repeatCommand)
                    Player.REPEAT_MODE_ONE -> button(CommandButton.ICON_REPEAT_ONE, tr(R.string.repeat_off), repeatCommand)
                    else -> button(CommandButton.ICON_REPEAT_OFF, tr(R.string.repeat), repeatCommand)
                }
                SessionButton.CAR_LYRICS -> if (c.settings.current.carLyrics) button(CommandButton.ICON_SUBTITLES, tr(R.string.hide_lyrics), lyricsCommand)
                    else button(CommandButton.ICON_SUBTITLES_OFF, tr(R.string.show_lyrics), lyricsCommand)
                SessionButton.RADIO -> button(CommandButton.ICON_RADIO, tr(R.string.start_radio), radioCommand)
                SessionButton.DISLIKE -> button(CommandButton.ICON_THUMB_DOWN_UNFILLED, tr(R.string.not_for_me), dislikeCommand)
                SessionButton.BACK_10 -> button(CommandButton.ICON_SKIP_BACK_10, tr(R.string.back_10_seconds), back10Command)
                SessionButton.AHEAD_30 -> button(CommandButton.ICON_SKIP_FORWARD_30, tr(R.string.ahead_30_seconds), ahead30Command)
            }
        }
        // Previous/next named for their slots outright: with only custom buttons, a controller that lays out by slots (the
        // Pixel Watch's media controls) filled the back/forward places with like and shuffle (1.6.1). But with them
        // Media3 takes the standard skip actions off the phone's media session, so widgets and "Dynamic Island" apps
        // that read those couldn't skip (Reddit R4). So they're only named when a watch's companion app is on the phone.
        val buttons = (if (watchPaired) listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS).setDisplayName(tr(R.string.widget_previous))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_NEXT).setDisplayName(tr(R.string.widget_next))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT).setSlots(CommandButton.SLOT_FORWARD).build(),
        ) else emptyList()) + custom
        session.setMediaButtonPreferences(buttons)
        pushWidgets()
    }

    /** Home screen widgets follow what's playing (nothing happens when none is placed). */
    /** A progress-only widget refresh was skipped with the screen off (see the service's tick). */
    private var widgetsBehind = false

    private fun screenOn(): Boolean = (getSystemService(POWER_SERVICE) as? android.os.PowerManager)?.isInteractive != false

    /**
     * The widgets (and Discord) brought up to date. [progressOnly]: only the playhead has moved since the last time,
     * which waits while the screen is off (each refresh sends the launcher the cover again).
     */
    private fun pushWidgets(progressOnly: Boolean = false) {
        if (!::player.isInitialized) return
        val song = player.currentMediaItem?.toSong()
        if (progressOnly && !screenOn()) widgetsBehind = true
        else {
            widgetsBehind = false
            com.prism.music.widget.Widgets.update(
                this, song, player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE,
                song != null && song.id in c.library.likedIds.value,
                player.currentPosition, player.duration.takeIf { it > 0 },
            )
        }
        // Discord shows what's playing too, when that's on (it only sends what changed).
        c.discord.show(song, player.isPlaying, player.currentPosition, player.duration.takeIf { it > 0 } ?: 0)
    }

    /** A fresh shuffle order each time shuffle goes on, current song first. */
    private fun reshuffle() {
        val n = player.mediaItemCount
        if (n < 2) return
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        player.setShuffleOrder(HistoryShuffleOrder.shuffled(n, cur) { player.currentMediaItemIndex })
    }

    /** Swaps everything after the current song for a radio station seeded by it. */
    private fun startRadio() {
        val song = player.currentMediaItem?.toSong() ?: return
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { c.radio(song.id, "RDAMVM${song.id}") } }.getOrNull() ?: return@launch
            if (player.currentMediaItem?.mediaId != song.id) return@launch
            val explicit = c.settings.current.behavior.explicitContent
            val add = r.songs.filter { it.id != song.id && !c.library.isDisliked(it.id) && !c.exclusions.blocks(it) && com.prism.music.data.ExplicitFilter.plays(explicit, it) }
            if (add.isEmpty()) return@launch
            val idx = player.currentMediaItemIndex
            if (idx + 1 < player.mediaItemCount) player.removeMediaItems(idx + 1, player.mediaItemCount)
            if (idx > 0) player.removeMediaItems(0, idx)
            player.shuffleModeEnabled = false
            player.addMediaItems(add.map { it.toMediaItem() })
            c.queue.source.value = QueueSource(QueueKind.RADIO, tr(R.string.radio_3, song.title), seedId = song.id)
            c.queue.autoplayStart.value = -1
            c.queue.autoplayContinuation = r.continuation
        }
    }

    private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo) =
        controller.packageName == "com.google.android.projection.gearhead" ||
            runCatching { session.isAutoCompanionController(controller) || session.isAutomotiveController(controller) }.getOrDefault(false)

    fun applyStreamStats(id: String) {
        val (source, s) = c.streamDetails[id] ?: return
        if (player.currentMediaItem?.mediaId != id && player.currentMediaItem?.videoModeId != id) return
        // Downloaded and cached songs have no stream info this session: work the bitrate out
        // from the saved file's size over the song's length.
        val savedBytes = if (s == null) savedLength(id) else 0L
        val durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: player.currentMediaItem?.toSong()?.durationSec?.takeIf { it > 0 }?.times(1000L) ?: 0L
        val savedKbps = if (savedBytes > 0 && durationMs > 0) (savedBytes * 8 / durationMs).toInt() else 0
        c.nerdStats.value = c.nerdStats.value.copy(
            source = source,
            itag = s?.itag ?: 0,
            container = s?.mimeType ?: c.nerdStats.value.container,
            codec = s?.codec ?: c.nerdStats.value.codec,
            streamBitrateKbps = s?.bitrate ?: savedKbps.takeIf { it > 0 } ?: c.nerdStats.value.streamBitrateKbps,
            contentLength = s?.contentLength ?: savedBytes,
            client = s?.client ?: "",
        )
    }

    /**
     * The lossless add-on: a matching FLAC / WAV / AIFF on the phone, when lossless mode and the add-on are on; and since
     * 1.8.1 one of your own MP3 / AAC / Ogg files when "Use your own song files" is on.
     */
    /** Files on the phone, read as the player loads them (a test holds the loading here: [TestHooks.songLoads]). */
    private fun localFiles(): androidx.media3.datasource.DataSource.Factory {
        val files = androidx.media3.datasource.DefaultDataSource.Factory(this)
        return androidx.media3.datasource.ResolvingDataSource.Factory(files) { spec -> if (com.prism.music.TestHooks.singHolding) com.prism.music.TestHooks.songLoads(); spec }
    }

    private fun losslessFileFor(item: MediaItem): android.net.Uri? {
        // Local music always plays from its file.
        if (item.mediaId.startsWith(Song.LOCAL_PREFIX)) {
            val uri = c.localMusic.uriFor(item.mediaId) ?: return null
            val format = c.localMusic.track(item.mediaId)?.mime?.let(::fileFormatName)
            c.streamDetails[item.mediaId] = "On this phone" + (format?.let { " · $it" } ?: "") to null
            scope.launch { applyStreamStats(item.mediaId) }
            return uri
        }
        // Lossless files need lossless playback, as in 1.8.0; your own MP3/AAC/Ogg files (#314) need only their switch,
        // and give way to a lossless file of the same song ([com.prism.music.data.local.PlaySourceRule]).
        val kinds = com.prism.music.data.local.OwnFiles.kinds(c.settings.current)
        if (!kinds.any) return null
        val song = item.toSong() ?: return null
        val track = c.localLossless.match(song, lossless = kinds.lossless, lossy = kinds.lossy) ?: return null
        c.streamDetails[song.id] = (if (track.lossy) com.prism.music.data.local.OwnFiles.source(track) else "Lossless · ${track.format} on this phone") to null
        scope.launch { applyStreamStats(song.id) }
        return track.uri
    }

    private fun savedLength(id: String): Long = c.localLossless.matched[id]?.size ?: runCatching {
        listOf(c.downloadCache to id, c.playerCache to c.songCacheKey(id)).firstNotNullOfOrNull { (cache, key) ->
            androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(key)).takeIf { it > 0 }
        } ?: 0L
    }.getOrDefault(0L)

    // ------------------------------------------------------------ Queue persistence

    private val queueFile get() = File(filesDir, "queue.json")

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(2000); saveQueue() }
    }

    /**
     * Writes what's lined up to queue.json. [now]: on this thread, for the last save as the service ends (1.7.9.3: it
     * went through [scope], which onDestroy cancels right after, so the final save could be dropped and the queue from
     * before came back; the in-app test PlaybackModesTest failed on it about every other run). Other saves go through
     * one writer, in order, and every write goes to a temporary file first, then takes the real one's place.
     */
    private fun saveQueue(now: Boolean = false) {
        if (!::player.isInitialized) return
        val songs = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toSong() }
        // The play order while shuffled, walked from its first song (only when every item made it into the list).
        val order = if (player.shuffleModeEnabled && songs.size == player.mediaItemCount) player.shuffleOrder.let { so ->
            generateSequence(so.firstIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET }) { i -> so.getNextIndex(i).takeIf { it != androidx.media3.common.C.INDEX_UNSET } }
                .take(songs.size).toList()
        } else emptyList()
        val q = SavedQueue(
            songs, player.currentMediaItemIndex, player.currentPosition, c.queue.source.value.title,
            shuffle = player.shuffleModeEnabled, shuffleOrder = order, repeat = player.repeatMode,
        )
        val text = InnerTube.json.encodeToString(SavedQueue.serializer(), q)
        if (now) writeQueue(text) else scope.launch(queueWriter) { writeQueue(text) }
    }

    private val queueWriter = Dispatchers.IO.limitedParallelism(1)

    @Synchronized private fun writeQueue(text: String) {
        runCatching {
            val tmp = File(filesDir, "queue.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(queueFile)) { queueFile.writeText(text); tmp.delete() }
        }
    }

    private fun restoreQueue() {
        val q = runCatching { InnerTube.json.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull() ?: return
        // Repeat and shuffle are how the listener listens, kept with nothing lined up too (#241: the song swiped away from
        // the mini player, then Prism closed, and repeat was off again).
        if (q.songs.isEmpty()) {
            if (q.repeat != Player.REPEAT_MODE_OFF) player.repeatMode = q.repeat
            if (q.shuffle) player.shuffleModeEnabled = true
            return
        }
        c.queue.source.value = QueueSource(QueueKind.OTHER, q.title)
        player.setMediaItems(q.songs.map { it.toMediaItem() }, q.index.coerceIn(0, q.songs.lastIndex), q.positionMs)
        // Shuffle and repeat as they were, in the same order.
        if (q.shuffle) {
            if (q.shuffleOrder.size == q.songs.size && q.shuffleOrder.sorted() == q.songs.indices.toList()) {
                player.setShuffleOrder(HistoryShuffleOrder(q.shuffleOrder.toIntArray()) { player.currentMediaItemIndex })
            } else reshuffle()
            player.shuffleModeEnabled = true
        }
        if (q.repeat != Player.REPEAT_MODE_OFF) player.repeatMode = q.repeat
        // The mini player shows this song: get it ready, so Play starts it straight away.
        player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { id -> scope.launch(Dispatchers.IO) { c.warmUp(id) } }
        // And the one after it (otherwise only a song coming up warms its successor).
        prefetchNext()
    }

    // ------------------------------------------------------------ Session

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (isCar(session, controller)) {
                carControllers += controller
                carLyrics.carConnected = true
            }
            if (controller.packageName != packageName) {
                AudioDiagnostics.log("control", "connected: ${controller.packageName} (${if (controller.isTrusted) "trusted" else "not trusted"})")
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().apply { customCommands.forEach { add(it) } }.build()
                )
                // Every app may play, pause and skip (#178, #187). Media3 1.11 gives apps it doesn't count as trusted only
                // the read-only player commands, so KDE Connect, "dynamic island" apps and launcher widgets showed the song
                // but their pause and skip were dropped without a word. Android already limits who can reach a media
                // session (the system, notification listeners, apps bound to the exported service), and a music player is
                // there to be controlled.
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (carControllers.remove(controller)) carLyrics.carConnected = carControllers.isNotEmpty()
        }

        // Every play, pause or skip another app sends (a widget, KDE Connect, a watch…), so a diagnostics log shows
        // whether a command arrived at all and from whom (#187, #178). Prism's own screens aren't logged.
        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            if (AudioDiagnostics.enabled && controller.packageName != packageName) {
                val what = when (playerCommand) {
                    Player.COMMAND_PLAY_PAUSE -> "play/pause"
                    Player.COMMAND_STOP -> "stop"
                    Player.COMMAND_PREPARE -> "prepare"
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "next"
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "previous"
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> "seek"
                    else -> null
                }
                if (what != null) {
                    AudioDiagnostics.log(
                        "control",
                        "$what from ${controller.packageName} (${if (controller.isTrusted) "trusted" else "not trusted"}, " +
                            "service ${if (inForeground()) "in the foreground" else "in the background"}, ${this@PlaybackService.javaClass.simpleName})",
                    )
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val key = runCatching {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            }.getOrNull()
            if (key?.action == android.view.KeyEvent.ACTION_DOWN) {
                AudioDiagnostics.log("control", "media button ${android.view.KeyEvent.keyCodeToString(key.keyCode)} from ${controllerInfo.packageName}")
            }
            // The notification swiped away (Media3 sends it as a media button with this extra): the listener is closing
            // Prism (Reddit, 1.7.7: it came back once, then sat in "background activity" until stopped by hand).
            if (intent.getBooleanExtra(NOTIFICATION_DISMISSED, false)) {
                val handled = super.onMediaButtonEvent(session, controllerInfo, intent)
                scope.launch { closedFromNotification() }
                return handled
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                likeCommand.customAction -> player.currentMediaItem?.toSong()?.let { song ->
                    if (song.isRadio) { c.radioStations.toggleFavorite(song.id.removePrefix(Song.RADIO_PREFIX)); updateButtons() }
                    else c.library.toggleLike(song)
                }
                shuffleCommand.customAction -> {
                    if (!player.shuffleModeEnabled) reshuffle()
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                repeatCommand.customAction -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                lyricsCommand.customAction -> c.settings.setCarLyrics(!c.settings.current.carLyrics)
                radioCommand.customAction -> if (player.currentMediaItem?.toSong()?.isRadio != true) startRadio()
                dislikeCommand.customAction -> player.currentMediaItem?.toSong()?.takeIf { !it.isRadio }?.let { song ->
                    c.library.setDisliked(song, true)
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                }
                back10Command.customAction -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                ahead30Command.customAction -> {
                    val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 30_000).coerceAtMost((end - 1_000).coerceAtLeast(0)))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // ---- Browsing (Android Auto, Assistant, other media browsers)

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            // Android Auto / the system ask for a "recent" root to offer a resume card. The root's hints say how the car shows
            // folders (cover tiles with the grid on) unless a folder says otherwise.
            Futures.immediateFuture(LibraryResult.ofItem(
                if (params?.isRecent == true) tree.recentRoot() else tree.root(),
                if (params?.isRecent == true) params else tree.rootExtras()?.let { hints ->
                    LibraryParams.Builder().setExtras(android.os.Bundle(params?.extras ?: android.os.Bundle.EMPTY).apply { putAll(hints) })
                        .setOffline(params?.isOffline == true).setSuggested(params?.isSuggested == true).build()
                } ?: params,
            ))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = tree.search(query)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            // This query's results (searched now if the car asks for them without a search first), not the last search's.
            val all = tree.searchItems(query)
            // A car that asks for no pages gets page 0 of Int.MAX_VALUE: worked out in Long, so it can't overflow.
            val from = (page.toLong() * pageSize).coerceIn(0, all.size.toLong()).toInt()
            LibraryResult.ofItemList(all.subList(from, (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()), params)
        }

        // ---- Playing what a controller picked

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                if (item.mediaId.startsWith(MediaTree.SHUFFLE)) tree.shuffleSongs(item.mediaId)?.second.orEmpty().shuffled().map { it.toMediaItem() }
                else if (item.mediaId.startsWith(MediaTree.DJ)) tree.shuffleSongs(item.mediaId)?.second.orEmpty().map { it.toMediaItem() }
                else listOfNotNull((item.toSong() ?: tree.song(item.mediaId, item))?.toMediaItem())
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            // "Hey Google, play … on Prism" (the phone's VoicePlayActivity, or the car's voice button through the
            // session's play-from-search): words and voice extras instead of an id.
            if (single != null && single.mediaId.isBlank() && single.toSong() == null) {
                return@future playVoice(mediaSession, controller, single.requestMetadata.searchQuery, single.requestMetadata.extras)
                    .also { voiceAnsweredAt = SystemClock.elapsedRealtime() }
            }
            // "Shuffle play" at the top of a list.
            if (single != null && single.mediaId.startsWith(MediaTree.SHUFFLE)) {
                tree.shuffleSongs(single.mediaId)?.takeIf { it.second.isNotEmpty() }?.let { (title, list) ->
                    c.queue.source.value = QueueSource(QueueKind.PLAYLIST, title)
                    c.queue.autoplayStart.value = -1
                    player.shuffleModeEnabled = true
                    // Shuffled here too, so each "Shuffle play" is a new order rather than the player's last one.
                    return@future MediaSession.MediaItemsWithStartPosition(list.shuffled().map { it.toMediaItem() }, 0, C.TIME_UNSET)
                }
            }
            // "Play as a DJ set" at the top of a list: from the first song, then in the order that blends best (DJ mode,
            // switched on once the songs are in, as the phone's "Play as a DJ set" does).
            if (single != null && single.mediaId.startsWith(MediaTree.DJ)) {
                tree.shuffleSongs(single.mediaId)?.takeIf { it.second.size > 1 }?.let { (title, list) ->
                    c.queue.source.value = QueueSource(QueueKind.PLAYLIST, title)
                    c.queue.autoplayStart.value = -1
                    c.dj.setOn(false)
                    player.shuffleModeEnabled = false
                    val first = list.first().id
                    scope.launch {
                        kotlinx.coroutines.withTimeoutOrNull(5_000) {
                            while (player.currentMediaItem?.mediaId != first) kotlinx.coroutines.delay(100)
                        } ?: return@launch
                        c.dj.setOn(true)
                    }
                    return@future MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, 0, C.TIME_UNSET)
                }
            }
            // One song tapped in a list: play the list from that song.
            if (single != null && single.toSong() == null) {
                tree.folderOf(single.mediaId)?.let { (title, list) ->
                    val index = list.indexOfFirst { it.id == single.mediaId }.coerceAtLeast(0)
                    c.queue.source.value = QueueSource(QueueKind.PLAYLIST, title)
                    c.queue.autoplayStart.value = -1
                    return@future MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, index, startPositionMs)
                }
                tree.song(single.mediaId, single)?.let { song ->
                    c.queue.source.value = QueueSource(QueueKind.SINGLE, tr(R.string.autoplay), seedId = song.id)
                    c.queue.autoplayStart.value = -1
                    c.queue.autoplayContinuation = null
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(song.toMediaItem()), 0, startPositionMs)
                }
            }
            val resolved = mediaItems.mapNotNull { item -> (item.toSong() ?: tree.song(item.mediaId, item))?.toMediaItem() }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }

        /**
         * A spoken request ([VoiceSearch]): an artist's songs, an album, a playlist (the listener's own first), liked songs,
         * downloads, a song and its radio, or Search's Top result; "play music" carries on with the last queue. With
         * nothing found the queue is left as it was (it used to be cleared, so the car went silent) and the asker is told
         * why, which Android Auto shows.
         */
        private suspend fun playVoice(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            query: String?,
            extras: Bundle?,
        ): MediaSession.MediaItemsWithStartPosition {
            val request = VoiceSearch.parse(query, extras)
            AudioDiagnostics.log("voice", "\"${query.orEmpty()}\" (${extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS) ?: "no focus"}) from ${controller.packageName}: $request")
            if (request == VoiceRequest.Resume) return onPlaybackResumption(session, controller, true).await()
            val pick = try {
                tree.voice(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AudioDiagnostics.log("voice", "couldn't search: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                session.sendError(controller, SessionError(SessionError.ERROR_IO, tr(R.string.voice_search_offline)))
                throw VoiceNotFound(request)
            }
            if (pick == null) {
                AudioDiagnostics.log("voice", "nothing found")
                val heard = query?.trim()?.takeIf { it.isNotEmpty() } ?: request.toString()
                session.sendError(controller, SessionError(SessionError.ERROR_BAD_VALUE, tr(R.string.voice_nothing_found, heard)))
                throw VoiceNotFound(request)
            }
            AudioDiagnostics.log("voice", "playing \"${pick.title}\": ${pick.songs.size} songs")
            c.queue.autoplayStart.value = -1
            c.queue.autoplayContinuation = null
            if (pick.radio) {
                val song = pick.songs.first()
                c.queue.source.value = QueueSource(QueueKind.SINGLE, pick.title, seedId = song.id)
                player.shuffleModeEnabled = false
                return MediaSession.MediaItemsWithStartPosition(listOf(song.toMediaItem()), 0, C.TIME_UNSET)
            }
            c.queue.source.value = QueueSource(QueueKind.PLAYLIST, pick.title)
            // Asked to shuffle: shuffled here too, so it's a new order each time (as the car's "Shuffle play" is);
            // otherwise in order, whatever shuffle was left on.
            player.shuffleModeEnabled = request.shuffle && pick.songs.size > 1
            val list = if (player.shuffleModeEnabled) pick.songs.shuffled() else pick.songs
            return MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, pick.start.coerceIn(0, list.lastIndex), C.TIME_UNSET)
        }

        /** Lets the system media controls (and the car) resume the last queue after Prism was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val q = withContext(Dispatchers.IO) {
                runCatching { InnerTube.json.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull()
            }
            if (q == null || q.songs.isEmpty()) {
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
            } else {
                c.queue.source.value = QueueSource(QueueKind.OTHER, q.title)
                MediaSession.MediaItemsWithStartPosition(q.songs.map { it.toMediaItem() }, q.index.coerceIn(0, q.songs.lastIndex), q.positionMs)
            }
        }
    }
    // ------------------------------------------------------------ Stats for nerds

    private inner class StatsListener : AnalyticsListener {
        private fun update(block: NerdStats.() -> NerdStats) {
            c.nerdStats.value = c.nerdStats.value.block()
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = update {
            copy(
                codec = format.codecs ?: format.sampleMimeType ?: codec,
                sampleRate = format.sampleRate.takeIf { it > 0 } ?: sampleRate,
                bitDepth = when (format.pcmEncoding) {
                    C.ENCODING_PCM_8BIT -> 8
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
                    else -> 0
                },
                channels = format.channelCount.takeIf { it > 0 } ?: channels,
                container = format.containerMimeType ?: container,
                streamBitrateKbps = streamBitrateKbps.takeIf { it > 0 }
                    ?: (format.averageBitrate.takeIf { it > 0 } ?: format.bitrate.takeIf { it > 0 })?.let { it / 1000 } ?: 0,
            )
        }

        // The length isn't always known when a downloaded song starts; fill its bitrate in once it is.
        override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (c.nerdStats.value.streamBitrateKbps <= 0) applyStreamStats(id)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) = update { copy(decoder = decoderName) }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
            update {
                copy(
                    outputEncoding = AudioEffects.encodingName(audioTrackConfig.encoding),
                    outputSampleRate = audioTrackConfig.sampleRate,
                    offload = audioTrackConfig.offload,
                    spatializer = c.settings.current.let { s ->
                        if (s.spatial) "Prism Spatial · ${(s.spatialAmount * 100).roundToInt()}%" else "Off"
                    },
                )
            }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) = update { copy(bandwidthKbps = bitrateEstimate / 1000) }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = update {
            copy(
                videoResolution = "${format.width}×${format.height}" + if (format.frameRate > 0) " @ ${format.frameRate.toInt()}fps" else "",
                videoCodec = format.codecs ?: format.sampleMimeType ?: "",
            )
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) =
            update { copy(droppedFrames = this.droppedFrames + droppedFrames) }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ): Unit {
            // Background scans back off while the sound is running dry (MixTempo.ease).
            MixTempo.lastUnderrunAt = SystemClock.elapsedRealtime()
            update { copy(underruns = underruns + 1) }
        }
    }

    // ------------------------------------------------------------ Playback diagnostics

    /** What the audio does, for [AudioDiagnostics] (only written while it's on). */
    private inner class DiagnosticsListener : AnalyticsListener {
        private fun at(t: AnalyticsListener.EventTime) = "at ${formatMs(t.currentPlaybackPositionMs)}"
        private fun formatMs(ms: Long) = if (ms < 0) "?" else "%d:%02d.%d".format(ms / 60_000, ms / 1000 % 60, ms / 100 % 10)
        private fun track(cfg: AudioSink.AudioTrackConfig) =
            "${AudioEffects.encodingName(cfg.encoding)} ${cfg.sampleRate} Hz, channel mask ${cfg.channelConfig}, buffer ${cfg.bufferSize} bytes" +
                (if (cfg.offload) ", offload" else "") + (if (cfg.tunneling) ", tunneling" else "")

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
            AudioDiagnostics.log("track", "made: ${track(audioTrackConfig)} ${at(eventTime)}; outputs ${AudioDiagnostics.devices(this@PlaybackService)}")

        override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
            AudioDiagnostics.log("track", "released: ${track(audioTrackConfig)} ${at(eventTime)}")

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
            AudioDiagnostics.log("underrun", "the output ran dry ${at(eventTime)}: buffer ${bufferSizeMs} ms, last fed ${elapsedSinceLastFeedMs} ms before")

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) =
            AudioDiagnostics.log("sink-error", "${audioSinkError.javaClass.simpleName}: ${audioSinkError.message} ${at(eventTime)}")

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) =
            AudioDiagnostics.log("codec-error", "${audioCodecError.javaClass.simpleName}: ${audioCodecError.message} ${at(eventTime)}")

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) =
            AudioDiagnostics.log("playing", "audio started coming out ${at(eventTime)}")

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) =
            AudioDiagnostics.log("format", "${format.codecs ?: format.sampleMimeType} ${format.sampleRate} Hz ${format.channelCount} ch, pcm ${format.pcmEncoding}")

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
            AudioDiagnostics.log("decoder", "$decoderName (${initializationDurationMs} ms to start)")

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) = AudioDiagnostics.log(
            "state",
            when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" } +
                " ${at(eventTime)}, ${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000} s loaded ahead",
        )

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) =
            AudioDiagnostics.log("state", (if (isPlaying) "playing" else "not playing (${player.playbackSuppressionReason.let { if (it == 0) "paused or waiting" else "suppressed: $it" }})") + " ${at(eventTime)}")

        // Why play/pause changed: a refused audio focus (Android 15+ refuses it to apps in the background without a
        // foreground service) shows up here as "audio focus loss" right after a play from another app (#187).
        override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) =
            AudioDiagnostics.log(
                "state",
                (if (playWhenReady) "play" else "pause") + " (" + when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "asked"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus loss"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "headphones unplugged"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of song"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed too long"
                    else -> "reason $reason"
                } + ") ${at(eventTime)}",
            )

        override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) =
            AudioDiagnostics.log(
                "song",
                if (mediaItem == null) "none (the queue is empty), reason $reason"
                else "${mediaItem.mediaMetadata.title} – ${mediaItem.mediaMetadata.artist} (${mediaItem.mediaId}), reason $reason",
            )

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) =
            AudioDiagnostics.log("error", "${error.errorCodeName}: ${error.message} ${at(eventTime)}")
    }
}

/** Normalization's level: songs are brought to this loudness (integrated LUFS, as Spotify and YouTube use). */
/** How YouTube says a song can't be played where the listener is (#93). */
private val UNAVAILABLE = listOf("not available", "unavailable", "isn't available", "in your country")

/**
 * Whether [error] (or what caused it) is YouTube saying the song isn't available, in the listener's country mostly
 * (#93). Not age-restricted or Premium-only songs: those need an account, not another recording.
 */
internal fun unavailableHere(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { e ->
        if (e is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException ||
            e is org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException) return@any false
        e is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException ||
            e.message?.let { m -> UNAVAILABLE.any { m.contains(it, ignoreCase = true) } } == true
    }

/** Wear OS watches' companion apps (also in the manifest's `<queries>`): their media controls lay buttons out by slot. */
private val WATCH_COMPANIONS = listOf("com.google.android.apps.wear.companion", "com.google.android.wearable.app")

/** How a stream refusal reads when only an account (age, Premium) or another network would help, not another recording. */
private val NEEDS_MORE_THAN_A_VIDEO = listOf("age-restricted", "Premium", "limiting playback", "sign in to confirm")

/**
 * Whether a song that failed may play from its music video's sound instead (#93, Reddit R2): YouTube says it isn't
 * available here ([unavailableHere]), its stream couldn't be got, or its server kept turning it away ([refused], after
 * the retries). Never for age-restricted or Premium-only songs or a blocked network: the video won't help there.
 */
internal fun wontPlayHere(error: Throwable, refused: Boolean = false): Boolean {
    val chain = generateSequence(error) { it.cause }.toList()
    if (chain.any { e ->
            e is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException ||
                e is org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException ||
                e.message?.let { m -> NEEDS_MORE_THAN_A_VIDEO.any { m.contains(it, ignoreCase = true) } } == true
        }) return false
    return unavailableHere(error) || refused || chain.any { it is com.prism.music.data.stream.StreamException }
}

/**
 * Whether [error] is the audio decoder failing or going away rather than the song's bytes: Android releases a background
 * app's codecs when another one needs them (1.7.9's night check). Unreadable bytes are a [androidx.media3.common.ParserException],
 * handled on their own; a format this phone can't decode has its own error codes and isn't retried.
 */
internal fun decoderLost(error: PlaybackException): Boolean =
    (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED || error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) &&
        generateSequence(error as Throwable) { it.cause }.none { it is androidx.media3.common.ParserException }

private const val TARGET_LUFS = -14f
/** The extra Media3 puts on the media button it sends when its notification is swiped away. */
private const val NOTIFICATION_DISMISSED = "androidx.media3.session.NOTIFICATION_DISMISSED_EVENT_KEY"
/** "Fade on pause and play" (A5): 8 steps of 30 ms. */
private const val PAUSE_FADE_STEPS = 8
private const val PAUSE_FADE_STEP_MS = 30L

/** How far a music video's length may be from its song's for its sound to play in the song's place (#93, Reddit R1). */
private const val SOUND_FROM_SLACK_SEC = 8

/** A song's volume (normalization) can still be set this far into it; after that it stays put for the song. */
private const val SETTLE_MS = 2_000L
/** A song already playing only has its volume changed for a better loudness figure this much apart (dB). */
private const val MIN_MIDSONG_CHANGE_DB = 1.5f
/** Queued songs are got ready once the playing one has played this long (not as it starts: that slowed its loading). */
private const val UNDER_WAY_MS = 5_000L
/**
 * How long after switching to a music video its measured offset may still move it to the matching moment (#203);
 * later the video stays where it is and only the lyrics follow the offset.
 */
private const val LATE_VIDEO_SEEK_MS = 3_000L

/** How far what plays may run from the song's own length before diagnostics note it ([PlaybackService.checkLength]). */
private const val LENGTH_MISMATCH_SEC = 8

/** Songs heard this recently aren't added by radio or autoplay again (1.7.9, Reddit: "the same songs over and over"). */
private const val RADIO_REST_MS = 4 * 3_600_000L
/** How long after a spoken request found its songs they may still come in and start playing. */
private const val VOICE_START_MS = 10_000L
/** How many songs ahead DJ mode scans and orders (a quick scan each: three stretches of the song, a few at once). */
private const val DJ_WINDOW = 40
/** How long DJ mode waits for more scans before putting the queue in order again. */
private const val RESEQUENCE_GAP_MS = 1_500L
/** How many songs ahead (in play order) Smart Shuffle places its picks among. */
private const val SMART_LOOK_AHEAD = 12
/** How many queued songs ahead are got ready (their loudness and edges). */
private const val QUEUE_AHEAD = 3
/** How far ahead the player loads: a whole song (and the start of the next, near its end). */
private const val WHOLE_SONG_MS = 300_000

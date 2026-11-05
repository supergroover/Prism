package com.prism.music

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.prism.music.data.LibraryRepository
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.meta.MetaRepository
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.stream.ResolvedStream
import com.prism.music.data.stream.StreamResolver
import com.prism.music.data.stream.YouTubeStreamInterceptor
import com.prism.music.download.DownloadRepository
import com.prism.music.playback.NerdStats
import com.prism.music.playback.PlayerConnection
import com.prism.music.playback.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** About 10 seconds of a song's stream (Opus ~160 kbps, AAC up to 256 kbps), saved ahead by [AppContainer.warmUp]. */
private const val PREBUFFER_BYTES = 320L * 1024
private const val YIELD_MS = 8_000L

class PrismApp : Application(), coil3.SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /**
     * Every image in Prism goes through here: covers saved on the phone are shown from their files (offline too, #54),
     * and data saver asks for smaller pictures on mobile data (#40). The rest is Coil's usual memory and disk caching.
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .components {
                // First, so a cover kept on the phone is trimmed the same as one from the network (#92).
                add(com.prism.music.data.VideoCoverInterceptor { ::container.isInitialized && container.settings.current.squareVideoCovers })
                add(com.prism.music.data.OfflineArtInterceptor { if (::container.isInitialized) container.artwork else null })
                add(com.prism.music.data.DataSaverInterceptor { ::container.isInitialized && container.dataSaving() })
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(File(context.cacheDir, "image_cache").absolutePath.toPath())
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    /** The language Prism started in: a change of it from outside Prism restarts Prism ([onConfigurationChanged]). */
    private var language = ""

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Menus' labels and other text read once would stay in the old language (seen after a change in the phone's
        // Settings → Apps → Prism → Language): Prism starts again in the new one, as its own Language page does.
        if (AppLanguage.changed(this, language, newConfig)) AppLanguage.restart(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything shows a word: Prism's text in the listener's language (Strings.kt).
        Strings.resources = resources
        language = AppLanguage.shown(this)
        // A crash's details kept on the phone (Settings → About → Diagnostics), from the very start.
        CrashLog.install(this)
        // A restore from a backup goes in before anything opens the files it replaces.
        com.prism.music.data.Backup.applyPending(this)
        // Before any icon is built: they're drawn in the style picked in Settings → Appearance → Icons.
        com.prism.music.ui.theme.IconStyle.load(this)
        com.prism.music.ui.theme.AppFonts.init(this)
        container = AppContainer(this)
        // Android's own backup restored onto another install: its sign-ins aren't this phone's (RestoredInstall).
        runCatching {
            com.prism.music.data.RestoredInstall.check(this) {
                // Signed out as Settings does: the account's library and Home kept for offline, and the sign-in page's
                // cookies (Android's backup leaves those out now, but an older backup may have brought them).
                container.settings.logout()
                runCatching { container.library.forgetAccount() }
                runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
                runCatching { container.lastFm.signOut() }
                runCatching { container.discord.signOut() }
            }
        }
        // Whether any of Prism's screens is showing: what Android 12+ allows depends on it (DownloadRepository).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; container.visible.value = true }
            override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0); container.visible.value = started > 0 }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        runCatching { com.prism.music.data.Notifications.createChannels(this) }
        // Long-press the app icon: Search, Recognize a song, Liked songs, Downloads (Reddit 1.7).
        // Off the main thread: it talks to the launcher's service and loads their icons, and start-up shouldn't wait.
        Thread({ runCatching { com.prism.music.AppShortcuts.publish(this) } }, "PrismShortcuts").start()
        // For you (1.7): Home gets its sections once, and Release Radar looks round every few days.
        container.settings.addNewHomeSections()
        // The scheduled jobs are set up off the main thread: WorkManager opens its own database the first time, and on a
        // slow phone start-up shouldn't wait for it (GitHub #103: a budget phone's Prism kept restarting).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.prism.music.data.foryou.ReleaseRadar.schedule(this@PrismApp) }
            // Rewind: the 1 December notification, and last year kept once it's over (after start-up has settled).
            runCatching { com.prism.music.data.Rewinds.schedule(this@PrismApp) }
            // DJ mode: the songs played most and liked get their key read once a day, on Wi-Fi while charging.
            runCatching { com.prism.music.playback.DjPrescanWorker.schedule(this@PrismApp) }
            // Bring your history: the daily look at whether a fresh Spotify or Takeout download is due (only once one
            // was imported; a restored backup brings the dates with it).
            runCatching { com.prism.music.data.imports.ImportReminders.schedule(this@PrismApp) }
        }
        // YouTube Music (live), 1.8.1: its job only while signed in and turned on; signing out stops it (read from its
        // preferences, so nothing is built for the many who never turn it on).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var was: Boolean? = null
            container.settings.flow.map { it.isLoggedIn }.distinctUntilChanged().collect { signedIn ->
                runCatching {
                    com.prism.music.data.imports.YouTubeLive.signInChanged(
                        this@PrismApp, signedIn, was, com.prism.music.data.imports.YouTubeLiveWorker.Jobs(this@PrismApp),
                    )
                }
                was = signedIn
            }
        }
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(45_000)
            container.quietNetwork()
            runCatching { container.rewind.freezeLastYear() }
        }
        // Scrobbles made offline go to Last.fm once the phone is back online (#133).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!container.lastFm.available) return@launch
            container.network.online.collect { online -> if (online && container.lastFm.user.value != null) runCatching { container.lastFm.flush() } }
        }
        // A restored backup's settings (since 1.7.8 kept as values beside the store) go in at once, before Home shows.
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.backup.applyRestoredSettings() } }
        // Downloads a restore left waiting carry on once the first screen is up (the download manager's calls go on the
        // main thread).
        container.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.delay(1_500)
            runCatching { container.backup.resumeDownloads() }
        }
        // Diagnostics also watch the screen's thread (1.7.4, #100): work that holds it up long enough to drop frames is
        // logged with where it was. The test copy (".dev") also says so in logcat (PrismStall).
        com.prism.music.playback.AudioDiagnostics.init(this)
        // How the last run ended, when it wasn't a Java crash (killed for memory, ANR, native crash: #103), off the
        // main thread (an ANR's trace can be large).
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { CrashLog.recordExits(this@PrismApp) } }
        com.prism.music.playback.MainThreadWatch.toLogcat = BuildConfig.APPLICATION_ID.endsWith(".dev")
        container.scope.launch {
            container.settings.flow.map { it.audioDiagnostics }.distinctUntilChanged().collectLatest { on ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.prism.music.playback.MainThreadWatch.setEnabled(on) }
                // And what Prism's memory is made of, every half minute (#103/#134: the phone closed Prism with gigabytes
                // in use, and the kind that grew says where to look).
                if (on) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    while (true) {
                        runCatching { com.prism.music.playback.AudioDiagnostics.log("memory", MemoryLine.now()) }
                        kotlinx.coroutines.delay(30_000)
                    }
                }
            }
        }
        // A lyrics save into a music file that Prism closing cut short is put right (#16).
        if (java.io.File(filesDir, "lyrics-tags").list()?.isNotEmpty() == true) {
            container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.lyricsToFiles.recover() } }
        }
        // Debug builds: song ids pushed to …/files/scan_list.txt are scanned (tempo, loudness, key) and logged under
        // PrismMix, for tuning the analysis on real songs (with mix_dump there too, their decoded audio is kept).
        if (BuildConfig.DEBUG) getExternalFilesDir(null)?.let { java.io.File(it, "scan_list.txt") }?.takeIf { it.isFile }?.let { list ->
            container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                for (id in list.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
                    runCatching { container.mixTempo.measure(id, network = true, edges = true, key = true) }
                }
                android.util.Log.i("PrismMix", "scan list done")
                list.delete()
            }
        }
        // Debug builds: renderers by description address, one per line in …/files/connect_renderer.txt, for casting to a
        // pretend one on a PC through adb (SSDP doesn't cross it); see SimulatedRendererTest.castFromThePhone. A
        // "googlecast://host:port" line adds a Google Cast device the same way (GoogleCastTest.castFromThePhone).
        if (BuildConfig.DEBUG) getExternalFilesDir(null)?.let { java.io.File(it, "connect_renderer.txt") }?.takeIf { it.isFile }?.let { f ->
            f.readLines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("googlecast://") }.forEach { container.cast.addByLocation(it) }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as PrismApp).container

/** One line of what this process's memory is made of, for diagnostics (in MB, as Android's own meminfo counts it). */
internal object MemoryLine {
    fun now(): String {
        val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        fun mb(stat: String) = (mi.getMemoryStat(stat)?.toLongOrNull() ?: 0L) / 1024
        val threads = java.io.File("/proc/self/task").list()?.size ?: Thread.activeCount()
        val rt = Runtime.getRuntime()
        return "total ${mb("summary.total-pss")} MB (Java ${mb("summary.java-heap")}, native ${mb("summary.native-heap")}, " +
            "graphics ${mb("summary.graphics")}, code ${mb("summary.code")}, other ${mb("summary.private-other")}, swapped ${mb("summary.total-swap")}); " +
            "Java heap ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB; $threads threads"
    }
}

/** Hand-rolled dependency graph; everything lives for the app process. */
@OptIn(UnstableApi::class)
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One of Prism's screens is showing (an activity is started); set by [PrismApp]. */
    val visible = MutableStateFlow(false)

    /**
     * Shows Android's notification prompt (set by the activity on screen). It isn't asked at start-up any more, with
     * nothing on screen to say why: the tour's "Stay in the loop" step asks, or else the first download does.
     */
    @Volatile var notificationPrompt: (() -> Unit)? = null

    /**
     * Whether Prism had been set up before this run (welcome screen done, or signed in): the tour greets a new listener
     * and someone updating differently. Set once, when the activity first starts.
     */
    @Volatile var returningUser: Boolean? = null

    /** Settings → About → "Take the tour": the activity shows the whole tour. */
    val tourRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Asks for notifications once ever, when Prism may not post them yet and is on screen to ask. */
    fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33 || com.prism.music.data.Notifications.allowed(app)) return
        val prefs = app.getSharedPreferences("prompts", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("notifications_asked", false)) return
        val prompt = notificationPrompt ?: return
        prefs.edit().putBoolean("notifications_asked", true).apply()
        prompt()
    }
    val settings = SettingsRepository(app, scope)
    /** Looks (1.7.3): saved, Prism's own and shared looks, Undo for look changes, the day / night schedule. */
    val looks = com.prism.music.data.looks.LookStore(app, settings, scope)
    /** Looks people shared on GitHub (themes/ in Prism's repository), fetched only when the listener opens the list. */
    val lookGallery by lazy { com.prism.music.data.looks.LookGallery(http, java.io.File(app.filesDir, "looks"), { settings.lookKinds }) }
    /** "Customize this" (1.7.3): with it on, the mini player, tab bar, player and Home's sections open their own settings. */
    val customizing = MutableStateFlow(false)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply { TestHooks.network?.let(::addInterceptor) }
        .build()
    private val streamHttp: OkHttpClient = http.newBuilder()
        .addInterceptor(YouTubeStreamInterceptor(http))
        .build()

    val innerTube = InnerTube(http, { settings.current }, settings::setVisitorData)
    val ytm = YouTubeMusic(
        innerTube,
        songsEverywhere = { settings.current.let { it.radioSongsOnly && it.songsEverywhere } },
        onPairs = { queue.counterparts.putAll(it) },
    )
    val db = PrismDatabase.build(app)
    /** Versions of songs the listener picked to hear instead (1.7.9, "Play a different version"). */
    val versions = com.prism.music.data.SongVersions(app.getSharedPreferences("song_versions", android.content.Context.MODE_PRIVATE))
    val streams by lazy { StreamResolver(app, http, settings, innerTube).apply { versionOf = versions::of } }
    val translator = com.prism.music.data.lyrics.LyricsTranslator(http, File(app.filesDir, "translations"))
    val network = com.prism.music.data.NetworkState(app)
    /** Covers kept on the phone for downloads and the library, so they show offline. */
    val artwork = com.prism.music.data.ArtworkStore(app, http) { quietNetwork() }
    /** The library's lists and opened playlists and albums, kept so they open offline. */
    val offline = com.prism.music.data.OfflineLibrary(app)
    /** The last Home, shown at once at the next start while the new one loads (#126). */
    val homeCache = com.prism.music.data.HomeCache(app.filesDir)
    /** Albums and playlists played lately (Home's Recently played, #196). */
    val recentCollections by lazy { com.prism.music.data.RecentCollections(app) }
    /** Last.fm scrobbling, when the listener connects their account (#133). */
    val lastFm by lazy { com.prism.music.data.lastfm.LastFm(app, http, scope) }
    val lyrics = LyricsRepository(http, db.lyrics(), ytm) { settings.current }.apply {
        choices = app.getSharedPreferences("lyrics_choice", android.content.Context.MODE_PRIVATE)
        searchNames = app.getSharedPreferences("lyrics_search_as", android.content.Context.MODE_PRIVATE)
        wordShifts = app.getSharedPreferences("lyrics_word_shift", android.content.Context.MODE_PRIVATE)
    }
    /** Search by a line of the lyrics, from the box itself (1.7.6). */
    val lyricSearch = com.prism.music.data.lyrics.LyricSearch(http, ytm, db.lyricsSearch(), db.songs(), lyrics::textLines)
    val meta = MetaRepository(http, db.meta())
    val canvas = com.prism.music.data.canvas.CanvasRepository(http).apply {
        hiddenPrefs = app.getSharedPreferences("canvas_hidden", android.content.Context.MODE_PRIVATE)
        savedPrefs = app.getSharedPreferences("canvas_saved", android.content.Context.MODE_PRIVATE)
        useIndex(File(app.filesDir, "canvas_index.json"))
    }
    val videoSync by lazy { com.prism.music.playback.VideoSync(app, streamHttp, streams, scope) }
    /** Songs' tempo and edges, for Mix, and their loudness, for normalization. */
    val mixTempo by lazy {
        // Phones with few cores (or little memory, or Performance mode on): scans make way while the listener scrolls (#343).
        val slow = com.prism.music.playback.MixTempo.isSlowPhone(Runtime.getRuntime().availableProcessors(), com.prism.music.data.DevicePower.mayBeSlow(app), false)
        com.prism.music.playback.MixTempo.slowPhone = { slow || settings.current.performanceMode }
        com.prism.music.playback.MixTempo(app, streamHttp, streams, ::audioOnPhone, { yieldToPlaying(null) }, { player.isPlaying.value }, waves)
    }
    /** Songs' loudness outlines, for the waveform progress bar (made by the song scan). */
    val waves by lazy { com.prism.music.data.SongWaves(app) }

    /**
     * Where a song's audio is on the phone, for scanning it without the network: a local file as it is; a download or
     * a song cached whole, copied out of its cache into [scratch]. Null when it isn't all here.
     */
    internal fun audioOnPhone(id: String, scratch: File): android.net.Uri? {
        if (id.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX)) return localMusic.uriFor(id)
        for ((cache, key) in listOf(downloadCache to id, playerCache to songCacheKey(id))) {
            if (!cache.fullyCached(key, 0, C.LENGTH_UNSET.toLong())) continue
            val ok = runCatching {
                val source = CacheDataSource(cache, null)
                source.open(DataSpec.Builder().setUri("prism://audio/$id").setKey(key).build())
                try {
                    scratch.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = source.read(buf, 0, buf.size)
                            if (n == C.RESULT_END_OF_INPUT) break
                            out.write(buf, 0, n)
                        }
                    }
                } finally { source.close() }
            }.isSuccess
            if (ok) return android.net.Uri.fromFile(scratch)
        }
        return null
    }
    /** Per-song lyric timing nudges, in ms (positive = lyrics earlier). Keyed by song id, plus ":video" in video mode. */
    /** YouTube's loudness figure per song (dB from its reference level), kept for offline normalization. */
    val loudness: android.content.SharedPreferences = app.getSharedPreferences("loudness", android.content.Context.MODE_PRIVATE)
    val lyricOffsets: android.content.SharedPreferences = app.getSharedPreferences("lyric_offsets", android.content.Context.MODE_PRIVATE)

    init {
        // Misses are cheap to re-check and may have been wrong (e.g. a provider was down).
        scope.launch(Dispatchers.IO) { runCatching { db.lyrics().clearMisses() } }
    }
    val queue = QueueState()
    val nerdStats = MutableStateFlow(NerdStats())
    /** video id -> (source label, resolved stream) for the stats overlay */
    val streamDetails = ConcurrentHashMap<String, Pair<String, ResolvedStream?>>()
    @Volatile var onStreamResolved: ((String) -> Unit)? = null

    val databaseProvider by lazy { StandaloneDatabaseProvider(app) }
    /** Bytes Smart Downloads' picks take up (kept current by [DownloadRepository]); the song cache has the rest of its space. */
    @Volatile var smartPickBytes = 0L
    /** The song cache keeps within Smart Downloads' space, less what its picks use. */
    val cacheEvictor by lazy {
        com.prism.music.download.SmartCacheEvictor(
            maxBytes = { (settings.current.offlineGb * 1_073_741_824.0).toLong() - smartPickBytes },
            favoured = { id -> runCatching { id in library.likedIds.value }.getOrDefault(false) },
        )
    }
    val playerCache: SimpleCache by lazy {
        SimpleCache(File(app.cacheDir, "song_cache"), cacheEvictor, databaseProvider)
    }
    /** Shrinks the song cache to fit, after Smart Downloads' space or its picks changed. */
    fun trimSongCache() { scope.launch(Dispatchers.IO) { runCatching { cacheEvictor.trim(playerCache) } } }
    val downloadCache: SimpleCache by lazy {
        SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)
    }

    val artistPrefs = com.prism.music.data.ArtistPrefs(app)
    /** The color picker's recent and starred colors. */
    val savedColors = com.prism.music.data.prefs.SavedColors(app)
    /** Where each podcast episode was left off. */
    val episodes = com.prism.music.data.EpisodeProgress(app)
    val library = LibraryRepository(this)
    /** Liked songs mirrored into a real YouTube Music playlist, when the listener turns that on. */
    val likedPlaylist = com.prism.music.data.LikedPlaylist(this)
    val localLossless = com.prism.music.data.local.LocalLossless(app)
    /** Library → Local music: the phone's own music files. */
    val localMusic = com.prism.music.data.local.LocalMusic(app, scope).also { it.start() }
    val losslessSync by lazy { com.prism.music.download.LosslessSync(app, this) }
    // Started only after localLossless exists: a launch from an earlier init block can run on a
    // background thread before this property is assigned.
    init { losslessSync.start() }
    val taste = com.prism.music.data.TasteRepository(this)
    /** Big data files downloaded on first use (the Japanese reading dictionary, Sing's model). */
    val models = com.prism.music.data.ModelStore(app, http)
    /** Playlists and saved albums kept on the phone (no account needed). */
    val prismLibrary = com.prism.music.data.PrismLibrary(java.io.File(app.filesDir, "library"))
    /** The listener's Prism Account: a name and picture kept on this phone ([com.prism.music.data.PrismAccount]). */
    val account by lazy { com.prism.music.data.PrismAccount(this) }

    /**
     * What Prism calls the listener: their Prism Account's name, else their YouTube Music account's, else "".
     * [first]: just the first name ("Sam's Phone").
     */
    fun displayName(first: Boolean = false): String {
        val name = account.profile.value?.name?.takeIf { it.isNotBlank() } ?: settings.current.accountName
        return if (first) name.trim().substringBefore(' ') else name.trim()
    }
    /** Lyrics in Latin letters (Korean, Japanese, Chinese, Arabic, Hindi…). */
    val romanizer = com.prism.music.data.lyrics.Romanizer(
        http, db.romanized(), models, scope, app.getSharedPreferences("romanize_songs", android.content.Context.MODE_PRIVATE),
    )
    /** Songs' best parts, from YouTube's "Most replayed" curves (and the chorus in the lyrics). */
    val bestParts = com.prism.music.data.BestParts(this)

    /** One-off upkeep that runs once per install, in the background, after Prism has settled. */
    private val migrations: android.content.SharedPreferences = app.getSharedPreferences("migrations", android.content.Context.MODE_PRIVATE)
    init {
        // Genre detection picked the wrong artist when several share a name (and now finds second
        // genres), so saved genres are looked up again once. Retried on a later launch if offline.
        if (!migrations.getBoolean("genres_refreshed_1", false)) scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(15_000)
            val songs = runCatching {
                (db.songs().liked() + db.songs().recentFlow(300).first()).map { it.toSong() }.distinctBy { it.id }
            }.getOrNull() ?: return@launch
            if (meta.refreshAll(songs)) migrations.edit().putBoolean("genres_refreshed_1", true).apply()
        }
        // Songs' genres come from iTunes (each song's own, by its artist's songs there): the refiner asks for one artist
        // at a time in the background, and the artists of the listener's own music are queued once, most played first.
        meta.startRefining(scope)
        if (!migrations.getBoolean("genres_itunes_1", false)) scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(20_000)
            val now = System.currentTimeMillis()
            val played = runCatching { db.plays().topArtists(now - 365L * 86_400_000, now, 200).map { it.artistName } }.getOrDefault(emptyList())
            val mine = runCatching { (db.songs().liked() + db.songs().recentFlow(300).first()).map { it.toSong() } }.getOrDefault(emptyList())
                .filter { !it.isLocal && !it.isEpisode }.distinctBy { it.id }.groupBy { it.primaryArtist }
            (played + mine.keys).filter { it.isNotBlank() }.distinct().forEach { meta.refineLater(it, mine[it].orEmpty()) }
            migrations.edit().putBoolean("genres_itunes_1", true).apply()
        }
        // GitHub #182: bringing the library over had starred every library song's artist; those come off once, unless the
        // account follows them. Retried on a later launch when the account's follows couldn't be asked for.
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(12_000)
            if (artistPrefs.starsRepaired) return@launch
            val signedIn = settings.current.isLoggedIn
            val followed = if (signedIn) runCatching { ytm.librarySubscriptions().map { it.id }.toSet() }.getOrNull() else null
            artistPrefs.repairBroughtStars(followed, signedIn)
        }
    }
    val downloads by lazy { DownloadRepository(app, this, scope) }

    /**
     * What's downloaded, for screens: empty until [downloads] has started, then kept in step by it. Screens read these
     * rather than [downloads] itself, which reads the download index and cache from disk when first touched (1.7.8's
     * check: Home's first frame waited 3.4 s for it on a slow phone). [downloads] starts in the background at launch.
     */
    val downloadState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.prism.music.download.DownloadInfo>>(emptyMap())
    val videoState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.prism.music.download.VideoDownload>>(emptyMap())
    /** Music videos downloaded as their song, video id -> song id ([com.prism.music.download.forRow], #315). */
    val downloadStandIns = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val covers by lazy { com.prism.music.data.PlaylistCovers(app) }
    // Read at start, so the songs' own covers (#305) are there for the media session and Android Auto from the first song.
    init { covers }
    /** Saves lyrics into your own music files' tags (#16). */
    val lyricsToFiles by lazy { com.prism.music.data.local.LyricsToFiles(app, this) }
    val updates by lazy { com.prism.music.data.UpdateChecker(app, http, scope) }
    val backup by lazy { com.prism.music.data.Backup(this) }
    val player = PlayerConnection(app, this)
    @Volatile private var songStartedAt = 0L
    init { scope.launch { player.currentSong.collect { songStartedAt = android.os.SystemClock.elapsedRealtime() } } }

    /**
     * Waits until the network is free for background saving (offline copies of playlists, covers, download retries):
     * not while the music is buffering, nor in a song's first 20 s, when its stream is loading. Since 1.6.8 that saving
     * ran at every launch and sync, and on slower connections the music stalled behind it.
     */
    suspend fun quietNetwork() {
        while (player.buffering.value ||
            (player.isPlaying.value && android.os.SystemClock.elapsedRealtime() - songStartedAt < 20_000)
        ) kotlinx.coroutines.delay(2_000)
    }

    /** Sing: karaoke with the song's own backing (vocals separated on the phone). */
    val sing by lazy { com.prism.music.playback.sing.Sing(this) }
    val dj = com.prism.music.playback.DjMode()
    val exclusions by lazy { com.prism.music.data.foryou.Exclusions(app) }
    val forYou by lazy { com.prism.music.data.foryou.ForYou(this) }
    /** What the listener made of Prism's recommendations: served, skipped, heard through (1.7.9). */
    val recFeedback by lazy { com.prism.music.data.foryou.RecFeedback(app) }
    val releaseRadar by lazy { com.prism.music.data.foryou.ReleaseRadar(this) }
    val blends by lazy { com.prism.music.data.blend.Blends(this) }
    /** Prism's radio and Home's lists and mixes, from the listener's own music on the phone. */
    val yourMusic by lazy { com.prism.music.data.foryou.YourMusic(this) }
    /** Prism's holiday mixes, Thanksgiving and Christmas (1.8.0), made when first opened in their season, or ahead on Wi-Fi. */
    val seasonal by lazy { com.prism.music.data.seasonal.SeasonalMixes(this) }
    /** Sends a Blend's other half back over Wi-Fi, so both phones have it ([com.prism.music.data.blend.BlendExchange]). */
    val blendExchange by lazy { com.prism.music.data.blend.BlendExchange({ code, mine -> blends.import(code, mine) }, { blends.myName }) }
    val recognizer by lazy { com.prism.music.data.recognize.Recognizer(this) }

    /** Headphone correction (AutoEq profiles per headphones). */
    /** Crossfade's plan and progress, for the player's mark and blended covers (1.7.8, A6). */
    val mixState = com.prism.music.playback.MixState()
    /** Your own equalizer presets, and the one picked for each output (1.7.8, A3). */
    val eqPresets by lazy { com.prism.music.data.prefs.EqPresetStore(app) }
    val headphoneEq by lazy { com.prism.music.data.HeadphoneEq(app, http, scope, player.preferredOutput) }

    /** "Listening to Prism" on the listener's Discord profile, when they've turned it on (Settings → Discord). */
    val discord = com.prism.music.playback.DiscordPresence(app, http, scope)
    init {
        scope.launch {
            settings.flow.collect { s -> discord.setEnabled(s.discordPresence && discord.signedIn) }
        }
    }

    /** Listen Together sessions on the local network. */
    val together by lazy { com.prism.music.playback.together.Together(app, this) }

    /** Prism Connect: speakers and TVs on the network (UPnP / DLNA). */
    val cast by lazy { com.prism.music.connect.Cast(app, this) }
    private val webRemoteMade = lazy { com.prism.music.connect.WebRemote(app, this) }
    /** Prism Connect: Prism played from a browser on the same Wi-Fi (Settings → Prism Connect). */
    val webRemote by webRemoteMade
    /** Prism on a TV: this phone's own copy handed to a TV on the Wi-Fi while its settings group is switched on. */
    val tvInstall by lazy { com.prism.music.connect.TvInstall { com.prism.music.connect.TvInstall.installedApk(app.applicationInfo) } }
    /** Rewind: each year in music (Replay → a year). */
    val rewind by lazy { com.prism.music.data.Rewinds(this) }
    /** A year's Rewind to open (Home's Rewind card, the 1 December notification): Replay shows it and clears this. */
    val rewindRequest = MutableStateFlow<Int?>(null)
    /**
     * Bring your history (1.8.1): plays and libraries from Spotify, Google Takeout and Last.fm. Replay counts its
     * plays through [com.prism.music.data.imports.HistoryImports.replay], the mixes through its `taste`.
     */
    val imports by lazy { com.prism.music.data.imports.HistoryImports(this) }

    /**
     * The optional Spotify connection (1.8.1): Spotify's official Web API through the listener's own developer app.
     * Made when first asked (Settings, the mixes' taste); never connected, it only reads its own preferences.
     */
    val spotify by lazy { com.prism.music.data.spotify.Spotify(this) }

    /** Prism Connect: playback moving between Prism devices on the same Wi-Fi. */
    val handoff by lazy { com.prism.music.connect.Handoff(app, this) }
    init {
        scope.launch {
            settings.flow.map { it.webRemote }.distinctUntilChanged().collect { on ->
                if (on) webRemote.setEnabled(true) else if (webRemoteMade.isInitialized()) webRemote.setEnabled(false)
            }
        }
        scope.launch {
            // A TV is always reachable for the phones paired with it (1.8.0: the phone as its remote); it takes new
            // pairings only while its pairing screen is open.
            settings.flow.map { it.handoffAvailable }.distinctUntilChanged().collect { on -> handoff.setAvailable(on || handoff.isTv) }
        }
        // White light / Spectrum: the home-screen widgets are drawn again to match.
        scope.launch {
            // Your light too (1.7.7): the widgets take its colors on Android 12+.
            settings.flow.map { listOf(it.lightStyle, it.spectrumPreset, it.spectrumStops, it.spectrumBands) }.distinctUntilChanged().drop(1).collect {
                com.prism.music.widget.Widgets.refresh(app)
                com.prism.music.widget.RecognizeWidgets.refresh(app)
            }
        }
    }

    /** Data saver is on and the phone is on mobile data (#40): smaller pictures, no artist photos or animated covers. */
    fun dataSaving(): Boolean = settings.current.dataSaver && network.metered.value

    /** Whether a song plays without the network: a file on the phone, a finished download, or a song cached whole. */
    fun availableOffline(id: String): Boolean =
        id.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX) || downloads.isDownloaded(id) ||
            runCatching { playerCache.fullyCached(songCacheKey(id), 0, C.LENGTH_UNSET.toLong()) }.getOrDefault(false)

    private fun Cache.fullyCached(key: String, position: Long, length: Long): Boolean {
        val total = ContentMetadata.getContentLength(getContentMetadata(key))
        if (total <= 0) return false
        val len = if (length == C.LENGTH_UNSET.toLong()) total - position else length
        return len <= 0 || isCached(key, position, len)
    }

    /**
     * Songs YouTube won't play where the listener is, and the music video whose sound plays in their place (#93: the
     * video was allowed when its song wasn't). Set by the player when a song fails as unavailable; for this run only.
     */
    val soundFrom = ConcurrentHashMap<String, String>()

    /**
     * The song cache's key for song [id]'s sound: a picked version ([versions]) is kept under that recording's own id, so
     * the two recordings' bytes never mix. They shared the song's key before, cleared at each pick while the player was
     * still saving the one it had been playing: what it saved after went in under the song, and back on its own
     * recording the song couldn't be read and stopped playing (1.7.9's check, "Woke Up Older").
     */
    fun songCacheKey(id: String): String = versions.of(id) ?: id

    /**
     * Plays [pick] in [song]'s place from now on (null: its own sound again). The version's measurements go, and a
     * download comes down again as the new one (each recording keeps its own cached part, [songCacheKey]). Playing now,
     * the song starts again where it was.
     */
    fun pickVersion(song: com.prism.music.data.model.Song, pick: com.prism.music.data.SongVersions.Pick?) {
        if (versions.of(song.id) == pick?.id?.takeIf { it != song.id }) return
        versions.set(song.id, pick)
        soundFrom.remove(song.id)
        com.prism.music.playback.AudioDiagnostics.log("song", "${song.id} plays as ${pick?.id ?: "itself"} from now on (picked by the listener)")
        scope.launch(Dispatchers.Main) {
            // #328: the queue's copies of it show the version (or the song again) in the notification, the car and widgets.
            player.refreshCovers { it.id == song.id }
            player.reloadIfCurrent(song.id) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    mixTempo.forget(song.id)
                    waves.forget(song.id)
                    downloads.redownload(song.id, unfinished = true)
                }
            }
        }
    }

    private fun resolveAudio(spec: DataSpec, useCaches: Boolean): DataSpec {
        val uri = spec.uri
        if (uri.scheme != "prism") return spec
        val song = uri.lastPathSegment ?: return spec
        // Played (and cached) as the video it plays from, so its bytes never mix with the song's own. Downloads keep
        // the song's own key, so they aren't swapped.
        val sound = if (useCaches) soundFrom[song] else null
        // A picked version is cached under its own id too ([songCacheKey]); its download is the song's (fetched again
        // as the version when it was picked).
        val id = sound ?: if (useCaches) songCacheKey(song) else song
        if (useCaches) {
            val saved = sound ?: song
            if (downloadCache.fullyCached(saved, spec.position, spec.length)) {
                streamDetails[song] = "Downloaded" to streamDetails[song]?.second
                onStreamResolved?.invoke(song)
                return spec.buildUpon().setKey(saved).build()
            }
            if (playerCache.fullyCached(id, spec.position, spec.length)) {
                streamDetails[song] = "Cached" to streamDetails[song]?.second
                onStreamResolved?.invoke(song)
                return spec.buildUpon().setKey(STREAMED + id).build()
            }
        }
        // The listener's own upload (#101): only their account may play it, so it's asked for that way at once instead of
        // after two refusals.
        if (uri.getQueryParameter("upload") == "1") streams.forAccount(sound ?: song)
        val s = try {
            // The song's id, not its version's: the resolver plays the version for it.
            streams.resolve(sound ?: song)
        } catch (e: Exception) {
            throw IOException(e.message ?: tr(R.string.couldn_t_resolve_stream), e)
        }
        // The song's start was saved ahead from another format (the network changed since, so another quality
        // was picked): bytes of two formats can't be stitched together, so that start goes.
        prebuffered.remove(id)?.let { itag -> if (itag != s.itag) runCatching { playerCache.removeResource(id) } }
        if (useCaches) cacheFormat(id, s.itag) else downloadFormat(id, s.itag, spec.position)
        streamDetails[song] = (if (useCaches) "Streaming" else "Downloading") to s
        if (useCaches) onStreamResolved?.invoke(song)
        return spec.buildUpon().setUri(s.audioUrl).setKey(if (useCaches) STREAMED + id else id).build()
    }

    /** [id] is downloaded whole, so the player reads it from Downloads ([resolveAudio]). */
    fun downloadedWhole(id: String): Boolean = runCatching { downloadCache.fullyCached(id, 0, C.LENGTH_UNSET.toLong()) }.getOrDefault(false)

    /** The format (itag) each download's bytes are in so far, kept so a later part never comes from another one. */
    private val downloadFormats = app.getSharedPreferences("download_formats", android.content.Context.MODE_PRIVATE)

    /**
     * A download that stopped part-way and starts again later can be given another format (the account's link was
     * refused and the next came from elsewhere, or the network changed the quality). Its two parts were stitched
     * into one file nothing could read: GitHub #183, "Skipping atom with length > 2147483647" from Downloads while the
     * song streamed fine. When the format changed, what was saved goes and the download starts over.
     */
    private fun downloadFormat(id: String, itag: Int, position: Long) {
        val before = downloadFormats.getInt(id, 0)
        if (before != 0 && before != itag && (position > 0 || downloadCache.getCachedBytes(id, 0, Long.MAX_VALUE) > 0)) {
            runCatching { downloadCache.removeResource(id) }
            downloadFormats.edit().putInt(id, itag).apply()
            com.prism.music.playback.AudioDiagnostics.log("download", "$id came as format $itag, not $before as before: starting it over")
            throw IOException(tr(R.string.the_song_s_format_changed_part))
        }
        if (before != itag) downloadFormats.edit().putInt(id, itag).apply()
    }

    /** The format (itag) each song's cached part came in (1.7.8), so a later part never comes from another one. */
    private val cacheFormats = app.getSharedPreferences("cache_formats", android.content.Context.MODE_PRIVATE)

    /**
     * The song cache's side of [downloadFormat] (Reddit, 1.7.7: "Element 134 must be in a TrackEntry" on one song every
     * time): what's cached of [id] from another format goes before today's stream adds to it. Before 1.7.8 nothing was
     * recorded; such a song is caught when it can't be read ([forgetCachedAudio] from the player).
     */
    private fun cacheFormat(id: String, itag: Int) {
        val before = cacheFormats.getInt(id, 0)
        if (before == itag) return
        if (before != 0 && playerCache.getCachedBytes(id, 0, Long.MAX_VALUE) > 0) {
            runCatching { playerCache.removeResource(id) }
            com.prism.music.playback.AudioDiagnostics.log("stream", "$id came as format $itag, not $before as cached: its cached part goes")
        }
        cacheFormats.edit().putInt(id, itag).apply()
    }

    /**
     * [itag] may be saved under [key]: nothing of it is cached yet, or what is came in that format (recorded for it, as
     * the player's stream does). Blocking (the cache's own lock).
     */
    private fun sameFormat(key: String, itag: Int): Boolean {
        val before = cacheFormats.getInt(key, 0)
        val cached = playerCache.getCachedBytes(key, 0, Long.MAX_VALUE) > 0
        if (!mayAddFormat(before, itag, cached)) {
            com.prism.music.playback.AudioDiagnostics.log("stream", "$key: not saved ahead as format $itag, its cached part is format $before")
            return false
        }
        if (before != itag) cacheFormats.edit().putInt(key, itag).apply()
        return true
    }

    /** [id]'s cached part can't be read: it goes, with its format. Blocking (the cache's own lock). */
    fun forgetCachedAudio(id: String) {
        val key = songCacheKey(id)
        runCatching { playerCache.removeResource(key) }
        cacheFormats.edit().remove(key).apply()
    }

    /** A download was removed (or is being fetched again): its format no longer binds the next one. */
    fun forgetDownloadFormat(id: String) = downloadFormats.edit().remove(id).apply()

    /** The format (itag) a download came down in, when it's known (recorded since 1.7.7.1). */
    fun downloadedItag(id: String): Int? = downloadFormats.getInt(id, 0).takeIf { it != 0 }

    companion object {
        /** The mark on a streamed song's key: Downloads holds no such key ([playbackDataSourceFactory]). */
        private const val STREAMED = "streamed:"

        /**
         * Records [id]'s full length in [cache] once all of it is saved there. The cache only learns a length from a
         * request without one (a song played through); Load ahead's request names its length, so a song it saved whole
         * didn't count as whole, and offline Prism asked the network for it and skipped it (#213).
         */
        /**
         * Bytes in format [itag] may join what's cached of a song: nothing is ([cached] false), or it came in the same
         * format ([before]; 0 when it wasn't recorded, which isn't taken on trust).
         */
        internal fun mayAddFormat(before: Int, itag: Int, cached: Boolean): Boolean = !cached || before == itag

        fun markWhole(cache: Cache, id: String, length: Long) {
            if (length <= 0 || !cache.isCached(id, 0, length)) return
            runCatching { cache.applyContentMetadataMutations(id, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) }) }
        }
    }

    /** Songs whose first seconds [warmUp] saved into the song cache, and the stream (itag) they came from. */
    private val prebuffered = ConcurrentHashMap<String, Int>()

    /**
     * Gets a song ready to start the moment it's picked: its stream is resolved (the slow part, 1–2 s through
     * NewPipe) and, on Wi-Fi, its first ~10 seconds are saved into the song cache, so playback opens on bytes
     * already here instead of waiting on googlevideo. Blocking; call it off the main thread.
     */
    fun warmUp(
        id: String, whole: Boolean = false, cellular: Boolean = false,
        /** For Sing: waits while the player loads even the song itself, so the two never fetch it side by side. */
        forSing: Boolean = false,
        /** Handed the download as it starts (Sing stops it when it lets go of the song). */
        writer: ((androidx.media3.datasource.cache.CacheWriter) -> Unit)? = null,
    ) {
        if (id.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX)) return // a file on the phone is ready already
        if (id.startsWith(com.prism.music.data.model.Song.RADIO_PREFIX)) return // a radio station: live, nothing to load ahead
        if (downloadCache.fullyCached(id, 0, C.LENGTH_UNSET.toLong())) return
        yieldToPlaying(if (forSing) null else id)
        val s = runCatching { streams.resolve(id) }.getOrNull() ?: return // live streams and failures: nothing to save
        val metered = app.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        // On mobile data only when allowed (Settings → Load ahead on mobile data).
        if (metered && !cellular) return
        // [whole] (Settings → Load ahead): the whole song, so it plays without buffering; it needs the stream's size.
        if (whole && s.contentLength <= 0) return
        // Saved under the key the player reads it by (a picked version's own, [songCacheKey]).
        val key = songCacheKey(id)
        if (!whole && prebuffered.containsKey(key)) return
        val bytes = if (whole) s.contentLength else PREBUFFER_BYTES.let { if (s.contentLength > 0) minOf(it, s.contentLength) else it }
        if (playerCache.isCached(key, 0, bytes)) return
        // Never added to a part cached in another format (1.8.1): the player's own stream keeps to the format it began
        // in ([cacheFormat]); saving the rest from another one joined two files into one nothing could read, kept till the
        // cache was cleared (Sing on a TV, Reddit: "I have to uninstall and reinstall the app to restore playback").
        if (!sameFormat(key, s.itag)) return
        val spec = DataSpec.Builder().setUri(s.audioUrl).setKey(key).setPosition(0).setLength(bytes).build()
        val source = CacheDataSource(playerCache, OkHttpDataSource.Factory(streamHttp).createDataSource())
        runCatching { androidx.media3.datasource.cache.CacheWriter(source, spec, null, null).also { writer?.invoke(it) }.cache() }
            .onSuccess {
                prebuffered[key] = s.itag
                // Saved whole: say so, so it plays from here offline too (#213).
                if (whole) markWhole(playerCache, key, s.contentLength)
            }
            .onFailure { android.util.Log.w("PrismStream", "Couldn't save the start of $id ahead: $it") }
    }

    /**
     * Getting another song ready waits (up to [YIELD_MS]) while the song that's playing is loading, at its start or in a
     * stall. Resolving is heavy (NewPipe makes several requests and runs YouTube's player code), and several at once
     * slowed each other down: on the phone, resolves that took ~2 s on their own took 3–6 s side by side, the playing
     * song's own among them. [id]: the song being got ready (no wait when it's the one playing); null always waits.
     * Blocking; call it off the main thread.
     */
    fun yieldToPlaying(id: String?) {
        // Called as a song changes: the player's "loading" reaches this side a moment after the change itself.
        Thread.sleep(250)
        val until = android.os.SystemClock.elapsedRealtime() + YIELD_MS
        while (player.buffering.value && (id == null || player.currentSong.value?.id != id) && android.os.SystemClock.elapsedRealtime() < until) {
            Thread.sleep(150)
        }
    }

    /**
     * Download cache → song cache → network, with prism:// URIs resolved lazily. Only a whole download is read from
     * Downloads: a song that streams (or plays from the song cache) has its key marked [STREAMED], which Downloads never
     * holds, and the song cache reads it without the mark. Before, a download still coming down was read as far as it
     * had got, and the stream went on from there: another version's (1.7.9's check, "Woke Up Older"), or another
     * format's, and the two couldn't be read as one.
     */
    fun playbackDataSourceFactory(): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(streamHttp)
        val songCache = CacheDataSource.Factory()
            .setCache(playerCache)
            .setCacheKeyFactory { it.key?.removePrefix(STREAMED) ?: it.uri.toString() }
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val downloadsFirst = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(songCache)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(downloadsFirst) { spec -> resolveAudio(spec, true) }
    }

    /** A music video's picture: from Downloads when it's saved there (key `video:<id>`), else streamed (never cached). */
    fun videoDataSourceFactory(): DataSource.Factory {
        val http = OkHttpDataSource.Factory(streamHttp)
        val downloadsFirst = CacheDataSource.Factory()
            .setCache(downloadCache)
            // A link YouTube turns away is forgotten (and its next lookup goes through NewPipe), as for songs.
            .setUpstreamDataSourceFactory { ForgetOnForbidden(http.createDataSource()) { key, code -> streams.invalidate(key.removePrefix("video:"), refused = code == 403) } }
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(downloadsFirst) { spec ->
            val id = spec.uri.lastPathSegment
            if (spec.uri.scheme != "prism" || id == null) spec
            else if (downloadCache.fullyCached("video:$id", spec.position, spec.length)) spec.buildUpon().setKey("video:$id").build()
            else spec.buildUpon().setUri(resolveVideoUrl(id)).setKey("video:$id").build()
        }
    }

    private fun resolveVideoUrl(id: String): android.net.Uri {
        val s = try { streams.resolve(id, withVideo = true) } catch (e: Exception) { throw IOException(e.message, e) }
        return android.net.Uri.parse(s.videoUrl ?: throw IOException("No video stream available"))
    }

    /**
     * Prism Car's internet radio ([com.prism.music.data.radio.RadioDirectory]): stations from Radio Browser's directory,
     * favorites and the ones played lately. Only a build for cars (`-Pcar`) shows it.
     */
    val radioStations by lazy { com.prism.music.data.radio.RadioDirectory(app, http, scope) }

    /**
     * Radio stations: their streams are plain GETs from the station's own server, nothing cached (they have no end);
     * prism://radio/<id> (a station not known when it was queued) is looked up in the directory first.
     */
    fun radioDataSourceFactory(): DataSource.Factory =
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(http).setUserAgent("PrismCar/${BuildConfig.VERSION_NAME}")) { spec ->
            val id = spec.uri.lastPathSegment
            if (spec.uri.scheme != "prism" || spec.uri.authority != "radio" || id == null) spec else {
                val station = radioStations.stationBlocking(id) ?: throw IOException(tr(R.string.couldn_t_load_the_live_stream))
                spec.buildUpon().setUri(station.stream).build()
            }
        }

    /**
     * Live streams: prism://live/<id> becomes the stream's HLS manifest; its playlists and segments are plain GETs
     * (not the POST-and-range dance [YouTubeStreamInterceptor] does for files), and nothing is cached.
     */
    fun liveDataSourceFactory(): DataSource.Factory =
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(http).setUserAgent(com.prism.music.data.stream.DESKTOP_UA)) { spec ->
            val id = spec.uri.lastPathSegment
            if (spec.uri.scheme != "prism" || id == null) spec else {
                val url = try { streams.liveManifest(id) } catch (e: Exception) { throw IOException(e.message ?: tr(R.string.couldn_t_load_the_live_stream), e) }
                streamDetails[id] = "Live" to streamDetails[id]?.second
                onStreamResolved?.invoke(id)
                spec.buildUpon().setUri(url).build()
            }
        }

    /** Song downloads (prism://audio/<id>) and saved music videos' pictures (prism://video/<id>). */
    fun downloadDataSourceFactory(): DataSource.Factory {
        val http = OkHttpDataSource.Factory(streamHttp)
        // A link YouTube turns away (it ran out mid-download) is forgotten, so the download's next try gets a fresh one
        // (#55): it used to try the same dead link until the download failed.
        val upstream = DataSource.Factory { ForgetOnForbidden(http.createDataSource()) { key, code -> streams.invalidate(key.removePrefix("video:"), refused = code == 403) } }
        return ResolvingDataSource.Factory(upstream) { spec ->
            val id = spec.uri.lastPathSegment
            if (spec.uri.scheme == "prism" && spec.uri.authority == "video" && id != null) {
                spec.buildUpon().setUri(resolveVideoUrl(id)).setKey("video:$id").build()
            } else resolveAudio(spec, false)
        }
    }

    /**
     * A radio or mix's queue from YouTube Music, as songs when Settings → Playback → "Songs, not videos" is on (#39), with
     * each song's music video remembered for the Video button. [keepFirst]: a station's first item stays as it is
     * (it can be a live stream).
     */
    suspend fun radio(
        videoId: String?, playlistId: String?, params: String? = null, continuation: String? = null, keepFirst: Boolean = false,
    ): com.prism.music.data.model.NextResult {
        val r = ytm.next(videoId, playlistId, params, continuation)
        return (if (settings.current.radioSongsOnly) r.songsOnly(keepFirst) else r).also { queue.counterparts.putAll(it.counterparts) }
    }

    /** Animated-cover clips on the phone: recently played, and saved (from the player, by album, or with downloads). */
    val canvasStore by lazy { com.prism.music.data.canvas.CanvasStore(app, http, canvas, ytm, databaseProvider, scope) }
    fun songCacheBytes(): Long = runCatching { playerCache.cacheSpace }.getOrDefault(0)

    fun clearSongCache() {
        runCatching { playerCache.keys.toList().forEach { playerCache.removeResource(it) } }
    }
}

/** Tells [onForbidden] the stream's key (and the code) when YouTube refuses its link (403 / 410), then fails as usual. */
@OptIn(UnstableApi::class)
private class ForgetOnForbidden(private val inner: DataSource, private val onForbidden: (String, Int) -> Unit) : DataSource by inner {
    override fun open(dataSpec: DataSpec): Long = try {
        inner.open(dataSpec)
    } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode == 403 || e.responseCode == 410) dataSpec.key?.let { onForbidden(it, e.responseCode) }
        throw e
    }
}

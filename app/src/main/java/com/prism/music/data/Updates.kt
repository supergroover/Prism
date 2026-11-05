package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.profileinstaller.DeviceProfileWriter
import androidx.profileinstaller.ProfileInstaller
import com.prism.music.BuildConfig
import com.prism.music.container
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A newer Prism on GitHub: its version and where to get it (the APK itself when the release has one). */
data class AppUpdate(val version: String, val url: String) {
    val isApk: Boolean get() = url.endsWith(".apk", ignoreCase = true)
}

/** Where updating Prism from inside the app has got to. */
sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    /** [fraction] is null until the size is known. */
    data class Downloading(val fraction: Float?) : UpdateProgress
    /** Handed to Android's installer; waiting on the listener's confirmation or the install itself. */
    data object Installing : UpdateProgress
    /** Prism needs "Install unknown apps" switched on first. */
    data object NeedsPermission : UpdateProgress
    data class Failed(val message: String) : UpdateProgress
    /**
     * The new Prism is signed with a different key (the move off the debug key), so Android won't
     * install it over this one. The listener backs up, keeps [apk], uninstalls and installs it fresh.
     */
    data class NewSignature(val version: String, val apk: File) : UpdateProgress
}

private class SignatureChanged : Exception()

/**
 * Asks GitHub for Prism's latest release now and then (at most every six hours, or right away from
 * Settings → About), and can update
 * Prism in place: the APK is downloaded inside the app and passed to Android's installer, which
 * asks the listener to confirm (Android never lets an app install silently the first time). Only
 * the public release info and file are fetched; nothing about the listener is sent. Pre-releases
 * are never offered, and Android itself refuses an APK that isn't signed like the installed Prism.
 */
class UpdateChecker(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _available = MutableStateFlow(saved())
    /** The update to offer, or null when Prism is current (or this version was dismissed). */
    val available: StateFlow<AppUpdate?> = _available
    private val _progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)
    val progress: StateFlow<UpdateProgress> = _progress
    @Volatile private var checking = false
    private var job: Job? = null
    private val dir get() = File(context.cacheDir, "updates")

    init {
        // A finished update leaves its APK behind; it's no use once that version is running.
        if (_available.value == null) scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private fun saved(): AppUpdate? {
        // A build for cars (`-Pcar`) doesn't update itself: GitHub's APK is the phone's Prism.
        if (!BuildConfig.SELF_UPDATE) return null
        val v = prefs.getString("latest_version", null) ?: return null
        val url = prefs.getString("latest_url", null) ?: return null
        return AppUpdate(v, url).takeIf { isNewer(v, BuildConfig.VERSION_NAME) && v != prefs.getString("dismissed", null) }
    }

    /**
     * The background check Home runs; does nothing if GitHub was asked in the last hour (#278: six hours until 1.8.1,
     * so a new release could take most of a day to show; an unchanged answer is a cheap "nothing new" now, see [fetch]).
     */
    fun check() {
        if (!BuildConfig.SELF_UPDATE || checking || !dueToCheck(System.currentTimeMillis(), prefs.getLong("checked_at", 0))) return
        checking = true
        scope.launch(Dispatchers.IO) {
            runCatching { fetch() }
            checking = false
        }
    }

    /**
     * Asks GitHub right away (the listener tapped "Check for updates"). A version they dismissed on
     * Home is offered again. Returns the newer version, null when Prism is current, or throws.
     */
    suspend fun checkNow(): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!BuildConfig.SELF_UPDATE) return@withContext null
        prefs.edit().remove("dismissed").apply()
        fetch()
        _available.value?.version
    }

    private fun fetch() {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "Prism/${BuildConfig.VERSION_NAME}")
            // GitHub answers "not modified" when the latest release is the one seen last time (and doesn't count it
            // against its hourly limit), so asking every hour costs next to nothing.
            .apply { prefs.getString("etag", null)?.takeIf { _available.value != null || prefs.contains("latest_version") }?.let { header("If-None-Match", it) } }
            .build()
        val answer = runCatching { http.newCall(req).execute() }
        // GitHub's API allows 60 requests an hour per address, and carriers and VPNs share addresses: refused or
        // unreachable, the release page's own redirect says the same thing with no such limit (1.8.1.1).
        if (answer.getOrNull()?.let { it.code == 304 || it.isSuccessful } != true) {
            answer.getOrNull()?.close()
            if (fetchFromReleasePage()) return
            answer.exceptionOrNull()?.let { throw it }
            error(tr(R.string.github_answered, answer.getOrNull()?.code ?: 0))
        }
        answer.getOrThrow().use { r ->
            if (r.code == 304) { prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply(); _available.value = saved(); return }
            r.header("ETag")?.let { prefs.edit().putString("etag", it).apply() }
            val json = InnerTube.json.parseToJsonElement(r.body.string())
            val version = json.str("tag_name")?.removePrefix("v")?.trim() ?: error("GitHub's answer had no version")
            val apk = json.arr("assets")?.firstOrNull { it.str("name")?.endsWith(".apk", true) == true }?.str("browser_download_url")
            val url = apk ?: json.str("html_url") ?: "https://github.com/$REPO/releases/latest"
            prefs.edit().putString("latest_version", version).putString("latest_url", url).putLong("checked_at", System.currentTimeMillis()).apply()
            _available.value = saved()
        }
    }

    /** The latest version from where github.com/…/releases/latest redirects to. False when it can't tell. */
    private fun fetchFromReleasePage(): Boolean = runCatching {
        val req = Request.Builder().url("https://github.com/$REPO/releases/latest").header("User-Agent", "Prism/${BuildConfig.VERSION_NAME}").build()
        http.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(req).execute().use { r ->
            val version = versionFromLatestRedirect(r.header("Location")) ?: return@use false
            prefs.edit().putString("latest_version", version)
                .putString("latest_url", "https://github.com/$REPO/releases/download/v$version/Prism.apk")
                .putLong("checked_at", System.currentTimeMillis()).apply()
            _available.value = saved()
            true
        }
    }.getOrDefault(false)

    /** Hides the banner until a version newer than this one comes out. */
    fun dismiss() {
        _available.value?.let { prefs.edit().putString("dismissed", it.version).apply() }
        _available.value = null
        _progress.value = UpdateProgress.Idle
    }

    /** Whether Android lets Prism hand APKs to its installer ("Install unknown apps" for Prism). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the listener allows Prism to install updates. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Downloads [update] and starts installing it. Asks for the install permission first if it's
     * missing; [install] is simply called again once the listener comes back with it.
     */
    fun install(update: AppUpdate) {
        // One update at a time (#278: a second tap could start a second download and a second install): while one is
        // downloading nothing new starts, and while Android's installer waits for the listener, its screen comes back.
        when (installStep(_progress.value, job?.isActive == true)) {
            InstallStep.WAIT -> return
            InstallStep.REOPEN -> { pendingConfirm?.let { runCatching { context.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }; return }
            InstallStep.START -> Unit
        }
        if (!canInstall()) { _progress.value = UpdateProgress.NeedsPermission; return }
        _progress.value = UpdateProgress.Downloading(null)
        job = scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val apk = download(update)
                verify(apk)
                _progress.value = UpdateProgress.Installing
                startSession(apk)
            }
            result.exceptionOrNull()?.let { e ->
                if (!isActive) return@let
                _progress.value = if (e is SignatureChanged) UpdateProgress.NewSignature(update.version, File(dir, "Prism-${update.version}.apk"))
                else UpdateProgress.Failed(e.friendlyMessage(tr(R.string.the_update_didn_t_download)))
            }
        }
    }

    private fun download(update: AppUpdate): File {
        dir.mkdirs()
        val file = File(dir, "Prism-${update.version}.apk")
        if (file.exists()) return file
        val part = File(dir, file.name + ".part")
        val req = Request.Builder().url(update.url).header("User-Agent", "Prism/${BuildConfig.VERSION_NAME}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error(tr(R.string.github_answered, r.code))
            val body = r.body
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = total?.let { (done * 100 / it).toInt() } ?: -1
                        if (pct != shown) { shown = pct; _progress.value = UpdateProgress.Downloading(total?.let { done.toFloat() / it }) }
                    }
                }
            }
            if (total != null && done != total) error(tr(R.string.the_download_was_cut_short))
        }
        if (!part.renameTo(file)) error(tr(R.string.couldn_t_save_the_update))
        return file
    }

    /** Only Prism, and only something newer than what's running, is passed on. */
    @Suppress("DEPRECATION")
    private fun verify(apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: error(tr(R.string.the_downloaded_file_isn_t_an))
        if (info.packageName != context.packageName) error(tr(R.string.the_download_isn_t_prism))
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) error(tr(R.string.that_s_not_newer_than_this))
        val theirs = signers(context.packageManager.getPackageArchiveInfo(apk.path, signingFlag()))
        val ours = signers(context.packageManager.getPackageInfo(context.packageName, signingFlag()))
        if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours) throw SignatureChanged()
    }

    @Suppress("DEPRECATION")
    private fun signingFlag() = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES

    /** SHA-256 of each certificate an app is signed with. */
    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo?): Set<String> {
        info ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            else info.signatures
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> sha.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Copies the downloaded new Prism to [uri] (somewhere that outlives uninstalling this one). */
    suspend fun saveApk(apk: File, uri: Uri) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error(tr(R.string.couldn_t_open_that_file))
        out.use { o -> apk.inputStream().use { it.copyTo(o) } }
    }

    /** Android's "uninstall Prism?" prompt. */
    fun uninstallIntent(): Intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))

    /** Android's "install this update?" screen while it waits for the listener (shown again on a tap, #278). */
    @Volatile private var pendingConfirm: Intent? = null

    /** The APK being installed and whether its session carried [dexMetadata], for one try without it. */
    @Volatile private var installing: Pair<File, Boolean>? = null

    private fun startSession(apk: File, withProfile: Boolean = true) {
        val profile = if (withProfile) dexMetadata(apk) else null
        installing = apk to (profile != null)
        val installer = context.packageManager.packageInstaller
        // An earlier install of Prism's own that never finished (its confirm screen left open) goes: never two at once.
        runCatching { installer.mySessions.filter { it.appPackageName == context.packageName }.forEach { installer.abandonSession(it.sessionId) } }
        pendingConfirm = null
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length() + (profile?.length() ?: 0L))
            // Once Prism installed itself, later updates can go through without the confirm screen (Android 12+).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("Prism.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Named after the APK: that's how Android pairs the two.
            if (profile != null) session.openWrite("Prism.dm", 0, profile.length()).use { out ->
                profile.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    /**
     * The new Prism's startup profile as a dex metadata file, in this phone's own profile format (Android 9+), so
     * Android compiles the update's busiest code (starting up, Home, Search, the player) while installing it (1.7.4).
     * Without it an update ran partly in Android's slow interpreter, sometimes for days, until the phone's overnight
     * maintenance compiled it: the "slow after updating" people saw. The Play Store does the same for its apps; Prism
     * makes the file from the profile inside the new APK, with the converter Prism already uses on its own first start
     * (androidx.profileinstaller). Null if anything about it fails: the update then installs as before.
     */
    @SuppressLint("RestrictedApi")
    private fun dexMetadata(apk: File): File? {
        // Android 8's installer turns down any file in a session that isn't an APK.
        if (Build.VERSION.SDK_INT < 28) return null
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val app = pm.getPackageArchiveInfo(apk.path, 0)?.applicationInfo ?: return null
            app.sourceDir = apk.path
            app.publicSourceDir = apk.path
            val prof = File(dir, "primary.prof").apply { delete() }
            val writer = DeviceProfileWriter(
                pm.getResourcesForApplication(app).assets, Runnable::run, NoDiagnostics,
                "base.apk", "dexopt/baseline.prof", "dexopt/baseline.profm", prof,
            )
            if (!writer.deviceAllowsProfileInstallerAotWrites() || !writer.read().transcodeIfNeeded().write()) return null
            val bytes = prof.readBytes().also { prof.delete() }
            if (bytes.isEmpty()) return null
            File(dir, "Prism.dm").apply {
                ZipOutputStream(outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("primary.prof").apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.getOrNull()
    }

    private object NoDiagnostics : ProfileInstaller.DiagnosticsCallback {
        override fun onDiagnosticReceived(code: Int, data: Any?) {}
        override fun onResultReceived(code: Int, data: Any?) {}
    }

    internal fun onInstallerResult(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                pendingConfirm = Intent(confirm)
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // On success Android replaces Prism and closes it; there's nothing left to do here.
            PackageInstaller.STATUS_SUCCESS -> { pendingConfirm = null; _progress.value = UpdateProgress.Idle }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { pendingConfirm = null; _progress.value = UpdateProgress.Idle }
            else -> if (!retryWithoutProfile(status)) _progress.value = UpdateProgress.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        tr(R.string.android_wouldn_t_install_it_over)
                    PackageInstaller.STATUS_FAILURE_STORAGE -> tr(R.string.not_enough_space_to_install_the)
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: tr(R.string.the_update_didn_t_install)
                }
            )
        }
    }

    /**
     * If Android turned down a session that carried the profile (it shouldn't), the same update goes in again without
     * it, to be compiled later as before. Returns whether it's trying again.
     */
    private fun retryWithoutProfile(status: Int): Boolean {
        if (status != PackageInstaller.STATUS_FAILURE && status != PackageInstaller.STATUS_FAILURE_INVALID) return false
        val (apk, hadProfile) = installing ?: return false
        if (!hadProfile || !apk.exists()) return false
        installing = null
        scope.launch(Dispatchers.IO) {
            runCatching { startSession(apk, withProfile = false) }
                .onFailure { _progress.value = UpdateProgress.Failed(it.friendlyMessage(tr(R.string.the_update_didn_t_install))) }
        }
        return true
    }

    /** What a tap on the update does now ([installStep]). */
    enum class InstallStep { START, WAIT, REOPEN }

    companion object {
        const val REPO = "Yigder/Prism"

        /** How long between background checks (#278: was six hours). */
        const val CHECK_EVERY_MS = 3_600_000L

        fun dueToCheck(now: Long, checkedAt: Long): Boolean = now - checkedAt !in 0 until CHECK_EVERY_MS

        /** "1.8.1" from a redirect to ".../releases/tag/v1.8.1"; null for anywhere else (no release yet, a sign-in page). */
        fun versionFromLatestRedirect(location: String?): String? =
            location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.removePrefix("v")?.trim()
                ?.takeIf { it.isNotEmpty() && it.first().isDigit() }

        /**
         * A tap on the update while one is under way (#278): downloading, nothing new starts; handed to Android's
         * installer, its confirm screen is shown again instead of a second install starting.
         */
        fun installStep(progress: UpdateProgress, downloading: Boolean): InstallStep = when {
            downloading || progress is UpdateProgress.Downloading -> InstallStep.WAIT
            progress == UpdateProgress.Installing -> InstallStep.REOPEN
            else -> InstallStep.START
        }

        /** "1.10.0" is newer than "1.9.2"; anything after a dash ("-beta") is ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Android's installer reports here: it asks for the listener's go-ahead, or says how the install went. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.container.updates.onInstallerResult(intent)
    }
}

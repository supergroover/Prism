import java.awt.geom.Path2D
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("androidx.baselineprofile")
}

// Room writes each database version's schema here (checked in); MigrationTest builds old versions from them.
room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.prism.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.prism.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 56
        versionName = "1.8.1.1"
        vectorDrawables { useSupportLibrary = true }
        // The name under the launcher icon; a side-by-side build (below) is called "Prism Dev" so it can't be mistaken for Prism.
        manifestPlaceholders["appLabel"] = "@string/app_name"
        // Only the side-by-side test copy can be profiled from adb (simpleperf / Perfetto), for chasing jank (1.7.4).
        manifestPlaceholders["profileable"] = "false"
        // In-app tests (app/src/androidTest, 1.7.9): Prism on a fixed offline setup, YouTube and lyrics answered from
        // recordings (PrismTestRunner), each test in a fresh app with its data cleared (the orchestrator).
        testInstrumentationRunner = "com.prism.music.PrismTestRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Tests that need the real internet (@Live) only run when asked for. Prism TV's (@TvTest, 1.8.0) are left out by
        // PrismTestRunner unless a run asks for them (a second name here only works without the orchestrator).
        testInstrumentationRunnerArguments["notAnnotation"] = "com.prism.music.Live"
        // Optional: `lastfm.apiKey=…` in local.properties lets genre detection ask Last.fm too.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProps.getProperty("lastfm.apiKey", "")}\"")
        // Scrobbling (1.7.8) signs its requests with the API account's shared secret; without one it isn't offered.
        buildConfigField("String", "LASTFM_SECRET", "\"${localProps.getProperty("lastfm.secret", "")}\"")
        // Prism updates itself from GitHub's releases (data/Updates.kt), except a build for cars (`-Pcar`, below): an
        // update from GitHub is the phone's Prism, which would install beside it instead of replacing it.
        buildConfigField("boolean", "SELF_UPDATE", (!project.hasProperty("car")).toString())
    }

    // Releases are signed with the key in keystore.properties (gitignored, with the .jks beside it).
    // Without it — or with -PdebugSign — they fall back to the debug key so anyone can build.
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val useReleaseKey = keyProps.isNotEmpty() && !project.hasProperty("debugSign")
    signingConfigs {
        if (useReleaseKey) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // en-XA (stretched, accented English) and ar-XB (mirrored, right to left) in the phone's language list: text
            // that gets cut off, or a layout that doesn't turn around, shows up before any translation does.
            isPseudoLocalesEnabled = true
            // `-PsideBySide`: a debug Prism that installs next to the real one (for trying changes on your own phone), as
            // "Prism Dev". `-PsideBySide=name` installs as com.prism.music.name instead of .dev, beside an older dev copy.
            if (project.hasProperty("sideBySide")) {
                applicationIdSuffix = "." + (project.property("sideBySide") as String).ifBlank { "dev" }
                manifestPlaceholders["appLabel"] = (project.findProperty("sideByLabel") as String?) ?: "Prism Dev"
            }
            // `-PsingBench`: not debuggable, so Sing's speed test (SingSpeedTest) times Prism's own code as a release
            // runs it (a debuggable app's Kotlin ran Sing's transforms several times slower on the A16).
            if (project.hasProperty("singBench")) isDebuggable = false
        }
        release {
            // Kept unminified: NewPipeExtractor + Rhino rely on reflection.
            isMinifyEnabled = false
            // `-Pr8` (1.7.4 trial, not for releases yet): R8 optimizes Prism's, Compose's and Kotlin's own code and keeps
            // every other library whole (r8-trial.pro), for measuring what it does to jank.
            if (project.hasProperty("r8")) {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "r8-trial.pro")
            }
            signingConfig = signingConfigs.getByName(if (useReleaseKey) "release" else "debug")
            // `-PsideBySide` on a release build: the same "Prism Dev" test copy, but optimised like a release (debug
            // builds run Prism's own audio code slower, which matters when chasing stutters). Always the debug key, so
            // it can't be mistaken for (or installed over) the real Prism.
            if (project.hasProperty("sideBySide")) {
                applicationIdSuffix = "." + (project.property("sideBySide") as String).ifBlank { "dev" }
                // `-PsideByLabel="Prism Premium"`: a second test copy that reads differently on the home screen.
                manifestPlaceholders["appLabel"] = (project.findProperty("sideByLabel") as String?) ?: "Prism Dev"
                manifestPlaceholders["profileable"] = "true"
                signingConfig = signingConfigs.getByName("debug")
            }
            // `-Pcar`: Prism for a car with Android Automotive OS (Polestar, Volvo…), installed on the car itself. The car's
            // own media player shows Prism's library and keeps playing while driving; Prism's screens open from the
            // player's settings button when parked (src/car/AndroidManifest.xml). Installs as com.prism.music.car
            // ("Prism Car"), so it's never taken for the phone's Prism, and doesn't update itself (SELF_UPDATE).
            if (project.hasProperty("car")) {
                applicationIdSuffix = ".car"
                manifestPlaceholders["appLabel"] = "Prism Car"
            }
        }
    }

    // The car's manifest (`-Pcar`) is laid over the main one in release builds, where it's the only release manifest.
    if (project.hasProperty("car")) sourceSets.getByName("release").manifest.srcFile("src/car/AndroidManifest.xml")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Android 13+ lists Prism's languages (each values-xx folder) in the phone's own per-app language setting: since
    // 1.8.0 that list (xml/locales_config) comes from splitTranslations, as the build's own generator only reads src/.
    androidResources {
        // Only Prism's own languages (1.8.0, #268): the libraries bring their few words in ~85, and every one of them
        // costs ~19 KB of resources.arsc (which Android needs stored uncompressed) even where Prism shows English.
        // A region is only kept when named (the libraries' own regional words for Prism's languages too).
        localeFilters += listOf(
            "en", "en-rGB", "en-rAU", "en-rCA", "en-rIN", "es", "es-rUS", "pt", "pt-rBR", "pt-rPT", "fr", "fr-rCA", "de",
            "it", "nl", "sv", "pl", "cs", "ro", "hu", "el", "ru", "uk", "tr", "ar", "fa", "iw", "hi", "bn", "ur", "in",
            "ms", "vi", "th", "fil", "tl", "ja", "ko", "zh", "zh-rCN", "zh-rTW", "zh-rHK",
        )
    }

    // res/ reaches the build through splitTranslations (below), which keeps the translations out of resources.arsc.
    sourceSets.getByName("main").res.directories.clear()

    // The translations' MissingTranslation would flag every string (they're assets now); TranslationsTest checks them.
    lint {
        disable += "MissingTranslation"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric tests can read the app's own resources (strings, the widgets' layouts).
        unitTests.isIncludeAndroidResources = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }

    packaging {
        // ONNX Runtime's engine (13+ MB per CPU type) comes with Sing's download (ModelStore.Model.SING_RUNTIME_*), not
        // in the APK. Its small Java bridge (~120 KB) stays: Android only finds that one in the APK (it has no soname).
        jniLibs {
            excludes += setOf("**/libonnxruntime.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/CONTRIBUTORS.md",
                "META-INF/*.kotlin_module",
                "mozilla/public-suffix-list.txt",
                // Kuromoji's dictionary: downloaded when Japanese lyrics are first romanized (ModelStore.Model.JAPANESE).
                "com/atilika/kuromoji/ipadic/*.bin",
            )
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

// The builds :baselineprofile makes from release (benchmarkRelease, nonMinifiedRelease) can be traced and profiled from
// adb, as the side-by-side test copy can; the real release can't.
androidComponents {
    onVariants { v ->
        if (v.buildType != "release") v.manifestPlaceholders.put("profileable", "true")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // Window size and fold posture (tablets, foldables, landscape): Apache-2.0, brings androidx.window.
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    // Prism TV (1.8.0): Compose for TV's focus styling (cards that lift when focused, the side navigation), Google,
    // Apache-2.0. Only the TV screens (ui/tv) use it.
    implementation("androidx.tv:tv-material:1.1.0")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Hands the APK's baseline profile (src/main/generated/baselineProfiles, written by :baselineprofile) to Android, so
    // Prism's busiest code is compiled ahead instead of interpreted after an install or update (1.7.4). Apache-2.0.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // Runs yt-dlp's EJS solver (assets/ejs) on WebView's V8 to decipher signed-in stream URLs.
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    implementation("org.jsoup:jsoup:1.23.2")
    // Japanese lyrics' kanji readings for romanization (Apache-2.0). Its 13 MB dictionary isn't packed into the APK
    // (see packaging below): it's downloaded on first use (ModelStore). Tests read it from the jar.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // Sing's vocal separation (Spleeter) runs on ONNX Runtime (MIT). Its native libraries (13+ MB per CPU type) aren't
    // packed into the APK (see packaging below): they're downloaded with Sing's model on first use (ModelStore).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    // Blend: QR codes made and read (ZXing core, Apache-2.0), the camera for scanning one (CameraX, Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // On the PC, tests run the same models with ONNX Runtime's desktop build.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")

    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("dev.chrisbanes.haze:haze-glass:2.0.1")

    testImplementation("junit:junit:4.13.2")
    // NewPipe's own JavaScript engine (shipped with it already), for testing the stream solver's scripts.
    testImplementation("org.mozilla:rhino:1.8.1")
    // Draws composables on the PC (DesignGalleryTest: the redesign's components, light and dark), Apache-2.0, test-only;
    // the manifest piece is debug-only (it registers the empty activity tests draw in).
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Renders the backgrounds to PNGs on the PC (BackdropRenderTest), with Robolectric's real graphics.
    testImplementation("org.robolectric:robolectric:4.14.1")

    // In-app tests (1.7.9, release-check PLAN.md): Compose UI tests on a device, all Apache-2.0, test-only.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestUtil("androidx.test:orchestrator:1.6.1")
    androidTestUtil("androidx.test.services:test-services:1.6.0")
}

// The tests use ONNX Runtime's desktop build (with its PC libraries); the Android one's classes would clash with it.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

/**
 * The geometry behind the generated icon packs (1.7.7): an icon's path data as polylines, either finely (for the Dots
 * pack's rasterizing) or with every curve cut where it has turned 45° (the Facet pack: circles become octagons).
 */
object IconGeometry {
    class P(val x: Double, val y: Double)
    class Poly(val pts: MutableList<P>, var closed: Boolean)

    private val TOKENS = Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun polylines(d: String, facet: Boolean): List<Poly> {
        val toks = TOKENS.findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var cx = 0.0; var cy = 0.0; var sx = 0.0; var sy = 0.0
        var lcx = 0.0; var lcy = 0.0; var last = ' '
        val out = mutableListOf<Poly>()
        var cur: Poly? = null
        fun num() = toks[i++].toDouble()
        while (i < toks.size) {
            val t = toks[i]
            if (t[0].isLetter()) {
                cmd = t[0]; i++
                if (cmd == 'Z' || cmd == 'z') { cur?.closed = true; cx = sx; cy = sy; last = ' '; continue }
            }
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0.0
            val oy = if (rel) cy else 0.0
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = num() + ox; val y = num() + oy
                    cur = Poly(mutableListOf(P(x, y)), false); out += cur
                    cx = x; cy = y; sx = x; sy = y; last = ' '
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { val x = num() + ox; val y = num() + oy; cur!!.pts += P(x, y); cx = x; cy = y; last = ' ' }
                'H' -> { val x = num() + ox; cur!!.pts += P(x, cy); cx = x; last = ' ' }
                'V' -> { val y = num() + oy; cur!!.pts += P(cx, y); cy = y; last = ' ' }
                'C', 'S' -> {
                    val (x1, y1) = if (cmd.uppercaseChar() == 'C') (num() + ox) to (num() + oy)
                        else if (last == 'C') (2 * cx - lcx) to (2 * cy - lcy) else cx to cy
                    val x2 = num() + ox; val y2 = num() + oy; val x = num() + ox; val y = num() + oy
                    cubic(cur!!, cx, cy, x1, y1, x2, y2, x, y, facet)
                    lcx = x2; lcy = y2; cx = x; cy = y; last = 'C'
                }
                'Q', 'T' -> {
                    val (qx, qy) = if (cmd.uppercaseChar() == 'Q') (num() + ox) to (num() + oy)
                        else if (last == 'Q') (2 * cx - lcx) to (2 * cy - lcy) else cx to cy
                    val x = num() + ox; val y = num() + oy
                    cubic(cur!!, cx, cy, cx + 2.0 / 3 * (qx - cx), cy + 2.0 / 3 * (qy - cy), x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), x, y, facet)
                    lcx = qx; lcy = qy; cx = x; cy = y; last = 'Q'
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0.0; val sweep = num() != 0.0
                    val x = num() + ox; val y = num() + oy
                    arc(cur!!, cx, cy, rx, ry, rot, large, sweep, x, y, facet)
                    cx = x; cy = y; last = ' '
                }
                else -> error("path command $cmd")
            }
        }
        return out
    }

    private fun cubic(p: Poly, x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double, facet: Boolean) {
        val n = if (!facet) 16 else {
            // Where the curve heads at its two ends; a cut every 45° it turns between them.
            var ax = x1 - x0; var ay = y1 - y0
            if (Math.hypot(ax, ay) < 1e-6) { ax = x2 - x0; ay = y2 - y0 }
            var bx = x3 - x2; var by = y3 - y2
            if (Math.hypot(bx, by) < 1e-6) { bx = x3 - x1; by = y3 - y1 }
            val cos = ((ax * bx + ay * by) / (Math.hypot(ax, ay) * Math.hypot(bx, by)).coerceAtLeast(1e-9)).coerceIn(-1.0, 1.0)
            Math.round(Math.acos(cos) / (Math.PI / 4)).toInt().coerceAtLeast(1)
        }
        for (k in 1..n) {
            val t = k.toDouble() / n; val u = 1 - t
            p.pts += P(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3, u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3)
        }
    }

    /** An SVG arc (endpoint form) to points, by its centre form (SVG 1.1, F.6.5). */
    private fun arc(p: Poly, x1: Double, y1: Double, rx0: Double, ry0: Double, rotDeg: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double, facet: Boolean) {
        if (rx0 == 0.0 || ry0 == 0.0) { p.pts += P(x2, y2); return }
        val phi = Math.toRadians(rotDeg); val cphi = Math.cos(phi); val sphi = Math.sin(phi)
        val dx = (x1 - x2) / 2; val dy = (y1 - y2) / 2
        val x1p = cphi * dx + sphi * dy; val y1p = -sphi * dx + cphi * dy
        var rx = Math.abs(rx0); var ry = Math.abs(ry0)
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { rx *= Math.sqrt(lambda); ry *= Math.sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = Math.sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val ccx = cphi * cxp - sphi * cyp + (x1 + x2) / 2; val ccy = sphi * cxp + cphi * cyp + (y1 + y2) / 2
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * Math.PI else if (sweep && dth < 0) dth += 2 * Math.PI
        val n = if (facet) Math.ceil(Math.abs(dth) / (Math.PI / 4) - 1e-6).toInt().coerceAtLeast(1)
            else Math.ceil(Math.abs(dth) / (Math.PI / 18)).toInt().coerceAtLeast(4)
        for (k in 1..n) {
            val th = th1 + dth * k / n
            val ex = rx * Math.cos(th); val ey = ry * Math.sin(th)
            p.pts += P(cphi * ex - sphi * ey + ccx, sphi * ex + cphi * ey + ccy)
        }
    }

    fun pathData(polys: List<Poly>, num: (Double) -> String): String = polys.joinToString(" ") { poly ->
        "M ${num(poly.pts[0].x)} ${num(poly.pts[0].y)} " + poly.pts.drop(1).joinToString(" ") { "L ${num(it.x)} ${num(it.y)}" } + if (poly.closed) " Z" else ""
    }

    /** The Dots pack: dots this far apart along each line (grid units, 24 a side), and on this grid inside solid shapes. */
    const val DOT_SPACING = 2.0

    /**
     * An icon in dots (the Dots pack): beads spaced evenly along each line of the drawing (both ends of an open line
     * included), and solid shapes ([fillTwin]: also the shapes a selected twin fills) filled with dots on a grid. Dots
     * closer than half a spacing are merged. As hex, four digits a dot: x and y in quarter units (0–96).
     */
    fun dots(parts: List<Triple<List<Poly>, Int, Double>>, fillTwin: Boolean): String {
        val sp = DOT_SPACING
        val out = mutableListOf<P>()
        fun add(p: P) { if (out.none { Math.hypot(it.x - p.x, it.y - p.y) < sp * 0.55 }) out += p }
        for ((polys, kind, _) in parts) {
            val solid = kind == 1 || (fillTwin && kind == 2)
            for (poly in polys) {
                val pts = if (poly.closed) poly.pts + poly.pts[0] else poly.pts
                val segs = pts.zipWithNext()
                val len = segs.sumOf { (a, b) -> Math.hypot(b.x - a.x, b.y - a.y) }
                if (len < 1e-6) { add(pts[0]); continue }
                val n = Math.max(1, Math.round(len / sp).toInt())
                // Evenly along the line: a closed one all round, an open one end to end.
                val count = if (poly.closed) n else n + 1
                for (k in 0 until count) {
                    var at = len * k / n
                    for ((a, b) in segs) {
                        val l = Math.hypot(b.x - a.x, b.y - a.y)
                        if (at <= l || b === segs.last().second) { val f = if (l < 1e-9) 0.0 else (at / l).coerceIn(0.0, 1.0); add(P(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)); break }
                        at -= l
                    }
                }
            }
            if (solid) {
                val path = Path2D.Double()
                for (poly in polys) {
                    path.moveTo(poly.pts[0].x, poly.pts[0].y)
                    poly.pts.drop(1).forEach { path.lineTo(it.x, it.y) }
                    path.closePath()
                }
                var y = sp / 2
                while (y < 24) { var x = sp / 2; while (x < 24) { if (path.contains(x, y)) add(P(x, y)); x += sp }; y += sp }
            }
        }
        val sb = StringBuilder()
        for (p in out) {
            val qx = Math.round(p.x * 4).toInt().coerceIn(0, 255); val qy = Math.round(p.y * 4).toInt().coerceIn(0, 255)
            sb.append(String.format("%02x%02x", qx, qy))
        }
        return sb.toString()
    }
}

/**
 * Prism's icons are drawn as SVG files in design/icons (see its README). This turns them into
 * `PrismIconData` (generated Kotlin: each icon's path data and how it's drawn), which `PrismIcons` builds from, so the
 * app can't drift from the drawings. It reads the small part of SVG the drawings use: path, circle and rect (with rx),
 * class "f" (filled) or "o" (outline that the selected twin fills), stroke-width, and on the root data-filled
 * ("tint" or "solid": the icon has a selected twin, NameFilled) and data-mirror ("true": flipped right to left).
 */
abstract class GeneratePrismIcons : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val drawings: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Android drawables made from the same drawings, for what Compose doesn't draw: widgets, the tile, Android Auto. */
    @get:OutputDirectory
    abstract val resDir: DirectoryProperty

    /** A drawable: [icon]'s drawing ([filled]: its selected twin), white, turned half round when [flip]. */
    private class Drawable(val icon: String, val filled: Boolean = false, val flip: Boolean = false, val note: String)

    private val drawables = mapOf(
        "ic_w_play" to Drawable("Play", note = "Widgets: play"),
        "ic_w_pause" to Drawable("Pause", note = "Widgets: pause"),
        "ic_w_next" to Drawable("Next", note = "Widgets: next"),
        "ic_w_prev" to Drawable("Previous", note = "Widgets: previous"),
        "ic_w_heart" to Drawable("Heart", filled = true, note = "Widgets: liked"),
        "ic_w_heart_outline" to Drawable("Heart", note = "Widgets: like"),
        "ic_w_thumb" to Drawable("ThumbDown", filled = true, flip = true, note = "YouTube Music-style widget: liked (thumb up)"),
        "ic_w_thumb_outline" to Drawable("ThumbDown", flip = true, note = "YouTube Music-style widget: like (thumb up)"),
        "ic_tile_recognize" to Drawable("Recognize", note = "Song recognition's Quick Settings tile and widget"),
        "ic_s_search" to Drawable("Search", note = "App icon shortcut: Search"),
        "ic_s_downloads" to Drawable("DownloadDone", note = "App icon shortcut: Downloads"),
    )

    /** One generated path: its data, kind (0 line, 1 filled, 2 line the twin fills) and line width. */
    private class GenPart(val d: String, val kind: Int, val width: Double)

    private fun vector(name: String, spec: Triple<Boolean, Int, List<GenPart>>, dr: Drawable): String {
        val (mirror, twin, parts) = spec
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<!-- ${dr.note}. Generated from design/icons/${dr.icon}.svg by app/build.gradle.kts (GeneratePrismIcons). -->\n")
        sb.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        sb.append("    android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"")
        sb.append(if (mirror) "\n    android:autoMirrored=\"true\">\n" else ">\n")
        if (dr.flip) sb.append("    <group android:rotation=\"180\" android:pivotX=\"12\" android:pivotY=\"12\">\n")
        val ind = if (dr.flip) "        " else "    "
        for (p in parts) {
            if (p.kind == 1) { sb.append("$ind<path android:fillColor=\"#FFFFFFFF\" android:pathData=\"${p.d}\" />\n"); continue }
            val fill = dr.filled && p.kind == 2 && twin != 0
            sb.append("$ind<path android:pathData=\"${p.d}\"\n")
            sb.append("$ind    android:fillColor=\"${if (fill) "#FFFFFFFF" else "#00000000"}\"")
            if (fill && twin == 1) sb.append(" android:fillAlpha=\"0.18\"")
            sb.append("\n$ind    android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"${num(p.width)}\"")
            sb.append(" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" />\n")
        }
        if (dr.flip) sb.append("    </group>\n")
        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun num(v: Double): String = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Path data with every command and number apart, so any parser reads it the same. */
    private fun normalize(d: String, where: String): String {
        val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        val leftover = d.replace(Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?|[\\s,]"), "")
        require(leftover.isEmpty()) { "$where: can't read '$leftover' in its path" }
        require(tokens.firstOrNull()?.let { it == "M" || it == "m" } == true) { "$where: a path has to start with M" }
        return tokens.joinToString(" ") { t -> t.toDoubleOrNull()?.let(::num) ?: t }
    }

    @TaskAction
    fun generate() {
        val files = drawings.get().asFile.listFiles { f -> f.extension == "svg" }!!.sortedBy { it.name }
        require(files.isNotEmpty()) { "No drawings in ${drawings.get().asFile}" }
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val generated = HashMap<String, Triple<Boolean, Int, List<GenPart>>>()
        val out = StringBuilder()
        out.append("// Generated from design/icons/*.svg by app/build.gradle.kts (GeneratePrismIcons). Edit the drawings, not this.\n")
        out.append("package com.prism.music.ui.theme\n\n")
        out.append("internal object PrismIconData {\n")
        out.append("    /** [kind]: 0 a line, 1 filled, 2 a line the selected twin fills. [width] in grid units (24 a side). */\n")
        out.append("    class Part(val d: String, val kind: Int, val width: Float)\n")
        out.append("    /**\n")
        out.append("     * [twin]: 0 none, 1 tinted fill, 2 solid fill. [facet]: the Facet pack's drawing (curves cut every 45°).\n")
        out.append("     * [dots] / [dotsFilled]: the Dots pack's dots as hex, four digits each: x, y in quarter units (filled: the selected twin).\n")
        out.append("     */\n")
        out.append("    class Spec(val mirror: Boolean, val twin: Int, val parts: Array<Part>, val facet: Array<Part>, val dots: String, val dotsFilled: String)\n\n")
        out.append("    const val DOT_SPACING = ${IconGeometry.DOT_SPACING}f\n\n")
        out.append("    val names = arrayOf(${files.joinToString(", ") { "\"${it.nameWithoutExtension}\"" }})\n\n")
        out.append("    fun spec(name: String): Spec = when (name) {\n")
        for (f in files) {
            val name = f.nameWithoutExtension
            require(Regex("[A-Z][A-Za-z0-9]*").matches(name)) { "${f.name}: name it like the Kotlin icon (Home.svg)" }
            val root = parser.parse(f).documentElement
            val mirror = root.getAttribute("data-mirror") == "true"
            val twin = when (val t = root.getAttribute("data-filled")) { "" -> 0; "tint" -> 1; "solid" -> 2; else -> error("${f.name}: data-filled '$t'") }
            val baseWidth = root.getAttribute("stroke-width").toDoubleOrNull() ?: 1.5
            val parts = mutableListOf<String>()
            val genParts = mutableListOf<GenPart>()
            val children = root.childNodes
            for (i in 0 until children.length) {
                val el = children.item(i) as? org.w3c.dom.Element ?: continue
                fun a(n: String) = el.getAttribute(n).toDoubleOrNull() ?: 0.0
                val d = when (el.tagName) {
                    "style", "title", "desc" -> continue
                    "path" -> normalize(el.getAttribute("d"), f.name)
                    "circle" -> {
                        val (cx, cy, r) = Triple(a("cx"), a("cy"), a("r"))
                        "M ${num(cx + r)} ${num(cy)} A ${num(r)} ${num(r)} 0 1 1 ${num(cx - r)} ${num(cy)} A ${num(r)} ${num(r)} 0 1 1 ${num(cx + r)} ${num(cy)} Z"
                    }
                    "rect" -> {
                        val (x, y) = a("x") to a("y")
                        val (w, h) = a("width") to a("height")
                        val r = minOf(a("rx"), w / 2, h / 2)
                        if (r <= 0.0) "M ${num(x)} ${num(y)} H ${num(x + w)} V ${num(y + h)} H ${num(x)} Z"
                        else listOf(
                            "M ${num(x + r)} ${num(y)} H ${num(x + w - r)} A ${num(r)} ${num(r)} 0 0 1 ${num(x + w)} ${num(y + r)}",
                            "V ${num(y + h - r)} A ${num(r)} ${num(r)} 0 0 1 ${num(x + w - r)} ${num(y + h)}",
                            "H ${num(x + r)} A ${num(r)} ${num(r)} 0 0 1 ${num(x)} ${num(y + h - r)}",
                            "V ${num(y + r)} A ${num(r)} ${num(r)} 0 0 1 ${num(x + r)} ${num(y)} Z",
                        ).joinToString(" ")
                    }
                    else -> error("${f.name}: <${el.tagName}> isn't supported (use path, circle or rect)")
                }
                val kind = when (val c = el.getAttribute("class")) { "" -> 0; "f" -> 1; "o" -> 2; else -> error("${f.name}: class '$c'") }
                val width = el.getAttribute("stroke-width").toDoubleOrNull() ?: baseWidth
                parts += "Part(\"$d\", $kind, ${num(width)}f)"
                genParts += GenPart(d, kind, width)
            }
            generated[name] = Triple(mirror, twin, genParts)
            require(parts.isNotEmpty()) { "${f.name} draws nothing" }
            require(twin == 0 || "2, " in parts.joinToString()) { "${f.name}: data-filled needs a class=\"o\" shape to fill" }
            // The two generated packs (1.7.7) from the same drawing.
            val facet = genParts.map { gp -> "Part(\"${IconGeometry.pathData(IconGeometry.polylines(gp.d, true), ::num)}\", ${gp.kind}, ${num(gp.width)}f)" }
            val fine = genParts.map { Triple(IconGeometry.polylines(it.d, false), it.kind, it.width) }
            val dots = IconGeometry.dots(fine, fillTwin = false)
            val dotsFilled = if (twin != 0) IconGeometry.dots(fine, fillTwin = true) else dots
            out.append("        \"$name\" -> Spec($mirror, $twin, arrayOf(\n")
            parts.forEach { out.append("            $it,\n") }
            out.append("        ), arrayOf(\n")
            facet.forEach { out.append("            $it,\n") }
            out.append("        ), \"$dots\", \"$dotsFilled\")\n")
        }
        out.append("        else -> error(\"No drawing for icon \$name in design/icons\")\n    }\n}\n")
        val dir = outputDir.get().asFile.resolve("com/prism/music/ui/theme").apply { mkdirs() }
        dir.resolve("PrismIconData.kt").writeText(out.toString())

        val res = resDir.get().asFile.resolve("drawable").apply { deleteRecursively(); mkdirs() }
        for ((file, dr) in drawables) {
            val spec = generated[dr.icon] ?: error("$file needs design/icons/${dr.icon}.svg")
            res.resolve("$file.xml").writeText(vector(file, spec, dr))
        }
    }
}

val generatePrismIcons = tasks.register<GeneratePrismIcons>("generatePrismIcons") {
    drawings.set(rootProject.layout.projectDirectory.dir("design/icons"))
    outputDir.set(layout.buildDirectory.dir("generated/prismIcons/kotlin"))
    resDir.set(layout.buildDirectory.dir("generated/prismIcons/res"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generatePrismIcons, GeneratePrismIcons::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(generatePrismIcons, GeneratePrismIcons::resDir)
    }
}

/**
 * The translations kept small (1.8.0, #268). Android needs resources.arsc stored uncompressed, and 31 languages' text in
 * it took 1.7.9's APK from 23 to 33 MB. So res/ goes to the build through this copy, in which each values-xx/strings.xml
 * keeps only what Android reads itself (the manifest's and widgets' labels, [kept]) and the plurals; every other string
 * goes into the compressed assets/i18n/<folder> (the strings' XML text in names' order, NUL between them, U+0001 for one
 * not translated yet), which Strings.kt reads. Each language's resources say which table is theirs (prism_i18n_table),
 * so Android still picks the language, exactly as it picks a values-xx folder. The files in src/main/res stay as they
 * are (TranslationsTest, corrections as pull requests).
 */
abstract class SplitTranslations : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val res: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifest: RegularFileProperty
    /** Strings the code hands to Android by id (Media3's download notification) or that the build names (app_name). */
    @get:Input abstract val kept: SetProperty<String>
    @get:OutputDirectory abstract val resDir: DirectoryProperty
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty

    private fun parse(f: File): org.w3c.dom.Document =
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)

    private fun elements(d: org.w3c.dom.Document, tag: String) =
        d.documentElement.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }

    @TaskAction fun split() {
        val src = res.get().asFile
        val outRes = resDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val outAssets = assetsDir.get().asFile.apply { deleteRecursively() }.resolve("i18n").apply { mkdirs() }
        val ref = Regex("@string/(\\w+)")
        val keep = kept.get().toMutableSet()
        src.walkTopDown().filter { it.isFile && it.extension == "xml" && !it.parentFile.name.startsWith("values") }
            .plus(manifest.get().asFile).forEach { f -> ref.findAll(f.readText()).forEach { keep += it.groupValues[1] } }

        val english = elements(parse(File(src, "values/strings.xml")), "string")
        val names = english.filter { it.getAttribute("translatable") != "false" }.map { it.getAttribute("name") }.filter { it !in keep }
        outAssets.resolve("names").writeText(names.joinToString("\n"))
        // English stays in resources.arsc: it's every language's fallback.
        File(outRes, "values").mkdirs()
        File(outRes, "values/i18n_table.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" +
                "    <string name=\"prism_i18n_table\" translatable=\"false\"></string>\n</resources>\n",
        )

        // The phone's per-app language list (Android 13+): English (resources.properties says which) and every values-xx.
        val default = File(src, "resources.properties").readLines().first { it.startsWith("unqualifiedResLocale=") }.substringAfter('=')
        val locales = src.listFiles().orEmpty().filter { File(it, "strings.xml").isFile && it.name.startsWith("values-") }
            .map { it.name.removePrefix("values-").removePrefix("b+").replace("-r", "-").replace('+', '-') }.sorted()
        File(outRes, "xml").mkdirs()
        File(outRes, "xml/locales_config.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\"" +
                " android:defaultLocale=\"$default\">\n" +
                (listOf(default) + locales).joinToString("") { "    <locale android:name=\"$it\"/>\n" } + "</locale-config>\n",
        )

        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        src.walkTopDown().filter { it.isFile && it.parentFile != src }.forEach { f ->
            val rel = f.relativeTo(src).path
            val folder = f.parentFile.name
            if (!(folder.startsWith("values-") && f.name == "strings.xml")) {
                f.copyTo(File(outRes, rel))
                return@forEach
            }
            val doc = parse(f)
            val qualifier = folder.removePrefix("values-")
            val texts = elements(doc, "string").associate { it.getAttribute("name") to it.textContent }
            outAssets.resolve(qualifier).writeText(names.joinToString("\u0000") { texts[it] ?: "\u0001" })
            elements(doc, "string").filter { it.getAttribute("name") !in keep }.forEach { it.parentNode.removeChild(it) }
            doc.documentElement.appendChild(doc.createElement("string").apply {
                setAttribute("name", "prism_i18n_table")
                textContent = qualifier
            })
            File(outRes, rel).apply { parentFile.mkdirs() }.outputStream().use {
                transformer.transform(javax.xml.transform.dom.DOMSource(doc), javax.xml.transform.stream.StreamResult(it))
            }
        }
    }
}

val splitTranslations = tasks.register<SplitTranslations>("splitTranslations") {
    res.set(layout.projectDirectory.dir("src/main/res"))
    manifest.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    kept.set(setOf("app_name", "downloads"))
    resDir.set(layout.buildDirectory.dir("generated/i18n/res"))
    assetsDir.set(layout.buildDirectory.dir("generated/i18n/assets"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(splitTranslations, SplitTranslations::resDir)
        variant.sources.assets?.addGeneratedSourceDirectory(splitTranslations, SplitTranslations::assetsDir)
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

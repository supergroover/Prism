package com.prism.music.data.radio

import android.content.Context
import com.prism.music.BuildConfig
import com.prism.music.R
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** A station as Radio Browser's directory (radio-browser.info) lists it. */
@Serializable
data class RadioStation(
    @SerialName("stationuuid") val uuid: String,
    val name: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val url: String = "",
    val favicon: String = "",
    val tags: String = "",
    val countrycode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
) {
    /** Where the sound comes from: the address the directory followed its redirects to, else the one given. */
    val stream: String get() = urlResolved.trim().ifBlank { url.trim() }
    val isHls: Boolean get() = hls == 1 || stream.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    /** The station's logo, when it has a web address. */
    val logo: String? get() = favicon.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }

    /** The station as a queue item: live, with the station's name as its title and "Radio · its genres" under it. */
    fun toSong(): Song {
        val genres = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 24 }.take(2)
            .map { t -> t.replaceFirstChar { it.uppercaseChar() } }
        return Song(
            id = Song.RADIO_PREFIX + uuid,
            title = name.trim().ifBlank { tr(R.string.car_radio) },
            artists = listOf(ArtistRef((listOf(tr(R.string.car_radio)) + genres).joinToString(" · "))),
            thumbnail = logo,
            isLive = true,
        )
    }
}

/**
 * Internet radio for Prism Car (a build for cars with Android Automotive OS): stations from Radio Browser, a free and open
 * directory run by volunteers (no account or key), as the car's Radio tab lists them: favorites, the most played in the
 * Netherlands, and search. Favorites and the stations played lately (so a queue can be resumed) are kept on the device.
 */
class RadioDirectory(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "radio.json")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Serializable
    private data class Saved(val favorites: List<String> = emptyList(), val recent: List<RadioStation> = emptyList())

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    /** Favorite stations' ids, first added first. */
    val favoriteIds: StateFlow<List<String>> = _favorites
    /** Stations played lately, newest first (their addresses, for a queue resumed before the directory answers). */
    private var recent: List<RadioStation> = emptyList()
    private var popular: Pair<Long, List<RadioStation>>? = null
    @Volatile private var server = 0

    init {
        runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()?.let { s ->
            s.recent.forEach { known[it.uuid] = it }
            recent = s.recent
            _favorites.value = s.favorites
        }
    }

    fun isFavorite(uuid: String) = uuid in _favorites.value

    fun favorites(): List<RadioStation> = _favorites.value.mapNotNull { known[it] }

    /** Adds the station to the favorites or takes it off; true when it's a favorite now. */
    fun toggleFavorite(uuid: String): Boolean {
        val now = if (isFavorite(uuid)) _favorites.value - uuid else _favorites.value + uuid
        _favorites.value = now
        save()
        return uuid in now
    }

    /** The most played stations in the Netherlands, asked again after an hour. */
    suspend fun popular(): List<RadioStation> = withContext(Dispatchers.IO) {
        popular?.takeIf { System.currentTimeMillis() - it.first < POPULAR_FOR_MS }?.second
            ?: stations("json/stations/search?countrycode=NL&hidebroken=true&order=clickcount&reverse=true&limit=60")
                .also { popular = System.currentTimeMillis() to it }
    }

    /** Stations whose name has [query] in it, the most played first. */
    suspend fun search(query: String): List<RadioStation> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) emptyList()
        else stations("json/stations/search?name=${URLEncoder.encode(q, "UTF-8")}&hidebroken=true&order=clickcount&reverse=true&limit=20")
    }

    /** The station for [uuid], asking the directory when it isn't known yet. Blocking: for the player's loading thread. */
    fun stationBlocking(uuid: String): RadioStation? =
        known[uuid] ?: runCatching { stations("json/stations/byuuid?uuids=${URLEncoder.encode(uuid, "UTF-8")}").firstOrNull() }.getOrNull()

    /**
     * The station started playing: kept among the recent ones, and counted in the directory (as Radio Browser asks of
     * the apps that use it; that's what orders its "most played").
     */
    fun played(uuid: String) {
        val station = known[uuid] ?: return
        recent = (listOf(station) + recent.filter { it.uuid != uuid }).take(KEPT_RECENT)
        save()
        scope.launch(Dispatchers.IO) { runCatching { request("json/url/${URLEncoder.encode(uuid, "UTF-8")}") } }
    }

    private fun stations(path: String): List<RadioStation> {
        val list = json.decodeFromString(ListSerializer(RadioStation.serializer()), request(path))
            .filter { it.uuid.isNotBlank() && it.name.isNotBlank() && (it.stream.startsWith("https://") || it.stream.startsWith("http://")) }
            // The directory often has a station several times (added by different people): the most played one stays.
            .distinctBy { it.name.trim().lowercase() }
        list.forEach { known[it.uuid] = it }
        return list
    }

    /** [path] from one of the directory's servers, trying the next when one doesn't answer. */
    private fun request(path: String): String {
        var failure: Exception? = null
        for (n in SERVERS.indices) {
            val i = (server + n) % SERVERS.size
            try {
                val req = Request.Builder().url("https://${SERVERS[i]}.api.radio-browser.info/$path")
                    .header("User-Agent", "PrismCar/${BuildConfig.VERSION_NAME}").build()
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        server = i
                        return r.body.string()
                    }
                    failure = IOException("Radio Browser answered ${r.code}")
                }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("Radio Browser didn't answer")
    }

    private fun save() {
        val saved = Saved(_favorites.value, (favorites() + recent).distinctBy { it.uuid })
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, "radio.json.tmp")
                tmp.writeText(json.encodeToString(Saved.serializer(), saved))
                if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
            }
        }
    }

    companion object {
        /** Radio Browser's servers (any of them has the whole directory). */
        private val SERVERS = listOf("de1", "nl1", "at1", "fi1", "de2")
        private const val POPULAR_FOR_MS = 60 * 60 * 1000L
        private const val KEPT_RECENT = 30

        /** Every station listed or kept this run, by id: what a queue item's address and logo come from. */
        private val known = ConcurrentHashMap<String, RadioStation>()

        fun station(uuid: String): RadioStation? = known[uuid]

        /** Whether [url] is a station's logo from the directory (the car's artwork provider fetches only those, of all sites). */
        fun isLogo(url: String): Boolean = known.values.any { it.logo == url }
    }
}

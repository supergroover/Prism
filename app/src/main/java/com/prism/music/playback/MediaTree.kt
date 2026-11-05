package com.prism.music.playback

import com.prism.music.R
import com.prism.music.tr
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.prism.music.AppContainer
import com.prism.music.BuildConfig
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.hiRes
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The browse tree Android Auto (and other media browsers) see:
 * Home · Liked songs · Library · Recently played, plus search.
 */
class MediaTree(private val c: AppContainer) {
    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val LIKED = "liked"
        const val LIBRARY = "library"
        const val RECENT = "recent"
        const val DOWNLOADS = "downloads"
        /** Prism's own mixes (Daily Mixes, the time-of-day mix, Release Radar). */
        const val MADE_FOR_YOU = "foryou"
        const val SHUFFLE = "shuffle:"
        /** "Play as a DJ set" at the top of a list: the list in the order that blends best, mixed (DJ mode). */
        const val DJ = "dj:"
        /** A Prism playlist's folder ("pp:" + its "prism:…" id). */
        const val PRISM_LIST = "pp:"
        /** A holiday mix's folder ("hm:" + its "season:…" id). */
        const val HOLIDAY = "hm:"
        /** Prism Car's Radio tab (internet radio, in Recents' place), and its lists of stations ("rl:" + which). */
        const val RADIO = "radio"
        private const val RADIO_LIST = "rl:"
        /** How many of each Home section's items show as tiles in the car. */
        private const val HOME_TILES = 8
        /** The songs a search found, as a folder (a song tapped in the results plays them on from there). */
        private const val SEARCH = "search"
        /** How many of each kind a search lists, and how many searches are kept. */
        private const val SEARCH_EACH = 8
        private const val KEPT_SEARCHES = 4
        private val SEARCH_KINDS = setOf(SearchFilter.SONGS, SearchFilter.ALBUMS, SearchFilter.ARTISTS, SearchFilter.PLAYLISTS)
    }

    /** Every song handed out, so a mediaId coming back can be played. */
    private val songs = ConcurrentHashMap<String, Song>()
    /** Songs per browsed folder, so tapping one song plays the whole list from there. */
    private val folders = ConcurrentHashMap<String, Pair<String, List<Song>>>()
    private var homeShelves: List<Shelf> = emptyList()
    /** Search results by query (lower case), oldest first. */
    private val searches = LinkedHashMap<String, List<MediaItem>>()

    /**
     * A folder. [extras]: how a car shows what's inside it, or the section it sits under ([styled], [grouped]).
     */
    private fun folder(
        id: String, title: String, subtitle: String? = null, art: String? = null, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        extras: android.os.Bundle? = null, fallback: String = AUTO_PLAYLIST,
    ) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUri(art, fallback))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setExtras(extras)
                .build()
        ).build()

    private fun playable(song: Song, extras: android.os.Bundle? = null): MediaItem {
        songs[song.id] = song
        val item = song.toMediaItem()
        // A station's logo (or Prism's radio tile) and "Radio · its genres" are its item's already.
        if (song.isRadio) return item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon().setArtworkUri(artworkUri(song.thumbnail, RADIO_TILE)).setSubtitle(song.artistText)
                .apply { if (extras != null) setExtras(extras) }.build()
        ).build()
        return item.buildUpon().setMediaMetadata(
            // The cover picked for the song or its album (#305) when there is one.
            item.mediaMetadata.buildUpon().setArtworkUri(artworkUri(c.covers.art(song))).setSubtitle(com.prism.music.data.SongVersions.shownOf(song).artistText)
                .apply { if (extras != null) setExtras(extras) }.build()
        ).build()
    }

    /** Settings → Lyrics → Android Auto → "Covers in a grid" (Reddit 1.7: "a home like Spotify's"). */
    private val grid get() = c.settings.current.autoGrid

    /** A folder whose contents show as cover tiles ([androidx.media3.session.MediaConstants]), when the grid's on. */
    private fun styled(browsableGrid: Boolean, playableGrid: Boolean): android.os.Bundle? = if (!grid) null else android.os.Bundle().apply {
        putInt(androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            if (browsableGrid) androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            if (playableGrid) androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    /** An item listed under a heading ([title]) inside its folder, as Home's sections are. */
    private fun grouped(title: String) = android.os.Bundle().apply {
        putString(androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title)
    }

    fun root(): MediaItem = folder(ROOT, tr(R.string.app_name))

    /** The root's own hints for the car: lists of songs, folders as cover tiles (when the grid's on). */
    fun rootExtras(): android.os.Bundle? = styled(browsableGrid = true, playableGrid = false)

    fun recentRoot(): MediaItem = folder(RECENT, tr(R.string.recently_played), type = MediaMetadata.MEDIA_TYPE_PLAYLIST)

    suspend fun children(parentId: String): List<MediaItem> = when {
        // Four tabs, as cars show a root's first four folders along the top (Liked songs and Downloads open Library).
        parentId == ROOT -> listOf(
            folder(HOME, tr(R.string.home), extras = styled(browsableGrid = true, playableGrid = true)),
            folder(MADE_FOR_YOU, tr(R.string.made_for_you), type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, extras = styled(browsableGrid = true, playableGrid = false)),
            // Prism Car: Radio in Recents' place (Recently played moves into Library).
            if (BuildConfig.CAR_BUILD) folder(RADIO, tr(R.string.car_radio), type = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
                extras = styled(browsableGrid = false, playableGrid = true), fallback = RADIO_TILE)
            else folder(RECENT, tr(R.string.recents), type = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            folder(LIBRARY, tr(R.string.library), type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, extras = styled(browsableGrid = true, playableGrid = false)),
        )
        // Home: every section's albums, playlists and songs as tiles under its heading (with the grid), like Spotify's
        // home; without the grid, the sections as folders, as before.
        parentId == HOME -> {
            homeShelves = runCatching { c.ytm.fullHome(2) }.getOrDefault(homeShelves)
            if (!grid) homeShelves.mapIndexed { i, s -> folder("shelf:$i", s.title, s.strapline, s.items.firstOrNull()?.thumbnail) }
            else homeShelves.withIndex().flatMap { (i, s) ->
                val group = grouped(s.title)
                val shelfSongs = s.items.filterIsInstance<SongItem>().map { it.song }
                if (shelfSongs.isNotEmpty()) folders["shelf:$i"] = s.title to shelfSongs
                s.items.take(HOME_TILES).mapNotNull { item -> tile(item, group) }
            }
        }
        parentId.startsWith("shelf:") -> {
            val shelf = homeShelves.getOrNull(parentId.removePrefix("shelf:").toIntOrNull() ?: -1) ?: return emptyList()
            val shelfSongs = shelf.items.filterIsInstance<SongItem>().map { it.song }
            if (shelfSongs.isNotEmpty()) folders[parentId] = shelf.title to shelfSongs
            shelf.items.mapNotNull { item -> tile(item, null) }
        }
        parentId == MADE_FOR_YOU -> {
            // What's kept is listed straight away (the car waits for nothing); anything due is made behind it.
            c.scope.launch { runCatching { c.forYou.refresh() } }
            // The season's holiday mixes first, in November and December (1.8.0), each made when it's opened.
            val holiday = c.seasonal.shown().map { m ->
                val cover = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.prism.music.ui.screens.HolidayCovers.file(c.app, m) }
                folder(HOLIDAY + m.id, tr(m.name), tr(m.blurb), cover, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            holiday + c.forYou.mixes.filterNotNull().first().filter { it.songs.isNotEmpty() }.map { m ->
                folder("fy:${m.id}", m.title, m.subtitle, m.covers.firstOrNull(), MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
        }
        parentId.startsWith("fy:") -> c.forYou.mix(parentId.removePrefix("fy:"))?.let { songList(parentId, it.title, it.songs) } ?: emptyList()
        parentId.startsWith(HOLIDAY) -> c.seasonal.mix(parentId.removePrefix(HOLIDAY))?.let { songList(parentId, it.title, it.songs) } ?: emptyList()
        parentId == LIKED -> songList(parentId, tr(R.string.liked_songs), c.library.likedShown.value)
        parentId == RECENT -> songList(parentId, tr(R.string.recently_played), c.library.recent.value)
        // Prism Car's Radio: the favorite stations, then the most played in the Netherlands (each list plays on from the
        // station tapped, so next and previous go through its stations).
        parentId == RADIO -> {
            val favorites = c.radioStations.favorites()
            val popular = runCatching { c.radioStations.popular() }.getOrDefault(emptyList()).filter { s -> favorites.none { it.uuid == s.uuid } }
            stationList("${RADIO_LIST}fav", tr(R.string.car_radio_favorites), favorites) +
                stationList("${RADIO_LIST}nl", tr(R.string.car_radio_popular_nl), popular)
        }
        parentId == DOWNLOADS -> songList(parentId, tr(R.string.downloads), c.downloads.completedSongs())
        parentId == LIBRARY -> {
            var playlists = c.library.playlists.value
            if (playlists.isEmpty() && c.settings.current.isLoggedIn) playlists = runCatching { c.ytm.libraryPlaylists() }.getOrDefault(emptyList())
            // Prism Car: Recently played lives here, its tab being Radio's.
            val recent = if (BuildConfig.CAR_BUILD) listOf(folder(RECENT, tr(R.string.recently_played), type = MediaMetadata.MEDIA_TYPE_PLAYLIST))
                else emptyList<MediaItem>()
            recent + listOf(
                folder(LIKED, tr(R.string.liked_songs), art = c.covers.custom("liked"), type = MediaMetadata.MEDIA_TYPE_PLAYLIST, fallback = "ic_auto_liked"),
                folder(DOWNLOADS, tr(R.string.downloads), type = MediaMetadata.MEDIA_TYPE_PLAYLIST, fallback = "ic_auto_downloads"),
            ) +
                // Prism's own playlists (kept on the phone), as in the app's Library.
                c.prismLibrary.lists.value.map { l ->
                    val item = l.item()
                    folder(PRISM_LIST + l.id, l.title, item.subtitle, c.covers.art(item), MediaMetadata.MEDIA_TYPE_PLAYLIST)
                } +
                playlists.filterIsInstance<PlaylistItem>().filter { it.id != "LM" }.map {
                    folder(mixOrPlaylistId(it), it.title, it.subtitle, c.covers.art(it), MediaMetadata.MEDIA_TYPE_PLAYLIST)
                }
        }
        parentId.startsWith(PRISM_LIST) -> c.prismLibrary.list(parentId.removePrefix(PRISM_LIST))?.let { songList(parentId, it.title, it.songs) } ?: emptyList()
        parentId.startsWith("pl:") -> runCatching { c.ytm.playlistAll(parentId.removePrefix("pl:"), 200) }.getOrNull()
            ?.let { songList(parentId, it.title, it.songs) } ?: emptyList()
        parentId.startsWith("al:") -> runCatching { c.ytm.album(parentId.removePrefix("al:")) }.getOrNull()
            ?.let { songList(parentId, it.title, it.songs) } ?: emptyList()
        parentId.startsWith("mix:") -> runCatching {
            // "mix:playlistId|seedVideoId|params" (stations need their seed song and params to start).
            val parts = parentId.removePrefix("mix:").split('|')
            c.radio(parts.getOrNull(1)?.ifBlank { null }, parts[0], parts.getOrNull(2)?.ifBlank { null }, keepFirst = true)
        }.getOrNull()
            ?.let { r -> songList(parentId, tr(R.string.mix_2), r.songs) } ?: emptyList()
        parentId.startsWith("ar:") -> runCatching { c.ytm.artist(parentId.removePrefix("ar:")) }.getOrNull()
            ?.let { a -> songList(parentId, a.name, a.shelves.flatMap { s -> s.items.filterIsInstance<SongItem>().map { it.song } }.distinctBy { it.id }) }
            ?: emptyList()
        else -> emptyList()
    }

    /** One of Home's items for the car: a song to play, or an album, playlist or artist to open; [extras] its heading. */
    private fun tile(item: com.prism.music.data.model.BrowseItem, extras: android.os.Bundle?): MediaItem? = when (item) {
        is SongItem -> playable(item.song, extras)
        is AlbumItem -> folder("al:${item.id}", item.title, item.subtitle, item.thumbnail, MediaMetadata.MEDIA_TYPE_ALBUM, extras)
        is PlaylistItem -> folder(mixOrPlaylistId(item), item.title, item.subtitle, c.covers.art(item), MediaMetadata.MEDIA_TYPE_PLAYLIST, extras)
        is ArtistItem -> folder("ar:${item.id}", item.title, item.subtitle, item.thumbnail, MediaMetadata.MEDIA_TYPE_ARTIST, extras)
        else -> null
    }

    /** Radio stations under [title], as a list that plays on from the station tapped. */
    private fun stationList(id: String, title: String, stations: List<com.prism.music.data.radio.RadioStation>): List<MediaItem> {
        if (stations.isEmpty()) return emptyList()
        val list = stations.map { it.toSong() }
        folders[id] = title to list
        val heading = grouped(title)
        return list.map { playable(it, heading) }
    }

    private fun songList(id: String, title: String, list: List<Song>): List<MediaItem> {
        folders[id] = title to list
        // "Shuffle play" and "Play as a DJ set" rows first, as in the phone app (the car has no buttons of its own for them).
        val top = if (list.size > 1) listOf(
            startItem(SHUFFLE, id, tr(R.string.shuffle_play), com.prism.music.data.model.songCount(list.size), "ic_auto_shuffle"),
            startItem(DJ, id, tr(R.string.play_as_a_dj_set), tr(R.string.in_the_order_that_blends_best), "ic_auto_dj"),
        ) else emptyList()
        return top + list.map(::playable)
    }

    private fun startItem(prefix: String, folderId: String, title: String, subtitle: String, icon: String) = MediaItem.Builder().setMediaId("$prefix$folderId").setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUri(null, icon))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    ).build()

    /** The songs behind a "Shuffle play" or "Play as a DJ set" row, loading the folder again if it's not to hand. */
    suspend fun shuffleSongs(mediaId: String): Pair<String, List<Song>>? {
        val folderId = mediaId.removePrefix(SHUFFLE).removePrefix(DJ)
        folders[folderId]?.let { return it }
        children(folderId)
        return folders[folderId]
    }

    /**
     * The car's search (its search button, or a spoken search on its screen): what Search's Top results would show, under
     * their headings, after the listener's own playlists and liked songs that match (those show offline too). Kept per
     * query, so a page asked for later (or by another browser) is of that search, not of whichever ran last.
     */
    suspend fun search(query: String): Int = searchItems(query).size

    /** The results of [search] for [query], searching first if it hasn't been. */
    suspend fun searchItems(query: String): List<MediaItem> {
        val key = query.trim().lowercase()
        synchronized(searches) { searches[key] }?.let { return it }
        val (items, complete) = buildSearch(query.trim())
        // A search that couldn't reach Prism Music isn't kept: asked again, it tries again.
        if (complete) synchronized(searches) {
            searches[key] = items
            while (searches.size > KEPT_SEARCHES) searches.remove(searches.keys.first())
        }
        return items
    }

    /** The items, and whether Prism Music's own search answered (else they're only the listener's own). */
    private suspend fun buildSearch(query: String): Pair<List<MediaItem>, Boolean> {
        if (query.isEmpty()) return emptyList<MediaItem>() to true
        val yours = grouped(tr(R.string.your_music))
        val lists = ownPlaylists().filter { VoiceSearch.names(it.title, query) > 0 }.take(SEARCH_EACH)
            .map { folder(it.folderId, it.title, it.subtitle, it.art, MediaMetadata.MEDIA_TYPE_PLAYLIST, yours) }
        val liked = c.library.likedShown.value.filter { VoiceSearch.names("${it.title} ${it.artistText}", query) >= 2 }
            .take(SEARCH_EACH).map { playable(it, yours) }
        // Prism Car: radio stations by that name, from the directory.
        val stations = if (!BuildConfig.CAR_BUILD) emptyList<MediaItem>() else runCatching { c.radioStations.search(query) }.getOrDefault(emptyList())
            .take(SEARCH_EACH).let { stationList("${RADIO_LIST}search", tr(R.string.car_radio), it) }
        val taste = runCatching { c.taste.searchTaste() }.getOrDefault(com.prism.music.data.innertube.SearchRank.Taste.NONE)
        val found = runCatching { c.ytm.searchTop(query, taste) }.getOrNull()
        val top = found?.top?.let { tile(it, grouped(tr(R.string.top_results))) }
        val sections = found?.sections.orEmpty().filter { (f, _) -> f in SEARCH_KINDS }.flatMap { (f, items) ->
            val heading = grouped(f.label)
            items.asSequence().filter { it.id != found?.top?.id && (it as? SongItem)?.song?.isLive != true }
                .mapNotNull { tile(it, heading) }.take(SEARCH_EACH).toList()
        }
        // A song's folder, so tapping one plays the songs found from there on (as a tapped song in a list does).
        val foundSongs = (listOfNotNull((found?.top as? SongItem)?.song) +
            found?.sections.orEmpty().firstOrNull { it.first == SearchFilter.SONGS }?.second.orEmpty().filterIsInstance<SongItem>().map { it.song })
            .distinctBy { it.id }
        if (foundSongs.isNotEmpty()) folders[SEARCH] = query to foundSongs
        return (lists + liked + stations + listOfNotNull(top) + sections).distinctBy { it.mediaId } to (found != null)
    }

    /** A playlist of the listener's own, for the car: Prism's, then their YouTube library's. */
    private data class OwnList(val folderId: String, val title: String, val subtitle: String?, val art: String?)

    private fun ownPlaylists(): List<OwnList> =
        c.prismLibrary.lists.value.map { l -> l.item().let { OwnList(PRISM_LIST + l.id, l.title, it.subtitle, c.covers.art(it)) } } +
            c.library.playlists.value.filterIsInstance<PlaylistItem>().filter { it.id != "LM" }
                .map { OwnList(mixOrPlaylistId(it), it.title, it.subtitle, c.covers.art(it)) }

    // ------------------------------------------------------------ Voice ("play … on Prism")

    /** What a voice request plays: the songs, where to start, and the queue's title. [radio]: one song, a radio after it. */
    data class VoicePick(val title: String, val songs: List<Song>, val start: Int = 0, val radio: Boolean = false)

    /**
     * What [request] plays ([VoiceSearch]), or null when nothing was found. Network trouble is thrown, so the car can be
     * told it was that rather than "nothing found".
     */
    suspend fun voice(request: VoiceRequest): VoicePick? = when (request) {
        VoiceRequest.Resume -> null
        is VoiceRequest.Liked -> c.library.likedShown.value.takeIf { it.isNotEmpty() }?.let { VoicePick(tr(R.string.liked_songs), it) }
        is VoiceRequest.Downloads -> c.downloads.completedSongs().takeIf { it.isNotEmpty() }?.let { VoicePick(tr(R.string.downloads), it) }
        is VoiceRequest.Playlist -> ownPlaylist(request.name, exact = false) ?: foundPlaylist(request.name)
        is VoiceRequest.Artist -> artistPick(request.name) ?: songPick(request.name)
        is VoiceRequest.Album -> albumPick(request.title, request.artist) ?: songPick(listOfNotNull(request.title, request.artist).joinToString(" "))
        is VoiceRequest.Song -> songPick(request.text)
        // A playlist of the listener's own named exactly so comes before anything YouTube has under that name.
        is VoiceRequest.Top -> ownPlaylist(request.text, exact = true) ?: topPick(request.text)
    }

    private suspend fun ownPlaylist(name: String, exact: Boolean): VoicePick? {
        val best = ownPlaylists().map { it to VoiceSearch.names(it.title, name) }.filter { (_, s) -> if (exact) s >= 3 else s > 0 }
            .maxByOrNull { it.second }?.first ?: return null
        children(best.folderId)
        return folders[best.folderId]?.takeIf { it.second.isNotEmpty() }?.let { (title, list) -> VoicePick(title, list) }
    }

    private suspend fun foundPlaylist(name: String): VoicePick? {
        val item = listOf(SearchFilter.FEATURED_PLAYLISTS, SearchFilter.PLAYLISTS).firstNotNullOfOrNull { f ->
            c.ytm.search(name, f).items.filterIsInstance<PlaylistItem>().firstOrNull()
        } ?: return null
        return listPick(item)
    }

    private suspend fun artistPick(name: String): VoicePick? {
        val artists = c.ytm.search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>()
        val artist = artists.firstOrNull { VoiceSearch.names(it.title, name) >= 3 } ?: artists.firstOrNull() ?: return null
        val page = runCatching { c.ytm.artist(artist.id) }.getOrNull() ?: return null
        val list = page.shelves.flatMap { s -> s.items.filterIsInstance<SongItem>().map { it.song } }.filter { !it.isVideo }.distinctBy { it.id }
        return list.takeIf { it.isNotEmpty() }?.let { VoicePick(page.name, it) }
    }

    private suspend fun albumPick(title: String, artist: String?): VoicePick? {
        val albums = c.ytm.search(listOfNotNull(title, artist).joinToString(" "), SearchFilter.ALBUMS).items.filterIsInstance<AlbumItem>()
        val album = albums.firstOrNull { VoiceSearch.names(it.title, title) >= 3 } ?: albums.firstOrNull() ?: return null
        val page = runCatching { c.ytm.album(album.id) }.getOrNull() ?: return null
        return page.songs.takeIf { it.isNotEmpty() }?.let { VoicePick(page.title, it) }
    }

    /** The best song for [text] (the listener's own taste first, as in Search), and a radio after it. */
    private suspend fun songPick(text: String): VoicePick? {
        val found = c.ytm.search(text, SearchFilter.SONGS).items.filterIsInstance<SongItem>()
        val taste = runCatching { c.taste.searchTaste() }.getOrDefault(com.prism.music.data.innertube.SearchRank.Taste.NONE)
        val best = found.withIndex().maxByOrNull { (i, item) -> com.prism.music.data.innertube.SearchRank.score(text, item, i, taste) }?.value?.song
            ?: return null
        songs[best.id] = best
        return VoicePick(tr(R.string.autoplay), listOf(best), radio = true)
    }

    /** Whatever Search's Top result is for [text], played as tapping it would. */
    private suspend fun topPick(text: String): VoicePick? {
        val taste = runCatching { c.taste.searchTaste() }.getOrDefault(com.prism.music.data.innertube.SearchRank.Taste.NONE)
        val found = c.ytm.searchTop(text, taste)
        val candidates = listOfNotNull(found.top) + found.sections.firstOrNull { it.first == SearchFilter.SONGS }?.second.orEmpty()
        for (item in candidates) {
            val pick = when (item) {
                is SongItem -> if (item.song.isLive || item.song.isEpisode) null else {
                    songs[item.song.id] = item.song
                    VoicePick(tr(R.string.autoplay), listOf(item.song), radio = true)
                }
                is AlbumItem -> runCatching { c.ytm.album(item.id) }.getOrNull()?.takeIf { it.songs.isNotEmpty() }?.let { VoicePick(it.title, it.songs) }
                is ArtistItem -> artistPick(item.title)
                is PlaylistItem -> listPick(item)
                else -> null
            }
            if (pick != null) return pick
        }
        return null
    }

    /** A playlist's songs, or a mix's / station's radio. */
    private suspend fun listPick(item: PlaylistItem): VoicePick? {
        val id = mixOrPlaylistId(item)
        children(id)
        return folders[id]?.takeIf { it.second.isNotEmpty() }?.let { (title, list) -> VoicePick(item.title.ifBlank { title }, list) }
    }

    suspend fun item(mediaId: String): MediaItem? = song(mediaId)?.let(::playable)

    /** Turns whatever a controller sent back into a playable song. */
    suspend fun song(mediaId: String, fallback: MediaItem? = null): Song? =
        songs[mediaId] ?: fallback?.toSong() ?: c.db.songs().get(mediaId)?.toSong()
            ?: fallback?.mediaMetadata?.title?.let { Song(mediaId, it.toString()) }

    /** The folder a tapped song was listed in, for playing the rest of it after. */
    fun folderOf(mediaId: String): Pair<String, List<Song>>? =
        if (mediaId.startsWith(SHUFFLE)) null else folders.values.firstOrNull { (_, list) -> list.any { it.id == mediaId } }

    /**
     * A picture for the car, through [ArtworkProvider]. [fallback]: one of Prism's own `ic_auto_` tiles, shown when
     * there's no picture or it can't be had (the car leaves a tile blank, or draws a warning sign, otherwise).
     */
    private fun artworkUri(url: String?, fallback: String? = null): Uri? {
        val u = hiRes(url, 544)
        if (u != null && u.startsWith("android.resource:")) return Uri.parse(u)
        val target = u ?: fallback?.let { ArtworkProvider.RES + it } ?: return null
        val uri = ArtworkProvider.contentUri(target)
        return if (u != null && fallback != null) uri.buildUpon().appendQueryParameter(ArtworkProvider.OR, fallback).build() else uri
    }
}

/** The tile for a playlist, mix or album whose cover is missing. */
private const val AUTO_PLAYLIST = "ic_auto_playlist"

/**
 * Serves cover art to Android Auto, which only loads content:// images for
 * browse lists. Downloads the (Google-hosted) image once and caches it.
 */
class ArtworkProvider : ContentProvider() {
    private val allowedHosts = listOf("googleusercontent.com", "ggpht.com", "ytimg.com", "gstatic.com")
    /**
     * YouTube Music's own mixes in the library (My Supermix, Discover mix, Replay mix…) have their covers drawn by
     * music.youtube.com itself (`/image/mixart?…`): only that path is taken from there.
     */
    private fun youTubeImage(host: String, path: String?) =
        (host == "music.youtube.com" || host == "www.youtube.com") && path?.startsWith("/image/") == true

    override fun onCreate(): Boolean {
        // The old cache kept YouTube's PNGs under a JPEG name and type; this one only holds JPEGs.
        context?.let { ctx -> Thread { File(ctx.cacheDir, "auto_art").deleteRecursively() }.start() }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val url = runCatching {
            String(Base64.decode(uri.lastPathSegment ?: return null, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        }.getOrNull() ?: return null
        return runCatching { picture(ctx, url) }.getOrNull()
            ?: uri.getQueryParameter(OR)?.let { runCatching { tile(ctx, it) }.getOrNull() }
    }

    private fun picture(ctx: android.content.Context, url: String): ParcelFileDescriptor? {
        if (url.startsWith(RES)) return tile(ctx, url.removePrefix(RES))
        // A playlist picture the listener chose, kept in the app's own covers folder.
        if (url.startsWith("file:")) {
            val file = File(Uri.parse(url).path ?: return null)
            val covers = File(ctx.filesDir, "covers").canonicalPath
            // ...or a holiday mix's cover, drawn by Prism (1.8.0).
            val holiday = File(ctx.filesDir, "seasonal").canonicalPath
            val path = file.canonicalPath
            if (!(path.startsWith(covers + File.separator) || path.startsWith(holiday + File.separator)) || !file.exists()) return null
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        // A local song's album art (Library → On this phone), passed on from Android's media store; nothing else there.
        if (url.startsWith(LOCAL_ALBUM_ART)) {
            val id = url.removePrefix(LOCAL_ALBUM_ART).toLongOrNull() ?: return null
            return runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(LOCAL_ALBUM_ART + id), "r") }.getOrNull()
        }
        val parsed = Uri.parse(url)
        val host = parsed.host ?: return null
        // A radio station's logo (Prism Car) is on the station's own site: only the logos the directory listed are fetched.
        val radioLogo = com.prism.music.data.radio.RadioDirectory.isLogo(url)
        if (!radioLogo && (!url.startsWith("https://") || (allowedHosts.none { host.endsWith(it) } && !youTubeImage(host, parsed.path)))) return null
        val container = runCatching { (ctx.applicationContext as? com.prism.music.PrismApp)?.container }.getOrNull()
        return cached(ctx, url) {
            // A cover already kept on the phone (what the app itself shows, offline too), else from the network.
            container?.artwork?.fileFor(url)?.takeIf { it.exists() }?.readBytes()
                ?: (container?.http ?: okhttp3.OkHttpClient()).newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body.bytes() else null
                }
        }
    }

    /** One of Prism's own `ic_auto_` tiles, drawn once per version of the app. */
    private fun tile(ctx: android.content.Context, name: String): ParcelFileDescriptor? {
        if (!name.matches(Regex("ic_auto_[a-z_]+"))) return null
        @Suppress("DiscouragedApi")
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName).takeIf { it != 0 } ?: return null
        return cached(ctx, "$RES$name@${BuildConfig.VERSION_CODE}") {
            val d = androidx.core.content.ContextCompat.getDrawable(ctx, id) ?: return@cached null
            val bitmap = android.graphics.Bitmap.createBitmap(TILE_PX, TILE_PX, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, TILE_PX, TILE_PX)
            d.draw(android.graphics.Canvas(bitmap))
            java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }

    /**
     * The picture for [key] from the cache, else made by [make] and kept as a JPEG. Every picture goes to the car as
     * a JPEG, as [getType] says: YouTube's own mixes and "Liked Music" come as PNGs, and those were left blank.
     */
    private fun cached(ctx: android.content.Context, key: String, make: () -> ByteArray?): ParcelFileDescriptor? {
        val name = MessageDigest.getInstance(tr(R.string.sha_1)).digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(ctx.cacheDir, "auto_art2").apply { mkdirs() }
        val file = File(dir, "$name.jpg")
        if (!file.exists()) {
            val jpeg = make()?.let(::asJpeg) ?: return null
            val tmp = File(dir, "$name.${Thread.currentThread().id}.tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(file)) { tmp.delete(); if (!file.exists()) return null }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    companion object {
        /** In place of an address: one of Prism's own `ic_auto_` tiles ([MediaTree]'s Downloads, Shuffle play…). */
        const val RES = "res:"
        /** The query parameter naming the tile shown when the picture can't be had. */
        const val OR = "or"
        private const val LOCAL_ALBUM_ART = "content://media/external/audio/albumart/"
        private const val TILE_PX = 288

        /** [url] served through this provider (a car can't open the app's own files, nor every web address). */
        fun contentUri(url: String): Uri {
            val encoded = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return Uri.parse("content://${BuildConfig.APPLICATION_ID}.artwork/$encoded")
        }
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/**
 * [bytes] as a JPEG: as they are when they already are one, else decoded and drawn on an opaque ground (a tile's
 * rounded corners take the tile's own color, a see-through picture goes on near-black). Null if it isn't a picture.
 */
internal fun asJpeg(bytes: ByteArray): ByteArray? {
    if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return bytes
    val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val edge = src.getPixel(src.width / 2, minOf(1, src.height - 1))
    val ground = if (android.graphics.Color.alpha(edge) == 255) edge else 0xFF121212.toInt()
    val out = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).apply { drawColor(ground); drawBitmap(src, 0f, 0f, null) }
    return java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
}

/** A playlist's browse folder id; mixes and stations keep their seed song and params for [MediaTree]'s "mix:" folders. */
private fun mixOrPlaylistId(item: PlaylistItem): String =
    if (!item.isMix) "pl:${item.id}"
    else if (item.seedVideoId == null && item.params == null) "mix:${item.id}"
    else tr(R.string.mix_3, item.id, item.seedVideoId.orEmpty(), item.params.orEmpty())

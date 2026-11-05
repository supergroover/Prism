package com.prism.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes

const val EXTRA_SONG = "prism.song"
const val EXTRA_VIDEO = "prism.video"

/**
 * prism://audio/<id>[?video=<videoId>] – resolved to a real stream URL at load time.
 * Live streams are prism://live/<id>, resolved to their HLS manifest, which carries the picture too: their video mode
 * keeps the same URI (so switching doesn't reload the stream) and only turns the video track on.
 */
fun Song.toMediaItem(videoId: String? = null): MediaItem {
    if (isRadio) return radioMediaItem()
    val uri = if (isLive) Uri.Builder().scheme("prism").authority("live").appendPath(id).build()
    else Uri.Builder().scheme("prism").authority("audio").appendPath(id).apply {
        if (videoId != null) appendQueryParameter("video", videoId)
        // The listener's own upload (#101): asked for as their account from the start.
        if (isUpload) appendQueryParameter("upload", "1")
    }.build()
    val extras = Bundle().apply {
        putString(EXTRA_SONG, InnerTube.json.encodeToString(Song.serializer(), this@toMediaItem))
        if (videoId != null) putString(EXTRA_VIDEO, videoId)
    }
    // #328: shown as the version picked to play in its place (the item itself stays the song, for history and likes).
    val shown = com.prism.music.data.SongVersions.shownOf(this)
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setCustomCacheKey(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(shown.title)
                .setArtist(shown.artistText)
                .setAlbumTitle(shown.album?.title)
                // The cover picked for the song or its album (#305), through the artwork provider so a car can show it too.
                .setArtworkUri(com.prism.music.data.PlaylistCovers.artOf(this@toMediaItem).let { art ->
                    if (art != null && art.startsWith("file:")) ArtworkProvider.contentUri(art) else hiRes(art, 544)?.let(Uri::parse)
                })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(if (isEpisode) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build()
        )
        .build()
}

/**
 * A radio station (Prism Car's Radio tab): its stream's own address, which nothing caches, with HLS marked as such; or
 * prism://radio/<id> when the station isn't known yet, looked up as it loads ([com.prism.music.AppContainer]). Always
 * live, so nothing measures, blends, downloads or slows it as it would a song; its logo goes through [ArtworkProvider],
 * with Prism's radio tile when there's none.
 */
private fun Song.radioMediaItem(): MediaItem {
    val song = if (isLive) this else copy(isLive = true)
    val uuid = id.removePrefix(Song.RADIO_PREFIX)
    val station = com.prism.music.data.radio.RadioDirectory.station(uuid)
    val logo = thumbnail ?: station?.logo
    val art = (if (logo != null) ArtworkProvider.contentUri(logo) else ArtworkProvider.contentUri(ArtworkProvider.RES + RADIO_TILE))
        .buildUpon().appendQueryParameter(ArtworkProvider.OR, RADIO_TILE).build()
    val extras = Bundle().apply { putString(EXTRA_SONG, InnerTube.json.encodeToString(Song.serializer(), song)) }
    return MediaItem.Builder()
        .setMediaId(id)
        .apply {
            if (station != null) {
                setUri(station.stream)
                if (station.isHls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            } else setUri(Uri.Builder().scheme("prism").authority("radio").appendPath(uuid).build())
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artistText)
                .setArtworkUri(art)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setExtras(extras)
                .build()
        )
        .build()
}

/** Prism's own tile for a station with no logo (res/drawable). */
const val RADIO_TILE = "ic_auto_radio"

fun MediaItem.toSong(): Song? = mediaMetadata.extras?.getString(EXTRA_SONG)?.let {
    runCatching { InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull()
}

val MediaItem.videoModeId: String? get() = mediaMetadata.extras?.getString(EXTRA_VIDEO)

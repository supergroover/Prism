package com.prism.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Builds audio-only sources normally, and an audio+video merged source when a
 * queue item is switched to its music video. The audio source is always first
 * so the timeline keeps the song's metadata.
 */
@OptIn(UnstableApi::class)
class PrismMediaSourceFactory(
    audioDsf: DataSource.Factory,
    videoDsf: DataSource.Factory,
    localDsf: DataSource.Factory? = null,
    /** A lossless file on the phone to play instead of the stream (the lossless add-on). */
    private val localAudio: ((MediaItem) -> Uri?)? = null,
    /** Live streams: prism://live/<id> resolved to the stream's HLS manifest. */
    liveDsf: DataSource.Factory? = null,
    /** Radio stations (Prism Car): their own stream's address, or prism://radio/<id> looked up as it loads. Never cached. */
    radioDsf: DataSource.Factory? = null,
) : MediaSource.Factory {
    private val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    private val audio = ProgressiveMediaSource.Factory(audioDsf, extractors)
    private val video = ProgressiveMediaSource.Factory(videoDsf, extractors)
    private val local = localDsf?.let { ProgressiveMediaSource.Factory(it, extractors) }
    private val live = liveDsf?.let { HlsMediaSource.Factory(it).setPlaylistParserFactory(BestLiveAudioParserFactory()) }
    // A station's stream has no length to seek in (and its own extractors: no constant-bitrate seeking).
    private val radio = radioDsf?.let { ProgressiveMediaSource.Factory(it, DefaultExtractorsFactory()) }
    private val radioHls = radioDsf?.let { HlsMediaSource.Factory(it) }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        audio.setDrmSessionManagerProvider(drmSessionManagerProvider)
        local?.setDrmSessionManagerProvider(drmSessionManagerProvider)
        video.setDrmSessionManagerProvider(drmSessionManagerProvider)
        live?.setDrmSessionManagerProvider(drmSessionManagerProvider)
        radio?.setDrmSessionManagerProvider(drmSessionManagerProvider)
        radioHls?.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        audio.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        local?.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        video.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        live?.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        radio?.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        radioHls?.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        if (radio != null && radioHls != null && mediaItem.mediaId.startsWith(com.prism.music.data.model.Song.RADIO_PREFIX)) {
            val item = mediaItem.buildUpon().setCustomCacheKey(null).apply {
                if (mediaItem.localConfiguration == null) {
                    setUri(Uri.Builder().scheme("prism").authority("radio").appendPath(mediaItem.mediaId.removePrefix(com.prism.music.data.model.Song.RADIO_PREFIX)).build())
                }
            }.build()
            return if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) radioHls.createMediaSource(item) else radio.createMediaSource(item)
        }
        if (live != null && mediaItem.localConfiguration?.uri?.authority == "live") {
            return live.createMediaSource(mediaItem.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).setCustomCacheKey(null).build())
        }
        val videoId = mediaItem.localConfiguration?.uri?.getQueryParameter("video")
        if (videoId == null) {
            if (local != null) localAudio?.invoke(mediaItem)?.let { uri ->
                return local.createMediaSource(mediaItem.buildUpon().setUri(uri).setCustomCacheKey(null).build())
            }
            val item = if (mediaItem.localConfiguration == null) {
                mediaItem.buildUpon().setUri("prism://audio/${mediaItem.mediaId}").setCustomCacheKey(mediaItem.mediaId).build()
            } else mediaItem
            return audio.createMediaSource(item)
        }
        // A distinct URI marks the audio leg so toggling back rebuilds the source.
        val audioItem = mediaItem.buildUpon()
            .setUri(Uri.parse("prism://audio/$videoId?leg=video"))
            .setCustomCacheKey(videoId)
            .build()
        val videoItem = MediaItem.Builder().setMediaId("video:$videoId").setUri("prism://video/$videoId").build()
        return MergingMediaSource(true, false, audio.createMediaSource(audioItem), video.createMediaSource(videoItem))
    }
}

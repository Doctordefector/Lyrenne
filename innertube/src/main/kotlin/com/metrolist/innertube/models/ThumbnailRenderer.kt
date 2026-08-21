package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class ThumbnailRenderer(
    val musicThumbnailRenderer: MusicThumbnailRenderer?,
    val musicAnimatedThumbnailRenderer: MusicAnimatedThumbnailRenderer?,
    val croppedSquareThumbnailRenderer: MusicThumbnailRenderer?,
) {
    @Serializable
    data class MusicThumbnailRenderer(
        // Defaults so a tile whose renderer carries no usable thumbnail still decodes; the
        // item then surfaces with a null thumbnail URL instead of aborting the whole response.
        val thumbnail: Thumbnails = Thumbnails(emptyList()),
        val thumbnailCrop: String?,
        val thumbnailScale: String?,
    ) {
        fun getThumbnailUrl() = thumbnail.thumbnails.lastOrNull()?.url
    }

    fun getThumbnailUrl(): String? =
        musicThumbnailRenderer?.getThumbnailUrl()
            ?: musicAnimatedThumbnailRenderer?.backupRenderer?.getThumbnailUrl()
            ?: croppedSquareThumbnailRenderer?.getThumbnailUrl()

    @Serializable
    data class MusicAnimatedThumbnailRenderer(
        val animatedThumbnail: Thumbnails = Thumbnails(emptyList()),
        val backupRenderer: MusicThumbnailRenderer? = null,
    )
}

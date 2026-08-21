package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnails(
    // YouTube occasionally omits the key entirely (e.g. a playlist tile with no artwork, like an
    // empty playlist). Without a default the missing field throws out of the whole response
    // decode, so one such tile would discard every item on the page.
    val thumbnails: List<Thumbnail> = emptyList(),
)

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int?,
    val height: Int?,
)

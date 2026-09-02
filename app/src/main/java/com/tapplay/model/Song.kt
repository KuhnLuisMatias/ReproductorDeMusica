package com.tapplay.model

data class Song(
    val uri: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artBytes: ByteArray?,
    val lastModified: Long? = null,
    val addedAtMs: Long? = null,
)

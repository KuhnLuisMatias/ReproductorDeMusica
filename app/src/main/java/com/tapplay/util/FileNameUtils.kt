package com.tapplay.util

/** Extension filter and file-name helpers for the supported audio formats. */
object FileNameUtils {
    val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "wav", "ogg", "aac", "m4a", "wma")

    fun isSupportedAudio(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        return extension.lowercase() in SUPPORTED_EXTENSIONS
    }

    fun titleFromFileName(fileName: String): String =
        if (fileName.contains('.')) {
            fileName.substringBeforeLast('.')
        } else {
            fileName
        }
}

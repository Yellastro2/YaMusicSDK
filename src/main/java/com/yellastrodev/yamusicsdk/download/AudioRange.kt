package com.yellastrodev.yamusicsdk.download

/** Проверенные границы ответа аудиосервера; offset относится к полному файлу. */
data class AudioRange(
    val offset: Long,
    val length: Long,
    val totalLength: Long,
    val entityTag: String?,
)

package com.yellastrodev.yamusicsdk.covers

import com.yellastrodev.yamusicsdk.entities.CoverSize
import com.yellastrodev.yamusicsdk.network.YamContentTransport
import com.yellastrodev.yamusicsdk.network.YamError
import com.yellastrodev.yamusicsdk.network.YamResult

internal class CoverApi(
    private val contentTransport: YamContentTransport
) {
    suspend fun bytes(uri: String, size: CoverSize): YamResult<ByteArray> {
        if (uri.isBlank()) {
            return YamResult.Failure(
                YamError.InvalidResponse(
                    IllegalArgumentException("URI обложки не должен быть пустым")
                )
            )
        }
        val sizedUri = uri.replace("%%", size.toString())
        val url = if (
            sizedUri.startsWith("https://") ||
            sizedUri.startsWith("http://")
        ) {
            sizedUri
        } else {
            "https://$sizedUri"
        }
        return contentTransport.retrieve(
            url = url,
            requiresAuthorization = true
        )
    }
}

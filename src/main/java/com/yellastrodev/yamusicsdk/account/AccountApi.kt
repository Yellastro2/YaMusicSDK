package com.yellastrodev.yamusicsdk.account

import com.yellastrodev.yamusicsdk.network.YamHttpMethod
import com.yellastrodev.yamusicsdk.network.YamHttpRequest
import com.yellastrodev.yamusicsdk.network.YamResponseDecoder
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.network.YamTransport

internal class AccountApi(
    private val transport: YamTransport
) {
    suspend fun status(): YamResult<AccountStatus> {
        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/account/status"
                )
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response = response.value,
                resultSerializer = AccountStatus.serializer()
            )
            is YamResult.Failure -> response
        }
    }
}

package com.naudio.core.network

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.serialization.ContentConvertException
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException

/**
 * Sealed hierarchy for network failures. [NaudioHttpClient] maps transport and
 * serialization failures onto this hierarchy so callers handle one taxonomy.
 * The original transport error is preserved as the cause wherever one exists.
 * Cancellation never lands here; it propagates untouched.
 */
sealed class NetworkException(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** DNS failure / unreachable host / connection refused. */
    class Connectivity(message: String? = null, cause: Throwable? = null) : NetworkException(message, cause)

    /** A connection or read timed out. */
    class Timeout(message: String? = null, cause: Throwable? = null) : NetworkException(message, cause)

    /** HTTP error status (4xx/5xx) with the code preserved. */
    class HttpError(val code: Int, message: String? = null, cause: Throwable? = null) : NetworkException(message, cause)

    /** Response body could not be parsed as JSON. */
    class Serialization(message: String? = null, cause: Throwable? = null) : NetworkException(message, cause)

    /** Anything not covered above. */
    class Unknown(message: String? = null, cause: Throwable? = null) : NetworkException(message, cause)
}

/**
 * Map an arbitrary transport/decoding failure to a [NetworkException] so
 * NaudioHttpClient.retryWithBackoff can decide whether to retry. The original
 * error is preserved as the cause.
 *
 * ExpectSuccess is enabled, so 4xx arrives as [ClientRequestException] and
 * 5xx as [ServerResponseException]; their response status is preserved in
 * [NetworkException.HttpError.code]. Cancellation is rethrown untouched.
 */
internal fun Throwable.toNetworkException(): NetworkException = when (this) {
    is NetworkException -> this
    is kotlinx.coroutines.CancellationException -> throw this
    // Ktor client plugin exceptions (expectSuccess = true).
    is ClientRequestException -> NetworkException.HttpError(response.status.value, message, this)
    is ServerResponseException -> NetworkException.HttpError(response.status.value, message, this)
    is HttpRequestTimeoutException -> NetworkException.Timeout(message, this)
    is ConnectTimeoutException -> NetworkException.Timeout(message, this)
    is SocketTimeoutException -> NetworkException.Timeout(message, this)
    // Transport-level failures.
    is UnknownHostException -> NetworkException.Connectivity(message, this)
    is ConnectException -> NetworkException.Connectivity(message, this)
    is SocketException -> NetworkException.Connectivity(message, this)
    // Body decoding (ContentNegotiation wraps converter failures in
    // ContentConvertException; a bare SerializationException is also possible).
    is ContentConvertException -> NetworkException.Serialization(message, this)
    is SerializationException -> NetworkException.Serialization(message, this)
    else -> NetworkException.Unknown(message ?: "Unknown network failure", this)
}

/**
 * Whether [NetworkException.HttpError.code] is 5xx (the only retryable HTTP
 * range). 4xx, including authentication failures, is permanent.
 */
internal fun NetworkException.HttpError.isRetryable(): Boolean = code in 500..599

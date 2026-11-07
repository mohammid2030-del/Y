package com.nova.iptv.data

enum class ErrorKind {
    NETWORK,
    TIMEOUT,
    INVALID_URL,
    AUTH_FAILED,
    EXPIRED,
    HTTP,
    NOT_PLAYLIST,
    PARSE,
    FILE,
    UNKNOWN,
}

class NovaException(
    val kind: ErrorKind,
    val httpCode: Int = 0,
    cause: Throwable? = null,
) : Exception(kind.name + if (httpCode != 0) " $httpCode" else "", cause)

/** Never let raw exception messages (which may contain credentials) reach logs or UI. */
fun Throwable.toNovaException(): NovaException = when (this) {
    is NovaException -> this
    is java.net.SocketTimeoutException -> NovaException(ErrorKind.TIMEOUT, cause = this)
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is javax.net.ssl.SSLException,
    is java.io.InterruptedIOException,
    is java.net.SocketException -> NovaException(ErrorKind.NETWORK, cause = this)
    is IllegalArgumentException -> NovaException(ErrorKind.INVALID_URL, cause = this)
    is java.io.FileNotFoundException, is SecurityException -> NovaException(ErrorKind.FILE, cause = this)
    is java.io.IOException -> NovaException(ErrorKind.NETWORK, cause = this)
    else -> NovaException(ErrorKind.UNKNOWN, cause = this)
}

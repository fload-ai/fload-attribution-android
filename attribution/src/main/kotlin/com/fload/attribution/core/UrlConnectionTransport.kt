package com.fload.attribution.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One POST over `HttpURLConnection`: no cookies, no cache, no redirects. */
internal class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : Transport {
    override fun send(url: String, headers: Map<String, String>, body: ByteArray): TransportResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")
            drain(connection, status)
            return TransportResponse(status, retryAfter)
        } finally {
            connection.disconnect()
        }
    }

    /** Reads the small answer so the connection can be reused. */
    private fun drain(connection: HttpURLConnection, status: Int) {
        try {
            (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
        } catch (error: IOException) {
            // The status is what counts.
        }
    }
}

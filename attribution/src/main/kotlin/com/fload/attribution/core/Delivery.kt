package com.fload.attribution.core

import kotlin.math.pow

/** What to do with a request after one attempt. */
internal sealed class Outcome {
    /** 2xx: Fload has it (a repeat included). Delete it. */
    object Delivered : Outcome()

    /** Fload will never take it (400, 413). Delete it. */
    data class Dropped(val status: Int) : Outcome()

    /** 401: the key is unknown to Fload. Keep the queue and pause this key for a day. */
    object KeyRefused : Outcome()

    /** 410: the key was revoked. Stop sending with it, for good. */
    object Revoked : Outcome()

    /** 429: wait what the server asked, then go on. */
    data class RateLimited(val afterMs: Long) : Outcome()

    /** 5xx, an unexpected status, or no answer: back off and retry. */
    object Retry : Outcome()
}

internal object Delivery {
    /** A request older than this is dropped unsent. */
    const val MAX_AGE_MS: Long = 30L * 24 * 3600 * 1000

    /** The most identifies kept; the server keeps 10 user ids per install. */
    const val MAX_QUEUED_IDENTIFIES: Int = 20

    /** Times the referrer is read again before the install goes without it. */
    const val MAX_REFERRER_RETRIES: Int = 3

    /** How long a refused (401) key waits before it is tried again. */
    const val REFUSED_KEY_PAUSE_MS: Long = 24L * 3600 * 1000

    fun outcome(response: TransportResponse): Outcome = when (response.status) {
        in 200..299 -> Outcome.Delivered
        400, 413 -> Outcome.Dropped(response.status)
        401 -> Outcome.KeyRefused
        410 -> Outcome.Revoked
        429 -> Outcome.RateLimited(retryAfterMs(response.retryAfter))
        else -> Outcome.Retry
    }

    /** `Retry-After` in seconds, between 1 s and 1 h; a minute when absent or a date. */
    fun retryAfterMs(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull() ?: return 60_000
        return seconds.coerceIn(1, 3600) * 1000
    }
}

/**
 * Exponential backoff: 2 s after the first failure, doubling, at most 6 h,
 * each delay scaled by a jitter factor in [0.5, 1].
 */
internal object Backoff {
    const val BASE_MS: Long = 2_000
    const val CAP_MS: Long = 6L * 3600 * 1000

    fun delayMs(failures: Int, jitter: Double): Long {
        val exponent = (failures.coerceIn(1, 32) - 1).toDouble()
        val raw = minOf(CAP_MS.toDouble(), BASE_MS * 2.0.pow(exponent))
        return (raw * jitter.coerceIn(0.5, 1.0)).toLong()
    }
}

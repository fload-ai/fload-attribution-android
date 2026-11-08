package com.fload.attribution.core

import java.io.IOException

internal data class TransportResponse(val status: Int, val retryAfter: String? = null)

internal fun interface Transport {
    /** Throws when no HTTP answer came back (offline, timeout, TLS). */
    @Throws(IOException::class)
    fun send(url: String, headers: Map<String, String>, body: ByteArray): TransportResponse
}

/** Reads the Play Install Referrer; blocks the SDK's own thread, never the caller's. */
internal fun interface ReferrerSource {
    fun read(): ReferrerResult
}

internal sealed class LoadResult {
    data class Loaded(val state: StoredState) : LoadResult()

    /** No state yet: the first launch with the SDK. */
    object Missing : LoadResult()

    /** The file is there but cannot be read now. */
    object Unreadable : LoadResult()

    /** The file is there but does not decode. */
    object Corrupt : LoadResult()
}

internal interface StateStore {
    fun load(): LoadResult

    @Throws(IOException::class)
    fun save(state: StoredState)
}

/** Fixed text only: never the write key, the referrer or a user id. */
internal interface SdkLog {
    fun error(message: String)
    fun warning(message: String)
    fun info(message: String)
    fun debug(message: String)
}

internal fun interface Cancellable {
    fun cancel()
}

internal fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/** Everything the engine reaches outside itself, so tests can stand in for it. */
internal class Environment(
    val endpoint: String,
    val transport: Transport,
    val referrer: ReferrerSource,
    val store: StateStore,
    val device: () -> DeviceFacts,
    val now: () -> Long,
    /** A factor in [0.5, 1] applied to each backoff delay. */
    val jitter: () -> Double,
    val makeId: () -> String,
    val log: SdkLog,
    /** Wakes the engine when its next request is due; null in tests, which call `flush()` themselves. */
    val scheduler: Scheduler?,
    /** The wrapper every request names, or null for an app calling the SDK directly. */
    val wrapper: WrapperInfo? = null,
)

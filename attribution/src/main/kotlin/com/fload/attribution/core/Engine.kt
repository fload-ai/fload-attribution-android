package com.fload.attribution.core

/**
 * The queue's owner: sends due requests one at a time, oldest first, and
 * keeps the queue on disk after every change. Not thread-safe: the SDK
 * calls it only from its own single thread.
 */
internal class Engine(
    private val key: String,
    initial: StoredState,
    private val env: Environment,
) {
    private val fingerprint = WriteKey.fingerprint(key)
    private var warnedKeyRefused = false
    private var wake: Cancellable? = null

    var state: StoredState = initial
        private set

    // User id

    fun setUserId(raw: String) {
        val userId = Text.appUserId(raw)
        if (userId == null) {
            env.log.warning("Fload.setUserId: the id must be 1 to 256 characters; ignored.")
            return
        }
        if (userId == state.lastUserId) return
        val install = state.queue.indexOfFirst { it.kind == RequestKind.INSTALL }
        val queue = state.queue.toMutableList()
        if (install >= 0 && queue[install].appUserId == null) {
            queue[install] = queue[install].copy(appUserId = userId)
        } else {
            val now = env.now()
            queue += QueuedRequest(
                requestId = env.makeId(),
                kind = RequestKind.IDENTIFY,
                createdAt = now,
                nextAttemptAt = now,
                appUserId = userId,
            )
            trimIdentifies(queue)
        }
        state = state.copy(queue = queue, lastUserId = userId)
        persist()
    }

    private fun trimIdentifies(queue: MutableList<QueuedRequest>) {
        var excess = queue.count { it.kind == RequestKind.IDENTIFY } - Delivery.MAX_QUEUED_IDENTIFIES
        val iterator = queue.iterator()
        while (excess > 0 && iterator.hasNext()) {
            if (iterator.next().kind == RequestKind.IDENTIFY) {
                iterator.remove()
                excess -= 1
            }
        }
    }

    // Sending

    /** Sends every due request, in order, until one has to wait. */
    fun flush() {
        sendDue()
        scheduleWake()
    }

    private val revoked: Boolean get() = fingerprint in state.revokedKeys
    private val refusedUntil: Long? get() = state.refusedKeys[fingerprint]

    private fun sendDue() {
        if (revoked) return
        dropExpired()
        val until = refusedUntil
        if (until != null && until > env.now()) {
            warnKeyRefused()
            return
        }
        while (true) {
            val head = state.queue.firstOrNull() ?: return
            if (head.nextAttemptAt > env.now()) return
            if (!apply(attempt(head), head.requestId)) return
        }
    }

    private fun dropExpired() {
        val now = env.now()
        val kept = state.queue.filter { now - it.createdAt <= Delivery.MAX_AGE_MS }
        if (kept.size != state.queue.size) {
            env.log.warning("Fload: dropped requests older than 30 days.")
            state = state.copy(queue = kept)
            persist()
        }
    }

    private sealed class Attempt {
        data class Sent(val outcome: Outcome) : Attempt()

        /** The referrer could not be read yet; try the install again later. */
        object AwaitingReferrer : Attempt()
    }

    private fun attempt(request: QueuedRequest): Attempt {
        val body: ByteArray
        val path: String
        when (request.kind) {
            RequestKind.INSTALL -> {
                val referrer = referrerFor(request) ?: return Attempt.AwaitingReferrer
                body = Wire.install(request.requestId, state, referrer, request.appUserId, env.wrapper)
                path = Wire.INSTALLS_PATH
            }
            RequestKind.IDENTIFY -> {
                body = Wire.identify(request.requestId, state.installReference, request.appUserId.orEmpty(), env.wrapper)
                path = Wire.IDENTIFY_PATH
            }
        }
        return try {
            Attempt.Sent(Delivery.outcome(env.transport.send(Wire.url(env.endpoint, path), Wire.headers(key), body)))
        } catch (error: Exception) {
            Attempt.Sent(Outcome.Retry)
        }
    }

    /**
     * The stored referrer, or a new read kept for every later attempt. Null
     * while Play's service is worth waiting for.
     */
    private fun referrerFor(request: QueuedRequest): ReferrerSnapshot? {
        state.referrer?.let { return it }
        val snapshot = when (val result = env.referrer.read()) {
            is ReferrerResult.Read -> result.snapshot
            ReferrerResult.NotSupported -> ReferrerSnapshot(ReferrerStatus.NOT_SUPPORTED)
            ReferrerResult.Failed -> ReferrerSnapshot(ReferrerStatus.ERROR)
            ReferrerResult.Retryable ->
                if (request.referrerFailures < Delivery.MAX_REFERRER_RETRIES) return null
                else ReferrerSnapshot(ReferrerStatus.UNAVAILABLE)
        }
        state = state.copy(referrer = snapshot)
        persist()
        return snapshot
    }

    /** Records an attempt's result. False when this round should stop. */
    private fun apply(attempt: Attempt, requestId: String): Boolean {
        val index = state.queue.indexOfFirst { it.requestId == requestId }
        if (index < 0) return true
        val request = state.queue[index]
        val now = env.now()
        val queue = state.queue.toMutableList()
        var proceed = false
        when (attempt) {
            Attempt.AwaitingReferrer -> {
                val failures = request.referrerFailures + 1
                queue[index] = request.copy(
                    referrerFailures = failures,
                    // 4 s, 8 s, 16 s
                    nextAttemptAt = now + Backoff.delayMs(failures + 1, env.jitter()),
                )
            }
            is Attempt.Sent -> when (val outcome = attempt.outcome) {
                Outcome.Delivered -> {
                    env.log.info("Fload: ${request.kind.name.lowercase()} accepted.")
                    queue.removeAt(index)
                    state = state.copy(refusedKeys = state.refusedKeys - fingerprint)
                    proceed = true
                }
                is Outcome.Dropped -> {
                    env.log.error("Fload: the ${request.kind.name.lowercase()} was refused (HTTP ${outcome.status}) and dropped.")
                    queue.removeAt(index)
                    proceed = true
                }
                Outcome.KeyRefused -> {
                    state = state.copy(refusedKeys = state.refusedKeys + (fingerprint to now + Delivery.REFUSED_KEY_PAUSE_MS))
                    warnKeyRefused()
                }
                Outcome.Revoked -> {
                    env.log.error("Fload: this write key was revoked (HTTP 410). The SDK stops sending with it.")
                    state = state.copy(revokedKeys = state.revokedKeys + fingerprint)
                }
                is Outcome.RateLimited -> queue[index] = request.copy(nextAttemptAt = now + outcome.afterMs)
                Outcome.Retry -> {
                    val failures = request.failures + 1
                    queue[index] = request.copy(
                        failures = failures,
                        nextAttemptAt = now + Backoff.delayMs(failures, env.jitter()),
                    )
                }
            }
        }
        state = state.copy(queue = queue)
        persist()
        return proceed
    }

    /** One warning a launch while Fload does not know this key. */
    private fun warnKeyRefused() {
        if (warnedKeyRefused) return
        warnedKeyRefused = true
        env.log.warning(
            "Fload: ingest key refused (HTTP 401). Check the key passed to Fload.configure; " +
                "installs stay queued and the key is tried again every 24 hours.",
        )
    }

    private fun persist() {
        try {
            env.store.save(state)
        } catch (error: Exception) {
            env.log.error("Fload: could not save its queue; it is kept in memory for this launch.")
        }
    }

    // Wake-ups

    /** While the app runs, waits until the head request is due and flushes then. */
    private fun scheduleWake() {
        wake?.cancel()
        wake = null
        val scheduler = env.scheduler ?: return
        if (revoked) return
        val head = state.queue.firstOrNull() ?: return
        val due = maxOf(head.nextAttemptAt, refusedUntil ?: Long.MIN_VALUE)
        wake = scheduler.schedule(maxOf(0, due - env.now())) { flush() }
    }
}

/** Loads the stored state, or starts a new install when there is none. */
internal object Bootstrap {
    /**
     * Null when the storage exists but cannot be read: starting a new
     * install then would count this one twice, so the SDK sits this launch out.
     */
    fun loadOrCreate(env: Environment): StoredState? = when (val loaded = env.store.load()) {
        is LoadResult.Loaded -> loaded.state
        LoadResult.Unreadable -> null
        LoadResult.Corrupt -> {
            env.log.warning("Fload: its stored state was unreadable and was reset.")
            newInstall(env)
        }
        LoadResult.Missing -> newInstall(env)
    }

    private fun newInstall(env: Environment): StoredState {
        val now = env.now()
        val state = StoredState(
            installReference = env.makeId(),
            firstLaunchAt = now,
            device = env.device(),
            queue = listOf(
                QueuedRequest(
                    requestId = env.makeId(),
                    kind = RequestKind.INSTALL,
                    createdAt = now,
                    nextAttemptAt = now,
                ),
            ),
        )
        try {
            env.store.save(state)
        } catch (error: Exception) {
            env.log.error("Fload: could not save the new install; it will be sent this launch only.")
        }
        env.log.info("Fload: new install.")
        return state
    }
}

package com.fload.attribution.core

/** The SDK's name and version, sent with every install. */
internal object SdkInfo {
    const val NAME: String = "fload-attribution-android"
    const val VERSION: String = "1.1.0"
}

/** The cross-platform wrapper around the SDK, as the wire names it (FLO-3957). */
internal data class WrapperInfo(val name: String, val version: String) {
    companion object {
        /** Trimmed; a blank name or version names no wrapper. */
        fun of(name: String, version: String): WrapperInfo? {
            val trimmedName = name.trim()
            val trimmedVersion = version.trim()
            return if (trimmedName.isEmpty() || trimmedVersion.isEmpty()) null else WrapperInfo(trimmedName, trimmedVersion)
        }
    }
}

/** The app and device as they were on the install's first launch. */
internal data class DeviceFacts(
    val appId: String,
    val appVersion: String? = null,
    val appBuild: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val locale: String? = null,
)

/** The contract's `playReferrer.status`. */
internal enum class ReferrerStatus(val wire: String) {
    OK("ok"),
    UNAVAILABLE("unavailable"),
    NOT_SUPPORTED("not_supported"),
    ERROR("error"),
}

/**
 * What the Play Install Referrer API said, kept once read: unlike Apple's
 * token it does not change, so every retry sends the same. Times are Play's
 * seconds; 0 means Play did not say.
 */
internal data class ReferrerSnapshot(
    val status: ReferrerStatus,
    val referrer: String? = null,
    val clickAtSeconds: Long = 0,
    val installBeginAtSeconds: Long = 0,
    val clickServerAtSeconds: Long = 0,
    val installBeginServerAtSeconds: Long = 0,
    val installVersion: String? = null,
    val googlePlayInstant: Boolean? = null,
) {
    companion object {
        /** Play's `ReferrerDetails`, fitted to the contract. An empty or missing referrer is still `ok`. */
        fun fromPlay(
            referrer: String?,
            clickAtSeconds: Long,
            installBeginAtSeconds: Long,
            clickServerAtSeconds: Long,
            installBeginServerAtSeconds: Long,
            installVersion: String?,
            googlePlayInstant: Boolean,
        ): ReferrerSnapshot = ReferrerSnapshot(
            status = ReferrerStatus.OK,
            referrer = Text.cut(referrer ?: "", Text.REFERRER_LIMIT),
            clickAtSeconds = clickAtSeconds,
            installBeginAtSeconds = installBeginAtSeconds,
            clickServerAtSeconds = clickServerAtSeconds,
            installBeginServerAtSeconds = installBeginServerAtSeconds,
            installVersion = Text.shortText(installVersion),
            googlePlayInstant = googlePlayInstant,
        )
    }
}

/** What one read of the Play Install Referrer API gave. */
internal sealed class ReferrerResult {
    data class Read(val snapshot: ReferrerSnapshot) : ReferrerResult()

    /** `FEATURE_NOT_SUPPORTED`: no Play Store, or one too old. */
    object NotSupported : ReferrerResult()

    /** `SERVICE_UNAVAILABLE`, a disconnect or no answer in time: worth asking again shortly. */
    object Retryable : ReferrerResult()

    /** Any other failure. */
    object Failed : ReferrerResult()
}

internal enum class RequestKind { INSTALL, IDENTIFY }

internal data class QueuedRequest(
    /** Sent unchanged on every retry. */
    val requestId: String,
    val kind: RequestKind,
    val createdAt: Long,
    /** Failed attempts in a row that count toward the backoff. */
    val failures: Int = 0,
    val nextAttemptAt: Long,
    val appUserId: String? = null,
    /** Times the referrer could not be read yet (install only). */
    val referrerFailures: Int = 0,
)

/** What the SDK keeps on disk. Times are epoch milliseconds. */
internal data class StoredState(
    /** Random, made on the first launch with the SDK; names this install to Fload. */
    val installReference: String,
    val firstLaunchAt: Long,
    val device: DeviceFacts,
    /** Unsent requests, oldest first. Sent in this order, one at a time. */
    val queue: List<QueuedRequest>,
    /** The referrer once read (or found missing for good). */
    val referrer: ReferrerSnapshot? = null,
    /** The last user id handed over for sending, so a repeat sends nothing. */
    val lastUserId: String? = null,
    /** SHA-256 of each write key Fload answered 410 (revoked) for. */
    val revokedKeys: Set<String> = emptySet(),
    /** SHA-256 of each write key Fload answered 401 (unknown) for, and when to try it again. */
    val refusedKeys: Map<String, Long> = emptyMap(),
)

/** The contract's string limits, counted in UTF-16 units as the server counts them. */
internal object Text {
    const val SHORT_TEXT_LIMIT: Int = 64
    const val APP_ID_LIMIT: Int = 256
    const val USER_ID_LIMIT: Int = 256
    const val REFERRER_LIMIT: Int = 1024

    /** At most [limit] UTF-16 units, never splitting a surrogate pair. */
    fun cut(value: String, limit: Int): String {
        if (value.length <= limit) return value
        var end = limit
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end -= 1
        return value.substring(0, end)
    }

    /** The contract's `shortText`: trimmed, 1 to 64 units, else left out. */
    fun shortText(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) null else cut(trimmed, SHORT_TEXT_LIMIT)
    }

    fun appId(packageName: String?): String {
        val trimmed = packageName?.trim().orEmpty()
        return if (trimmed.isEmpty()) "unknown" else cut(trimmed, APP_ID_LIMIT)
    }

    /** A user id the contract accepts: trimmed, 1 to 256 units, else null. */
    fun appUserId(raw: String): String? {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty() || trimmed.length > USER_ID_LIMIT) null else trimmed
    }

    /** "samsung SM-S918B"; a model that already starts with its maker stays as it is. */
    fun deviceModel(manufacturer: String?, model: String?): String? {
        val maker = manufacturer?.trim().orEmpty()
        val name = model?.trim().orEmpty()
        val joined = when {
            name.isEmpty() -> maker
            maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
            else -> "$maker $name"
        }
        return shortText(joined)
    }
}

internal object WriteKey {
    const val PREFIX: String = "fload_pk_"
    const val HEADER: String = "X-Fload-Write-Key"

    /** What the server's `appWriteKey` policy accepts: the prefix, at most 128 characters. */
    fun isWellFormed(key: String): Boolean =
        key.startsWith(PREFIX) && key.length > PREFIX.length && key.length <= 128

    fun fingerprint(key: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

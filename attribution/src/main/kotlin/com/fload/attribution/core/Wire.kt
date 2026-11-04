package com.fload.attribution.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The two requests of Fload's ingest contract
 * (`packages/shared-types/src/contracts/attribution-ingest.ts` in the Fload repo).
 */
internal object Wire {
    const val INSTALLS_PATH: String = "attribution/v1/installs"
    const val IDENTIFY_PATH: String = "attribution/v1/identify"
    private const val PLATFORM = "android"

    fun url(endpoint: String, path: String): String = endpoint.trimEnd('/') + "/" + path

    fun headers(key: String): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        WriteKey.HEADER to key,
        "User-Agent" to "${SdkInfo.NAME}/${SdkInfo.VERSION}",
    )

    fun install(
        requestId: String,
        state: StoredState,
        referrer: ReferrerSnapshot,
        appUserId: String?,
    ): ByteArray {
        val device = state.device
        return Json.write(
            linkedMapOf(
                "requestId" to requestId,
                "installReference" to state.installReference,
                "platform" to PLATFORM,
                "appId" to device.appId,
                "appVersion" to device.appVersion,
                "appBuild" to device.appBuild,
                "osVersion" to device.osVersion,
                "deviceModel" to device.deviceModel,
                "locale" to device.locale,
                "sdk" to linkedMapOf("name" to SdkInfo.NAME, "version" to SdkInfo.VERSION),
                "firstLaunchAt" to timestamp(state.firstLaunchAt),
                "playReferrer" to playReferrer(referrer),
                "appUserId" to appUserId,
            ),
        ).toByteArray(Charsets.UTF_8)
    }

    fun identify(requestId: String, installReference: String, appUserId: String): ByteArray =
        Json.write(
            linkedMapOf(
                "requestId" to requestId,
                "installReference" to installReference,
                "platform" to PLATFORM,
                "appUserId" to appUserId,
            ),
        ).toByteArray(Charsets.UTF_8)

    fun playReferrer(snapshot: ReferrerSnapshot): Map<String, Any?> = linkedMapOf(
        "status" to snapshot.status.wire,
        "referrer" to snapshot.referrer,
        "referrerClickAt" to secondsTimestamp(snapshot.clickAtSeconds),
        "installBeginAt" to secondsTimestamp(snapshot.installBeginAtSeconds),
        "referrerClickServerAt" to secondsTimestamp(snapshot.clickServerAtSeconds),
        "installBeginServerAt" to secondsTimestamp(snapshot.installBeginServerAtSeconds),
        "installVersion" to snapshot.installVersion,
        "googlePlayInstant" to snapshot.googlePlayInstant,
    )

    /** ISO 8601 in UTC with milliseconds, which the contract's `datetime` accepts. */
    fun timestamp(epochMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMs))
    }

    /** Play's seconds as a timestamp; 0 (Play did not say) is left out. */
    fun secondsTimestamp(seconds: Long): String? = if (seconds > 0) timestamp(seconds * 1000) else null
}

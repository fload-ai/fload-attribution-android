package com.fload.attribution.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The wire contract as the server states it
 * (`packages/shared-types/src/contracts/attribution-ingest.ts`), checked on
 * what the SDK builds.
 */
class ContractTest {
    private val installKeys = setOf(
        "requestId", "installReference", "platform", "appId", "appVersion", "appBuild",
        "osVersion", "deviceModel", "locale", "sdk", "firstLaunchAt", "appleAds",
        "playReferrer", "appUserId",
    )
    private val playKeys = setOf(
        "status", "referrer", "referrerClickAt", "installBeginAt", "referrerClickServerAt",
        "installBeginServerAt", "installVersion", "googlePlayInstant",
    )
    private val phoneId = Regex("^[A-Za-z0-9._:-]{8,128}$")

    private fun longest(): StoredState = StoredState(
        installReference = UUID.randomUUID().toString(),
        firstLaunchAt = 1_800_000_000_000,
        device = DeviceFacts(
            appId = "a".repeat(256),
            appVersion = "v".repeat(64),
            appBuild = "b".repeat(64),
            osVersion = "o".repeat(64),
            deviceModel = "d".repeat(64),
            locale = "l".repeat(64),
        ),
        queue = emptyList(),
    )

    @Test
    fun theLongestInstallFitsTheBodyLimitAndOnlyUsesContractKeys() {
        val referrer = ReferrerSnapshot.fromPlay("r".repeat(5000), 1, 2, 3, 4, "x".repeat(100), true)
        val body = Wire.install(UUID.randomUUID().toString(), longest(), referrer, "u".repeat(256))
        assertTrue("the install route's 8 KB body limit", body.size < 8 * 1024)

        val json = TestJson.parseObject(String(body, Charsets.UTF_8))
        assertTrue(installKeys.containsAll(json.keys))
        assertTrue(phoneId.matches(json["requestId"] as String))
        assertTrue(phoneId.matches(json["installReference"] as String))
        @Suppress("UNCHECKED_CAST")
        val play = json["playReferrer"] as Map<String, Any?>
        assertTrue(playKeys.containsAll(play.keys))
        assertEquals(1024, (play["referrer"] as String).length)
        assertEquals(64, (play["installVersion"] as String).length)
    }

    @Test
    fun androidSendsPlayReferrerAndNeverAppleAds() {
        val body = TestJson.parseObject(
            String(Wire.install("req-00000001", longest(), ReferrerSnapshot(ReferrerStatus.ERROR), null), Charsets.UTF_8),
        )
        assertEquals("android", body["platform"])
        assertFalse(body.containsKey("appleAds"))
        assertEquals(mapOf("status" to "error"), body["playReferrer"])
    }

    @Test
    fun liveIdsAreValidPhoneIds() {
        assertTrue(phoneId.matches(UUID.randomUUID().toString()))
    }

    @Test
    fun urlsJoinWhateverTheEndpointsSlash() {
        assertEquals("http://localhost:4105/attribution/v1/identify", Wire.url("http://localhost:4105/", Wire.IDENTIFY_PATH))
        assertEquals("https://ingest.fload.com/attribution/v1/installs", Wire.url("https://ingest.fload.com", Wire.INSTALLS_PATH))
    }

    @Test
    fun timestampsAreIsoWithAZoneAndPlaysZeroIsLeftOut() {
        assertEquals("1970-01-01T00:00:00.250Z", Wire.timestamp(250))
        assertNull(Wire.secondsTimestamp(0))
        assertEquals("1970-01-01T00:00:01.000Z", Wire.secondsTimestamp(1))
    }

    @Test
    fun jsonEscapesWhatItMust() {
        val text = Json.write(linkedMapOf("a" to "q\"b\\n\n\u0001 é", "skip" to null, "n" to 5L, "t" to true))
        assertEquals("{\"a\":\"q\\\"b\\\\n\\n\\u0001\\u2028é\",\"n\":5,\"t\":true}", text)
        assertEquals("q\"b\\n\n\u0001 é", TestJson.parseObject(text)["a"])
    }

    @Test
    fun textLimitsCountUtf16AndNeverSplitAPair() {
        assertNull(Text.shortText("   "))
        assertEquals("1.0", Text.shortText(" 1.0 "))
        val emoji = "a" + "👍".repeat(40)
        val cut = Text.cut(emoji, 64)
        assertEquals(63, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
        assertEquals("unknown", Text.appId(" "))
        assertNull(Text.appUserId(" "))
        assertNull(Text.appUserId("x".repeat(257)))
        assertEquals("x".repeat(256), Text.appUserId("x".repeat(256)))
    }

    @Test
    fun deviceModelJoinsMakerAndModelOnce() {
        assertEquals("samsung SM-S918B", Text.deviceModel("samsung", "SM-S918B"))
        assertEquals("Google Pixel 8", Text.deviceModel("Google", "Pixel 8"))
        assertEquals("OnePlus 12", Text.deviceModel("OnePlus", "OnePlus 12"))
        assertEquals(64, Text.deviceModel("M".repeat(40), "X".repeat(40))!!.length)
        assertNull(Text.deviceModel(" ", null))
    }

    @Test
    fun writeKeyShape() {
        assertTrue(WriteKey.isWellFormed("fload_pk_abc"))
        assertFalse(WriteKey.isWellFormed("fload_pk_"))
        assertFalse(WriteKey.isWellFormed("fload_sk_abc"))
        assertFalse(WriteKey.isWellFormed("fload_pk_" + "a".repeat(120)))
        assertEquals(64, WriteKey.fingerprint("fload_pk_abc").length)
    }

    @Test
    fun statusesMapToTheContractsActions() {
        assertEquals(Outcome.Delivered, Delivery.outcome(TransportResponse(202)))
        assertEquals(Outcome.Delivered, Delivery.outcome(TransportResponse(200)))
        assertEquals(Outcome.Dropped(400), Delivery.outcome(TransportResponse(400)))
        assertEquals(Outcome.Dropped(413), Delivery.outcome(TransportResponse(413)))
        assertEquals(Outcome.KeyRefused, Delivery.outcome(TransportResponse(401)))
        assertEquals(Outcome.Revoked, Delivery.outcome(TransportResponse(410)))
        assertEquals(Outcome.RateLimited(12_000), Delivery.outcome(TransportResponse(429, "12")))
        for (status in listOf(404, 408, 500, 502, 503, 504)) {
            assertEquals("HTTP $status", Outcome.Retry, Delivery.outcome(TransportResponse(status)))
        }
    }

    @Test
    fun retryAfterBounds() {
        assertEquals(60_000, Delivery.retryAfterMs(null))
        assertEquals(60_000, Delivery.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(1_000, Delivery.retryAfterMs("0"))
        assertEquals(3_600_000, Delivery.retryAfterMs("99999"))
    }

    @Test
    fun backoffDoublesToItsCapWithJitter() {
        assertEquals(2_000, Backoff.delayMs(1, 1.0))
        assertEquals(32_000, Backoff.delayMs(5, 1.0))
        assertEquals(16_000, Backoff.delayMs(5, 0.5))
        assertEquals(6 * 3_600_000L, Backoff.delayMs(40, 1.0))
        assertEquals("jitter is clamped", 2_000, Backoff.delayMs(1, 7.0))
    }

    @Test
    fun identifyNamesTheSdkAndItsWrapperInTheContractsShape() {
        val body = String(
            Wire.identify("req-00000001", "ins-00000001", "u", WrapperInfo.of("react-native", "1.2.0")),
            Charsets.UTF_8,
        )
        assertEquals(
            "{\"requestId\":\"req-00000001\",\"installReference\":\"ins-00000001\",\"platform\":\"android\",\"appUserId\":\"u\"," +
                "\"sdk\":{\"name\":\"fload-attribution-android\",\"version\":\"${SdkInfo.VERSION}\"," +
                "\"wrapper\":{\"name\":\"react-native\",\"version\":\"1.2.0\"}}}",
            body,
        )
    }
}

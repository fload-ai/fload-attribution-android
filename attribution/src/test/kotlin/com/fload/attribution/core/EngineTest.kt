package com.fload.attribution.core

import com.fload.attribution.core.Harness.Companion.DAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    // The first launch

    @Test
    fun firstLaunchSendsTheInstallOnceAcrossLaunches() {
        val harness = Harness()
        val first = harness.launch()!!
        first.flush()

        assertEquals(listOf("/attribution/v1/installs"), harness.transport.paths)
        val sent = harness.transport.sent.single()
        assertEquals(Harness.KEY, sent.headers["X-Fload-Write-Key"])
        assertEquals("application/json", sent.headers["Content-Type"])
        assertTrue(first.state.queue.isEmpty())

        val second = harness.launch()!!
        second.flush()
        assertEquals("a relaunch sends nothing again", 1, harness.transport.sent.size)
        assertEquals(first.state.installReference, second.state.installReference)
    }

    @Test
    fun installBodyCarriesTheReferrerAndTheFirstLaunchFacts() {
        val harness = Harness()
        val engine = harness.launch()!!
        harness.advance(5_000)
        engine.flush()

        val body = harness.transport.bodies.single()
        assertEquals(engine.state.installReference, body["installReference"])
        assertEquals("android", body["platform"])
        assertEquals("com.example.app", body["appId"])
        assertEquals("1.2.3", body["appVersion"])
        assertEquals("45", body["appBuild"])
        assertEquals("14", body["osVersion"])
        assertEquals("Google Pixel 8", body["deviceModel"])
        assertEquals("en-US", body["locale"])
        assertEquals("2027-01-15T08:00:00.000Z", body["firstLaunchAt"])
        assertEquals(mapOf("name" to "fload-attribution-android", "version" to SdkInfo.VERSION), body["sdk"])
        assertEquals(
            mapOf(
                "status" to "ok",
                "referrer" to "utm_source=google-play&utm_medium=organic",
                "referrerClickAt" to "2027-01-15T07:43:20.000Z",
                "installBeginAt" to "2027-01-15T07:45:00.000Z",
                "referrerClickServerAt" to "2027-01-15T07:43:21.000Z",
                "installVersion" to "1.2.3",
                "googlePlayInstant" to false,
            ),
            body["playReferrer"],
        )
        assertNull(body["appleAds"])
        assertNull(body["appUserId"])
    }

    @Test
    fun aWrapperIsNamedOnTheInstallAndEveryIdentify() {
        val harness = Harness()
        harness.wrapper = WrapperInfo.of("flutter", "1.1.0")
        val engine = harness.launch()!!
        engine.flush()
        engine.setUserId("user-1")
        engine.flush()

        assertEquals(listOf("/attribution/v1/installs", "/attribution/v1/identify"), harness.transport.paths)
        val sdk = mapOf(
            "name" to "fload-attribution-android",
            "version" to SdkInfo.VERSION,
            "wrapper" to mapOf("name" to "flutter", "version" to "1.1.0"),
        )
        for (body in harness.transport.bodies) assertEquals(sdk, body["sdk"])
    }

    @Test
    fun aNativeAppNamesNoWrapper() {
        val harness = Harness()
        val engine = harness.launch()!!
        engine.flush()
        engine.setUserId("user-1")
        engine.flush()

        val sdk = mapOf("name" to "fload-attribution-android", "version" to SdkInfo.VERSION)
        for (body in harness.transport.bodies) assertEquals(sdk, body["sdk"])
    }

    @Test
    fun aBlankWrapperIsLeftOut() {
        assertNull(WrapperInfo.of("  ", "1.0.0"))
        assertNull(WrapperInfo.of("unity", ""))
        assertEquals(WrapperInfo("dotnet", "1.1.0"), WrapperInfo.of(" dotnet ", "1.1.0 "))
    }

    // The referrer

    @Test
    fun okWithAnEmptyReferrerIsSentAsOkWithTheEmptyString() {
        val harness = Harness(FakeReferrer(FakeReferrer.ok(referrer = "")))
        harness.launch()!!.flush()
        @Suppress("UNCHECKED_CAST")
        val play = harness.transport.bodies.single()["playReferrer"] as Map<String, Any?>
        assertEquals("ok", play["status"])
        assertEquals("", play["referrer"])
    }

    @Test
    fun okWithoutAReferrerIsSentAsOkWithTheEmptyString() {
        val harness = Harness(FakeReferrer(FakeReferrer.ok(referrer = null)))
        harness.launch()!!.flush()
        @Suppress("UNCHECKED_CAST")
        val play = harness.transport.bodies.single()["playReferrer"] as Map<String, Any?>
        assertEquals("ok", play["status"])
        assertEquals("", play["referrer"])
    }

    @Test
    fun referrerFailuresMapToTheContractsStatuses() {
        for ((result, status) in listOf(
            ReferrerResult.NotSupported to "not_supported",
            ReferrerResult.Failed to "error",
        )) {
            val harness = Harness(FakeReferrer(result))
            harness.launch()!!.flush()
            assertEquals(mapOf("status" to status), harness.transport.bodies.single()["playReferrer"])
        }
    }

    @Test
    fun anUnavailableServiceIsAskedAgainThenSentAsUnavailable() {
        val harness = Harness(FakeReferrer(ReferrerResult.Retryable))
        val engine = harness.launch()!!
        for (wait in listOf(4_000L, 8_000L, 16_000L)) {
            engine.flush()
            assertTrue(harness.transport.sent.isEmpty())
            harness.advance(wait - 1)
            engine.flush()
            assertTrue("not before $wait ms", harness.transport.sent.isEmpty())
            harness.advance(1)
        }
        engine.flush()
        assertEquals(4, harness.referrer.calls)
        assertEquals(mapOf("status" to "unavailable"), harness.transport.bodies.single()["playReferrer"])
    }

    @Test
    fun theReferrerIsReadOnceAndReusedOnRetries() {
        val harness = Harness()
        harness.transport.script(503)
        val engine = harness.launch()!!
        engine.flush()
        harness.advance(2_000)
        engine.flush()

        assertEquals(1, harness.referrer.calls)
        val bodies = harness.transport.bodies
        assertEquals(2, bodies.size)
        assertEquals(bodies[0]["playReferrer"], bodies[1]["playReferrer"])
        assertEquals("a retry keeps its request id", bodies[0]["requestId"], bodies[1]["requestId"])
    }

    @Test
    fun aRecoveredReadIsKeptAcrossARelaunch() {
        val harness = Harness(FakeReferrer(ReferrerResult.Retryable, FakeReferrer.ok()))
        harness.transport.script(503)
        val first = harness.launch()!!
        first.flush()
        harness.advance(4_000)
        first.flush() // read ok, send fails
        val relaunched = harness.launch()!!
        harness.advance(2_000)
        relaunched.flush()
        assertEquals(2, harness.referrer.calls)
        assertEquals(2, harness.transport.sent.size)
    }

    // Statuses

    @Test
    fun serverErrorsBackOffExponentiallyAndKeepTheIds() {
        val harness = Harness()
        harness.transport.script(500)
        harness.transport.script(502)
        harness.transport.scriptOffline()
        val engine = harness.launch()!!

        engine.flush() // 500: wait 2 s
        harness.advance(1_999)
        engine.flush()
        assertEquals(1, harness.transport.sent.size)
        harness.advance(1)
        engine.flush() // 502: wait 4 s
        harness.advance(4_000)
        engine.flush() // offline: wait 8 s
        val waiting = engine.state.queue.single()
        assertEquals(3, waiting.failures)
        assertEquals(harness.now + 8_000, waiting.nextAttemptAt)
        harness.advance(8_000)
        engine.flush()

        assertEquals(4, harness.transport.sent.size)
        assertTrue(engine.state.queue.isEmpty())
        assertEquals(1, harness.transport.bodies.map { "${it["requestId"]}|${it["installReference"]}" }.toSet().size)
    }

    @Test
    fun rateLimitWaitsRetryAfter() {
        val harness = Harness()
        harness.transport.script(429, retryAfter = "30")
        val engine = harness.launch()!!
        engine.flush()
        harness.advance(29_999)
        engine.flush()
        assertEquals(1, harness.transport.sent.size)
        harness.advance(1)
        engine.flush()
        assertEquals(2, harness.transport.sent.size)
        assertTrue(engine.state.queue.isEmpty())
    }

    @Test
    fun badRequestsAreDropped() {
        for (status in listOf(400, 413)) {
            val harness = Harness()
            harness.transport.script(status)
            val engine = harness.launch()!!
            engine.flush()
            assertTrue("HTTP $status drops the request", engine.state.queue.isEmpty())
        }
    }

    @Test
    fun aRefusedKeyHoldsTheQueueAndIsTriedOnceADay() {
        val log = RecordingLog()
        val harness = Harness(log = log)
        harness.transport.script(401)
        harness.transport.script(401)
        val engine = harness.launch()!!
        engine.flush()
        engine.setUserId("anonymous") // rides in the held install
        engine.setUserId("signed-in") // an identify behind it

        harness.advance(DAY - 1)
        engine.flush()
        val relaunched = harness.launch()!!
        relaunched.flush()
        assertEquals("nothing is sent with the key for 24 h, relaunch or not", 1, harness.transport.sent.size)
        assertEquals("a 401 drops nothing", listOf(RequestKind.INSTALL, RequestKind.IDENTIFY), relaunched.state.queue.map { it.kind })
        assertEquals("one warning a launch", 2, log.warnings.count { it.startsWith("Fload: ingest key refused") })

        harness.advance(1)
        relaunched.flush() // 401 again: another day
        assertEquals(2, harness.transport.sent.size)
        harness.advance(DAY - 1)
        relaunched.flush()
        assertEquals(2, harness.transport.sent.size)

        harness.advance(1)
        relaunched.flush() // the key works now: everything goes
        assertEquals(
            listOf("/attribution/v1/installs", "/attribution/v1/installs", "/attribution/v1/installs", "/attribution/v1/identify"),
            harness.transport.paths,
        )
        assertTrue(relaunched.state.queue.isEmpty())
        assertTrue(relaunched.state.refusedKeys.isEmpty())
        assertEquals("anonymous", harness.transport.bodies[2]["appUserId"])
        assertEquals("signed-in", harness.transport.bodies[3]["appUserId"])
    }

    @Test
    fun aRefusedKeysQueueGoesAtOnceWithANewKey() {
        val harness = Harness()
        harness.transport.script(401)
        harness.launch()!!.flush()

        val fixed = harness.launch("fload_pk_fixed_0123456789")!!
        fixed.flush()
        assertEquals(2, harness.transport.sent.size)
        assertEquals("fload_pk_fixed_0123456789", harness.transport.sent.last().headers["X-Fload-Write-Key"])
        assertTrue(fixed.state.queue.isEmpty())
    }

    @Test
    fun aRefusedKeysQueueIsDroppedAfterThirtyDays() {
        val harness = Harness()
        repeat(40) { harness.transport.script(401) }
        val engine = harness.launch()!!
        repeat(31) {
            engine.flush()
            harness.advance(DAY)
        }
        engine.flush()
        assertEquals("one try a day, day 0 through day 30, then dropped", 31, harness.transport.sent.size)
        assertTrue(engine.state.queue.isEmpty())
    }

    @Test
    fun aRevokedKeyStopsForGoodUntilANewKey() {
        val harness = Harness()
        harness.transport.script(410)
        val engine = harness.launch()!!
        engine.flush()
        harness.advance(7 * DAY)
        engine.flush()
        engine.setUserId("user-1")
        engine.flush()
        harness.launch()!!.flush()
        assertEquals(1, harness.transport.sent.size)

        val newKey = harness.launch("fload_pk_rotated_9876543210")!!
        newKey.flush()
        assertEquals(2, harness.transport.sent.size)
        assertEquals("user-1", harness.transport.bodies.last()["appUserId"])
    }

    @Test
    fun requestsOlderThanThirtyDaysAreDropped() {
        val harness = Harness()
        harness.transport.script(503)
        val engine = harness.launch()!!
        engine.flush()
        harness.advance(30 * DAY + 1)
        engine.flush()
        assertEquals(1, harness.transport.sent.size)
        assertTrue(engine.state.queue.isEmpty())
    }

    @Test
    fun aFailedRequestHoldsBackTheOnesBehindIt() {
        val harness = Harness()
        harness.transport.script(503)
        val engine = harness.launch()!!
        engine.flush()
        engine.setUserId("anonymous")
        engine.setUserId("signed-in")
        engine.flush()
        assertEquals("the identify waits for the install", listOf("/attribution/v1/installs"), harness.transport.paths)
    }

    // User id

    @Test
    fun aUserIdSetBeforeTheInstallGoesInsideIt() {
        val harness = Harness()
        val engine = harness.launch()!!
        engine.setUserId("  \$RCAnonymousID:abc  ")
        engine.flush()
        assertEquals(listOf("/attribution/v1/installs"), harness.transport.paths)
        assertEquals("\$RCAnonymousID:abc", harness.transport.bodies.single()["appUserId"])
    }

    @Test
    fun aUserIdAfterTheInstallIsAnIdentify() {
        val harness = Harness()
        val engine = harness.launch()!!
        engine.flush()
        engine.setUserId("user-1")
        engine.setUserId("user-1")
        engine.flush()

        assertEquals(listOf("/attribution/v1/installs", "/attribution/v1/identify"), harness.transport.paths)
        val identify = harness.transport.bodies.last()
        assertEquals(setOf("requestId", "installReference", "platform", "appUserId", "sdk"), identify.keys)
        assertEquals(engine.state.installReference, identify["installReference"])
        assertEquals("android", identify["platform"])
        assertEquals("user-1", identify["appUserId"])
    }

    @Test
    fun theSameUserIdOnALaterLaunchSendsNothing() {
        val harness = Harness()
        val first = harness.launch()!!
        first.setUserId("user-1")
        first.flush()
        val second = harness.launch()!!
        second.setUserId("user-1")
        second.flush()
        assertEquals(1, harness.transport.sent.size)
    }

    @Test
    fun invalidUserIdsAreIgnored() {
        val harness = Harness()
        val engine = harness.launch()!!
        engine.setUserId("   ")
        engine.setUserId("x".repeat(257))
        engine.flush()
        assertNull(harness.transport.bodies.single()["appUserId"])
    }

    @Test
    fun queuedIdentifiesAreCapped() {
        val harness = Harness()
        val engine = harness.launch()!!
        engine.setUserId("first")
        repeat(30) { engine.setUserId("user-$it") }
        val identifies = engine.state.queue.filter { it.kind == RequestKind.IDENTIFY }
        assertEquals(Delivery.MAX_QUEUED_IDENTIFIES, identifies.size)
        assertEquals("the newest are kept", "user-29", identifies.last().appUserId)
    }

    // Storage

    @Test
    fun unreadableStorageSitsTheLaunchOut() {
        val store = MemoryStore().apply { result = LoadResult.Unreadable }
        val harness = Harness(store = store)
        assertNull(harness.launch())
        assertNull("no new install is written over an unreadable one", store.stored)
    }

    @Test
    fun corruptStorageStartsAFreshInstall() {
        val store = MemoryStore().apply { result = LoadResult.Corrupt }
        val harness = Harness(store = store)
        harness.launch()!!.flush()
        assertEquals(listOf("/attribution/v1/installs"), harness.transport.paths)
    }

    @Test
    fun aFailingSaveStillSendsThisLaunch() {
        val store = MemoryStore().apply { failSaves = true }
        val harness = Harness(store = store)
        harness.launch()!!.flush()
        assertEquals(1, harness.transport.sent.size)
    }
}

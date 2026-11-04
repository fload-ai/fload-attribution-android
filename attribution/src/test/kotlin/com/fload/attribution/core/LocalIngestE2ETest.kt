package com.fload.attribution.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.util.UUID

/**
 * Against a LOCAL Fload API only (`pnpm dev:api` in a worktree; never
 * production). Skipped unless `FLOAD_E2E_ENDPOINT` and `FLOAD_E2E_KEY` are
 * set. The real HttpURLConnection transport and file store; only Play's
 * referrer is faked (there is no Play Store on the JVM). Prints the install
 * reference so the rows can be checked in the worktree's database.
 */
class LocalIngestE2ETest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun installThenIdentifyAgainstALocalApi() {
        val endpoint = System.getenv("FLOAD_E2E_ENDPOINT")
        val key = System.getenv("FLOAD_E2E_KEY")
        assumeTrue("FLOAD_E2E_ENDPOINT and FLOAD_E2E_KEY are not set", endpoint != null && key != null)
        assumeTrue("local APIs only", URI(endpoint).host in setOf("localhost", "127.0.0.1"))

        val statuses = mutableListOf<Int>()
        val real = UrlConnectionTransport()
        val recording = Transport { url, headers, body -> real.send(url, headers, body).also { statuses += it.status } }
        val environment = Environment(
            endpoint = endpoint!!,
            transport = recording,
            referrer = { FakeReferrer.ok("utm_source=e2e&utm_medium=cpc&utm_campaign=sdk-${UUID.randomUUID()}") },
            store = FileStateStore(folder.newFolder("state")),
            device = { FIXTURE_DEVICE.copy(appId = "com.fload.e2e.android") },
            now = System::currentTimeMillis,
            jitter = { 1.0 },
            makeId = { UUID.randomUUID().toString() },
            log = SilentLog,
            scheduler = null,
        )

        val state = Bootstrap.loadOrCreate(environment)!!
        val engine = Engine(key!!, state, environment)
        engine.flush()
        engine.setUserId("e2e-user-${UUID.randomUUID().toString().take(8)}")
        engine.flush()

        // The same install again, as after a lost answer: the server takes the repeat.
        val replay = engine.state.copy(
            queue = listOf(QueuedRequest(state.queue[0].requestId, RequestKind.INSTALL, System.currentTimeMillis(), nextAttemptAt = 0)),
        )
        Engine(key, replay, environment).flush()

        assertEquals(listOf(202, 202, 202), statuses)
        assertTrue(engine.state.queue.isEmpty())
        println("FLOAD_E2E_INSTALL_REFERENCE=${state.installReference}")
    }
}

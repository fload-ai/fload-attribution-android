package com.fload.attribution.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun fullState() = StoredState(
        installReference = "ins-00000001",
        firstLaunchAt = 1_800_000_000_123,
        device = FIXTURE_DEVICE.copy(locale = null),
        queue = listOf(
            QueuedRequest("req-00000001", RequestKind.INSTALL, 1_800_000_000_123, 2, 1_800_000_010_000, "user-1", 1),
            QueuedRequest("req-00000002", RequestKind.IDENTIFY, 1_800_000_000_500, nextAttemptAt = 1_800_000_000_500, appUserId = "ü 👍"),
        ),
        referrer = ReferrerSnapshot.fromPlay("utm_source=x", 1, 2, 3, 0, null, true),
        lastUserId = "user-1",
        revokedKeys = setOf("abc"),
        refusedKeys = mapOf("def" to 1_800_086_400_000),
    )

    @Test
    fun roundTripsTheState() {
        val directory = File(folder.root, "com.fload.attribution")
        val store = FileStateStore(directory)
        assertEquals(LoadResult.Missing, store.load())

        val state = fullState()
        store.save(state)
        assertEquals(LoadResult.Loaded(state), FileStateStore(directory).load())
        assertFalse("no temporary file is left", File(directory, "state.bin.tmp").exists())

        val minimal = state.copy(referrer = null, lastUserId = null, revokedKeys = emptySet(), refusedKeys = emptyMap(), queue = emptyList())
        store.save(minimal)
        assertEquals(LoadResult.Loaded(minimal), store.load())
    }

    @Test
    fun garbageIsCorrupt() {
        val store = FileStateStore(folder.root)
        store.file.writeText("not a state")
        assertEquals(LoadResult.Corrupt, store.load())
    }

    @Test
    fun aTruncatedFileIsCorrupt() {
        val store = FileStateStore(folder.root)
        store.save(fullState())
        store.file.writeBytes(store.file.readBytes().copyOf(40))
        assertEquals(LoadResult.Corrupt, store.load())
    }

    @Test
    fun aFileThatCannotBeReadIsUnreadableNotMissing() {
        val store = FileStateStore(folder.root)
        assertTrue(store.file.mkdirs()) // present, but not readable as a file
        assertEquals(LoadResult.Unreadable, store.load())
    }

    @Test
    fun theStateSurvivesAnEngineRelaunchFromDisk() {
        val store = FileStateStore(File(folder.root, "state"))
        val harness = Harness()
        val environment = harness.environment.let {
            Environment(it.endpoint, it.transport, it.referrer, store, it.device, it.now, it.jitter, it.makeId, it.log, null)
        }
        harness.transport.script(503)
        val first = Engine(Harness.KEY, Bootstrap.loadOrCreate(environment)!!, environment)
        first.flush()
        first.setUserId("user-1")

        val second = Engine(Harness.KEY, Bootstrap.loadOrCreate(environment)!!, environment)
        assertEquals(first.state, second.state)
        harness.advance(2_000)
        second.flush()
        assertEquals("user-1", harness.transport.bodies.last()["appUserId"])
        assertTrue(second.state.queue.isEmpty())
    }
}

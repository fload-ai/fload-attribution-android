package com.fload.attribution

import android.content.Context
import com.fload.attribution.core.Bootstrap
import com.fload.attribution.core.Cancellable
import com.fload.attribution.core.Engine
import com.fload.attribution.core.Environment
import com.fload.attribution.core.FileStateStore
import com.fload.attribution.core.Scheduler
import com.fload.attribution.core.UrlConnectionTransport
import com.fload.attribution.core.WriteKey
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The one SDK instance behind [Fload]'s static calls. Everything after
 * `configure` runs on one background thread, in order, so the engine is
 * never touched by two threads.
 */
internal object Runtime {
    private val lock = Any()
    private var executor: ScheduledExecutorService? = null
    private var bufferedUserId: String? = null

    /** Touched only on the executor's thread. */
    private var engine: Engine? = null
    private var triggers: Triggers? = null

    @Volatile
    var installReference: String? = null
        private set

    fun configure(context: Context, key: String, options: FloadOptions) {
        val log = AndroidLog(options.logLevel)
        if (!WriteKey.isWellFormed(key)) {
            log.error("Fload.configure: the key must be a write key (fload_pk_…). Attribution is off.")
            return
        }
        val app = context.applicationContext ?: context
        val started = synchronized(lock) {
            if (executor != null) {
                null
            } else {
                Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "fload-attribution").apply { isDaemon = true }
                }.also { executor = it }
            }
        }
        if (started == null) {
            log.warning("Fload.configure was called more than once; the first call stands.")
            return
        }
        started.execute { start(app, key, options, log, started) }
    }

    private fun start(app: Context, key: String, options: FloadOptions, log: AndroidLog, executor: ScheduledExecutorService) {
        val environment = Environment(
            endpoint = options.endpoint,
            transport = UrlConnectionTransport(),
            referrer = PlayInstallReferrer(app),
            store = FileStateStore(File(app.noBackupFilesDir, "com.fload.attribution")),
            device = { AndroidDevice.facts(app) },
            now = System::currentTimeMillis,
            jitter = { 0.5 + Random.nextDouble() * 0.5 },
            makeId = { UUID.randomUUID().toString() },
            log = log,
            scheduler = ExecutorScheduler(executor),
        )
        val state = Bootstrap.loadOrCreate(environment)
        if (state == null) {
            log.error("Fload: its storage cannot be read yet. Attribution is off for this launch.")
            return
        }
        val made = Engine(key, state, environment)
        engine = made
        installReference = state.installReference
        synchronized(lock) { bufferedUserId.also { bufferedUserId = null } }?.let(made::setUserId)
        made.flush()
        triggers = Triggers.register(app) { executor.execute { made.flush() } }
    }

    fun setUserId(userId: String) {
        val current = synchronized(lock) {
            executor ?: run {
                bufferedUserId = userId
                null
            }
        } ?: return
        current.execute {
            engine?.let {
                it.setUserId(userId)
                it.flush()
            }
        }
    }
}

private class ExecutorScheduler(private val executor: ScheduledExecutorService) : Scheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }
}

package com.fload.attribution.core

import java.io.IOException

internal class FakeTransport : Transport {
    data class Sent(val url: String, val headers: Map<String, String>, val body: String) {
        val path: String get() = url.substringAfter("://").substringAfter('/').let { "/$it" }
        val json: Map<String, Any?> get() = TestJson.parseObject(body)
    }

    private val scripted = ArrayDeque<() -> TransportResponse>()
    val sent = mutableListOf<Sent>()

    fun script(status: Int, retryAfter: String? = null) {
        scripted.addLast { TransportResponse(status, retryAfter) }
    }

    fun scriptOffline() {
        scripted.addLast { throw IOException("offline") }
    }

    val paths: List<String> get() = sent.map { it.path }
    val bodies: List<Map<String, Any?>> get() = sent.map { it.json }

    override fun send(url: String, headers: Map<String, String>, body: ByteArray): TransportResponse {
        sent += Sent(url, headers, String(body, Charsets.UTF_8))
        return scripted.removeFirstOrNull()?.invoke() ?: TransportResponse(202)
    }
}

internal class FakeReferrer(vararg results: ReferrerResult) : ReferrerSource {
    private val queue = ArrayDeque(results.toList())
    private val fallback = results.lastOrNull() ?: ok()
    var calls = 0
        private set

    override fun read(): ReferrerResult {
        calls += 1
        return queue.removeFirstOrNull() ?: fallback
    }

    companion object {
        fun ok(referrer: String? = "utm_source=google-play&utm_medium=organic") = ReferrerResult.Read(
            ReferrerSnapshot.fromPlay(
                referrer = referrer,
                clickAtSeconds = 1_799_999_000,
                installBeginAtSeconds = 1_799_999_100,
                clickServerAtSeconds = 1_799_999_001,
                installBeginServerAtSeconds = 0,
                installVersion = "1.2.3",
                googlePlayInstant = false,
            ),
        )
    }
}

internal class MemoryStore : StateStore {
    var stored: StoredState? = null
    var result: LoadResult? = null
    var failSaves = false

    override fun load(): LoadResult = result ?: stored?.let { LoadResult.Loaded(it) } ?: LoadResult.Missing

    override fun save(state: StoredState) {
        if (failSaves) throw IOException("disk full")
        stored = state
    }
}

internal object SilentLog : SdkLog {
    override fun error(message: String) = Unit
    override fun warning(message: String) = Unit
    override fun info(message: String) = Unit
    override fun debug(message: String) = Unit
}

internal class RecordingLog : SdkLog {
    val warnings = mutableListOf<String>()
    override fun error(message: String) = Unit
    override fun warning(message: String) {
        warnings += message
    }
    override fun info(message: String) = Unit
    override fun debug(message: String) = Unit
}

internal val FIXTURE_DEVICE = DeviceFacts(
    appId = "com.example.app",
    appVersion = "1.2.3",
    appBuild = "45",
    osVersion = "14",
    deviceModel = "Google Pixel 8",
    locale = "en-US",
)

/** One app's SDK, with every outside dependency faked. */
internal class Harness(
    val referrer: FakeReferrer = FakeReferrer(FakeReferrer.ok()),
    val store: MemoryStore = MemoryStore(),
    val log: SdkLog = SilentLog,
) {
    val transport = FakeTransport()
    var now: Long = 1_800_000_000_000
    private var ids = 0

    fun advance(ms: Long) {
        now += ms
    }

    val environment: Environment
        get() = Environment(
            endpoint = "https://ingest.example.test",
            transport = transport,
            referrer = referrer,
            store = store,
            device = { FIXTURE_DEVICE },
            now = { now },
            jitter = { 1.0 },
            makeId = { "id-%08d".format(++ids) },
            log = log,
            scheduler = null,
        )

    /** A launch: loads the stored state (or starts the install) and builds the engine. */
    fun launch(key: String = KEY): Engine? = Bootstrap.loadOrCreate(environment)?.let { Engine(key, it, environment) }

    companion object {
        const val KEY = "fload_pk_test_0123456789"
        const val HOUR: Long = 3_600_000
        const val DAY: Long = 24 * HOUR
    }
}

/** A small JSON reader for assertions: objects, strings, numbers, booleans, null. */
internal object TestJson {
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> = Reader(text).run { value().also { end() } } as Map<String, Any?>

    private class Reader(val text: String) {
        var at = 0

        fun end() {
            skip()
            check(at == text.length) { "trailing text at $at" }
        }

        fun value(): Any? {
            skip()
            return when (val char = text[at]) {
                '{' -> obj()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char.isDigit()) number() else error("unexpected '$char' at $at")
            }
        }

        private fun obj(): Map<String, Any?> {
            val result = linkedMapOf<String, Any?>()
            at += 1
            skip()
            if (text[at] == '}') return result.also { at += 1 }
            while (true) {
                skip()
                val key = string()
                skip()
                check(text[at++] == ':')
                check(!result.containsKey(key)) { "duplicate key $key" }
                result[key] = value()
                skip()
                when (text[at++]) {
                    ',' -> continue
                    '}' -> return result
                    else -> error("bad object at $at")
                }
            }
        }

        private fun string(): String {
            check(text[at++] == '"')
            val out = StringBuilder()
            while (true) {
                when (val char = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val escaped = text[at++]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> out.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                        else -> out.append(escaped)
                    }
                    else -> {
                        check(char >= ' ') { "raw control character in string" }
                        out.append(char)
                    }
                }
            }
        }

        private fun number(): Long {
            val start = at
            while (at < text.length && (text[at] == '-' || text[at].isDigit())) at += 1
            return text.substring(start, at).toLong()
        }

        private fun literal(word: String, value: Any?): Any? {
            check(text.startsWith(word, at))
            at += word.length
            return value
        }

        private fun skip() {
            while (at < text.length && text[at].isWhitespace()) at += 1
        }
    }
}

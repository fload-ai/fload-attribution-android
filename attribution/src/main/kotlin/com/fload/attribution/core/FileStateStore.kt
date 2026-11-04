package com.fload.attribution.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The state as one small binary file, written to a temporary file and
 * renamed over the old one (atomic on Android's file systems). The SDK puts
 * it in `noBackupFilesDir`, so a restored backup is a new install.
 */
internal class FileStateStore(private val directory: File) : StateStore {
    val file: File = File(directory, "state.bin")

    override fun load(): LoadResult {
        if (!file.exists()) return LoadResult.Missing
        val bytes = try {
            file.readBytes()
        } catch (error: IOException) {
            return LoadResult.Unreadable
        }
        return try {
            LoadResult.Loaded(StateCodec.decode(bytes))
        } catch (error: Exception) {
            LoadResult.Corrupt
        }
    }

    override fun save(state: StoredState) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.name}")
        val temporary = File(directory, "state.bin.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(StateCodec.encode(state))
            out.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("cannot replace ${file.name}")
    }
}

/**
 * The state's file format: a magic, a version, then the fields in order.
 * A new field is appended under a new version, and older versions still decode.
 */
internal object StateCodec {
    private const val MAGIC = 0x464C4441 // "FLDA"
    private const val VERSION = 1

    fun encode(state: StoredState): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(state.installReference)
            out.writeLong(state.firstLaunchAt)
            writeDevice(out, state.device)
            out.writeInt(state.queue.size)
            state.queue.forEach { writeRequest(out, it) }
            out.writeBoolean(state.referrer != null)
            state.referrer?.let { writeReferrer(out, it) }
            writeNullable(out, state.lastUserId)
            out.writeInt(state.revokedKeys.size)
            state.revokedKeys.forEach(out::writeUTF)
            out.writeInt(state.refusedKeys.size)
            state.refusedKeys.forEach { (key, until) ->
                out.writeUTF(key)
                out.writeLong(until)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): StoredState {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "not a Fload state file" }
            val version = input.readInt()
            require(version in 1..VERSION) { "unknown state version $version" }
            val installReference = input.readUTF()
            val firstLaunchAt = input.readLong()
            val device = readDevice(input)
            val queue = List(count(input)) { readRequest(input) }
            val referrer = if (input.readBoolean()) readReferrer(input) else null
            val lastUserId = readNullable(input)
            val revoked = List(count(input)) { input.readUTF() }.toSet()
            val refused = List(count(input)) { input.readUTF() to input.readLong() }.toMap()
            return StoredState(installReference, firstLaunchAt, device, queue, referrer, lastUserId, revoked, refused)
        }
    }

    private fun count(input: DataInputStream): Int = input.readInt().also { require(it in 0..10_000) { "bad count" } }

    private fun writeDevice(out: DataOutputStream, device: DeviceFacts) {
        out.writeUTF(device.appId)
        listOf(device.appVersion, device.appBuild, device.osVersion, device.deviceModel, device.locale)
            .forEach { writeNullable(out, it) }
    }

    private fun readDevice(input: DataInputStream) = DeviceFacts(
        appId = input.readUTF(),
        appVersion = readNullable(input),
        appBuild = readNullable(input),
        osVersion = readNullable(input),
        deviceModel = readNullable(input),
        locale = readNullable(input),
    )

    private fun writeRequest(out: DataOutputStream, request: QueuedRequest) {
        out.writeUTF(request.requestId)
        out.writeUTF(request.kind.name)
        out.writeLong(request.createdAt)
        out.writeInt(request.failures)
        out.writeLong(request.nextAttemptAt)
        writeNullable(out, request.appUserId)
        out.writeInt(request.referrerFailures)
    }

    private fun readRequest(input: DataInputStream) = QueuedRequest(
        requestId = input.readUTF(),
        kind = RequestKind.valueOf(input.readUTF()),
        createdAt = input.readLong(),
        failures = input.readInt(),
        nextAttemptAt = input.readLong(),
        appUserId = readNullable(input),
        referrerFailures = input.readInt(),
    )

    private fun writeReferrer(out: DataOutputStream, referrer: ReferrerSnapshot) {
        out.writeUTF(referrer.status.name)
        writeNullable(out, referrer.referrer)
        out.writeLong(referrer.clickAtSeconds)
        out.writeLong(referrer.installBeginAtSeconds)
        out.writeLong(referrer.clickServerAtSeconds)
        out.writeLong(referrer.installBeginServerAtSeconds)
        writeNullable(out, referrer.installVersion)
        out.writeByte(
            when (referrer.googlePlayInstant) {
                null -> -1
                false -> 0
                true -> 1
            },
        )
    }

    private fun readReferrer(input: DataInputStream) = ReferrerSnapshot(
        status = ReferrerStatus.valueOf(input.readUTF()),
        referrer = readNullable(input),
        clickAtSeconds = input.readLong(),
        installBeginAtSeconds = input.readLong(),
        clickServerAtSeconds = input.readLong(),
        installBeginServerAtSeconds = input.readLong(),
        installVersion = readNullable(input),
        googlePlayInstant = when (input.readByte().toInt()) {
            0 -> false
            1 -> true
            else -> null
        },
    )

    private fun writeNullable(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(value)
    }

    private fun readNullable(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null
}

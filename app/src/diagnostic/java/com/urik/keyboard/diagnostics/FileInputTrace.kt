package com.urik.keyboard.diagnostics

import android.view.inputmethod.InputConnection
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Persists the "recording since" timestamp; 0 means recording is off. */
interface TraceStateStore {
    var recordingSinceWallMs: Long
}

/**
 * Appends one JSON object per line to app-private storage.
 *
 * Guards: off by default, auto-off after [MAX_RECORDING_MS], nothing recorded while the
 * current field is secure (a single `field_skipped` event is written instead), size capped
 * at [MAX_FILE_BYTES] with one rotated file. Switching recording off wipes the files.
 */
class FileInputTrace(
    private val dir: File,
    private val store: TraceStateStore,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monoClock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "urik-input-trace").apply { isDaemon = true }
        }
) : InputTrace {
    @Volatile private var secureField = false

    private var writer: BufferedWriter? = null
    private var writtenBytes = 0L

    private var wrappedTarget: InputConnection? = null
    private var wrapper: InputConnection? = null

    override val recording: Boolean
        get() {
            val since = store.recordingSinceWallMs
            if (since <= 0L) return false
            if (wallClock() - since >= MAX_RECORDING_MS) {
                // Auto-off keeps the file so it can still be exported; the user wipes it.
                store.recordingSinceWallMs = 0L
                write(TYPE_AUTO_OFF, emptyMap())
                executor.execute { closeWriterNow() }
                return false
            }
            return true
        }

    override val enabled: Boolean
        get() = !secureField && recording

    val remainingMs: Long
        get() {
            val since = store.recordingSinceWallMs
            return if (since <= 0L) 0L else (MAX_RECORDING_MS - (wallClock() - since)).coerceAtLeast(0L)
        }

    val files: List<File>
        get() = listOf(rotatedFile, currentFile).filter { it.exists() && it.length() > 0 }

    private val currentFile get() = File(dir, FILE_NAME)
    private val rotatedFile get() = File(dir, ROTATED_FILE_NAME)

    fun start() {
        if (store.recordingSinceWallMs > 0L) return
        // secureField deliberately keeps the last field's state: never assume "not secure".
        store.recordingSinceWallMs = wallClock()
        write(TYPE_RECORDING_ON, mapOf("wall" to wallClock()))
    }

    /** Switches recording off and deletes every recorded file. */
    fun stopAndWipe() {
        store.recordingSinceWallMs = 0L
        wipe()
    }

    fun wipe() {
        executor
            .submit {
                closeWriterNow()
                dir.listFiles()?.forEach { it.delete() }
            }.get(IO_TIMEOUT_S, TimeUnit.SECONDS)
    }

    /** Blocks until every queued event has been written; used before export and in tests. */
    fun flush() {
        executor.submit { writer?.flush() }.get(IO_TIMEOUT_S, TimeUnit.SECONDS)
    }

    override fun record(type: String, fields: Map<String, Any?>) {
        if (enabled) write(type, fields)
    }

    override fun startField(packageName: String?, fields: Map<String, Any?>, secure: Boolean) {
        if (!recording) {
            secureField = secure
            return
        }
        if (secure) {
            write(TYPE_FIELD_SKIPPED, mapOf("pkg" to packageName))
            secureField = true
        } else {
            secureField = false
            write(TYPE_INPUT_START, mapOf("pkg" to packageName) + fields)
        }
    }

    override fun marker() {
        record(TYPE_MARKER, emptyMap())
    }

    override fun wrap(ic: InputConnection?): InputConnection? {
        if (ic == null || !enabled) return ic
        if (ic === wrappedTarget) return wrapper
        val newWrapper = TracingInputConnection(ic, this)
        wrappedTarget = ic
        wrapper = newWrapper
        return newWrapper
    }

    private fun write(type: String, fields: Map<String, Any?>) {
        val line =
            buildString {
                append("{\"t\":").append(monoClock()).append(",\"type\":")
                Json.appendValue(this, type)
                for ((key, value) in fields) {
                    append(',')
                    Json.appendValue(this, key)
                    append(':')
                    Json.appendValue(this, value)
                }
                append("}\n")
            }
        executor.execute { writeLine(line) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeLine(line: String) {
        try {
            val bytes = line.toByteArray(Charsets.UTF_8).size
            if (writer == null) openWriter()
            if (writtenBytes + bytes > MAX_FILE_BYTES) {
                closeWriterNow()
                rotatedFile.delete()
                currentFile.renameTo(rotatedFile)
                openWriter()
            }
            writer?.write(line)
            writer?.flush()
            writtenBytes += bytes
        } catch (_: Exception) {
            // Diagnostics must never disturb typing.
        }
    }

    private fun openWriter() {
        dir.mkdirs()
        writtenBytes = if (currentFile.exists()) currentFile.length() else 0L
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(currentFile, true), Charsets.UTF_8))
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun closeWriterNow() {
        try {
            writer?.close()
        } catch (_: Exception) {
            // Ignored: nothing useful to do if close fails.
        }
        writer = null
        writtenBytes = 0L
    }

    companion object {
        const val MAX_RECORDING_MS = 4L * 60 * 60 * 1000
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val FILE_NAME = "urik_trace.jsonl"
        const val ROTATED_FILE_NAME = "urik_trace.1.jsonl"
        private const val IO_TIMEOUT_S = 5L
        private const val NANOS_PER_MS = 1_000_000L

        const val TYPE_RECORDING_ON = "recording_on"
        const val TYPE_AUTO_OFF = "auto_off"
        const val TYPE_INPUT_START = "input_start"
        const val TYPE_FIELD_SKIPPED = "field_skipped"
        const val TYPE_MARKER = "marker"
    }
}

/** Minimal JSON encoder (avoids org.json, which is stubbed in JVM unit tests). */
internal object Json {
    fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean, is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Number -> if (value.toDouble().isFinite()) sb.append(value.toString()) else sb.append("null")
            is Map<*, *> -> appendObject(sb, value)
            is Iterable<*> -> appendArray(sb, value)
            is Array<*> -> appendArray(sb, value.asList())
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendObject(sb: StringBuilder, map: Map<*, *>) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            appendString(sb, k.toString())
            sb.append(':')
            appendValue(sb, v)
        }
        sb.append('}')
    }

    private fun appendArray(sb: StringBuilder, items: Iterable<*>) {
        sb.append('[')
        var first = true
        for (v in items) {
            if (!first) sb.append(',')
            first = false
            appendValue(sb, v)
        }
        sb.append(']')
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

package com.urik.keyboard.diagnostics

import android.view.inputmethod.InputConnection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class FileInputTraceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MemoryStore : TraceStateStore {
        override var recordingSinceWallMs: Long = 0L
    }

    private var now = 1_000_000L
    private lateinit var dir: File
    private lateinit var store: MemoryStore
    private lateinit var trace: FileInputTrace

    @Before
    fun setUp() {
        dir = File(tmp.root, "trace")
        store = MemoryStore()
        trace = FileInputTrace(dir, store, wallClock = { now }, monoClock = { now })
    }

    private fun lines(): List<String> {
        trace.flush()
        return trace.files.flatMap { it.readLines() }
    }

    private fun types(): List<String> = lines().map { Regex("\"type\":\"([a-z_]+)\"").find(it)!!.groupValues[1] }

    @Test
    fun offByDefault_recordsNothing() {
        assertFalse(trace.recording)
        assertFalse(trace.enabled)
        trace.record("key", mapOf("key" to "a"))
        trace.marker()
        trace.flush()
        assertTrue(trace.files.isEmpty())
    }

    @Test
    fun recording_appendsOneJsonObjectPerLine() {
        trace.start()
        trace.record("key", mapOf("key" to "a", "n" to 1, "ok" to true, "none" to null))
        trace.marker()

        val all = lines()
        assertEquals(listOf("recording_on", "key", "marker"), types())
        assertEquals("{\"t\":1000000,\"type\":\"key\",\"key\":\"a\",\"n\":1,\"ok\":true,\"none\":null}", all[1])
    }

    @Test
    fun json_escapesQuotesNewlinesAndControlChars() {
        trace.start()
        trace.record("ic_op", mapOf("text" to "a\"b\\c\nd\u0001", "list" to listOf(1, "x")))

        assertEquals(
            "{\"t\":1000000,\"type\":\"ic_op\",\"text\":\"a\\\"b\\\\c\\nd\\u0001\",\"list\":[1,\"x\"]}",
            lines()[1]
        )
    }

    @Test
    fun secureField_suppressesEverything_andLogsSingleSkip() {
        val ic = mock(InputConnection::class.java)
        trace.start()

        trace.startField("com.example.bank", mapOf("input_type" to "0x81"), secure = true)
        trace.record("key", mapOf("key" to "p"))
        trace.record("ic_op", mapOf("text" to "password"))
        trace.marker()

        assertFalse(trace.enabled)
        assertTrue(trace.recording)
        assertSame("secure fields must get the raw connection", ic, trace.wrap(ic))
        assertEquals(listOf("recording_on", "field_skipped"), types())
        assertFalse(lines().any { it.contains("password") || it.contains("0x81") })
    }

    @Test
    fun leavingSecureField_resumesRecording() {
        trace.start()
        trace.startField("com.example.bank", emptyMap(), secure = true)
        trace.startField("com.example.chat", emptyMap(), secure = false)
        trace.record("key", mapOf("key" to "h"))

        assertEquals(listOf("recording_on", "field_skipped", "input_start", "key"), types())
    }

    @Test
    fun secureFieldStartedWhileOff_staysSuppressedAfterStart_untilNextField() {
        trace.startField("com.example.bank", emptyMap(), secure = true)
        trace.start()
        trace.record("key", mapOf("key" to "p"))

        assertFalse(trace.enabled)
        assertEquals(listOf("recording_on"), types())
    }

    @Test
    fun autoOff_afterFourHours_stopsButKeepsFileForExport() {
        trace.start()
        trace.record("key", mapOf("key" to "a"))

        now += FileInputTrace.MAX_RECORDING_MS
        assertFalse(trace.recording)
        trace.record("key", mapOf("key" to "b"))

        assertEquals(0L, store.recordingSinceWallMs)
        assertEquals(listOf("recording_on", "key", "auto_off"), types())
    }

    @Test
    fun remainingMs_countsDown() {
        assertEquals(0L, trace.remainingMs)
        trace.start()
        now += 60_000L
        assertEquals(FileInputTrace.MAX_RECORDING_MS - 60_000L, trace.remainingMs)
    }

    @Test
    fun stopAndWipe_deletesRecording() {
        trace.start()
        trace.record("key", mapOf("key" to "a"))
        trace.flush()
        assertTrue(trace.files.isNotEmpty())

        trace.stopAndWipe()

        assertFalse(trace.recording)
        assertTrue(trace.files.isEmpty())
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun rotation_capsSizeAtTwoFiles() {
        trace.start()
        val payload = "x".repeat(1000)
        repeat(4500) { trace.record("ic_op", mapOf("text" to payload)) }
        trace.flush()

        val files = trace.files
        assertEquals(2, files.size)
        files.forEach { assertTrue(it.length() <= FileInputTrace.MAX_FILE_BYTES) }
        assertTrue(File(dir, FileInputTrace.ROTATED_FILE_NAME).exists())
    }
}

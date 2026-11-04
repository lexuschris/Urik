package com.urik.keyboard.service

import android.view.inputmethod.InputConnection
import com.urik.keyboard.diagnostics.InputTrace
import com.urik.keyboard.diagnostics.NoOpInputTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OnUpdateSelectionTraceTest {
    private class RecordingTrace(override var enabled: Boolean = true) : InputTrace {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override val recording: Boolean get() = enabled

        override fun record(type: String, fields: Map<String, Any?>) {
            events += type to fields
        }

        override fun startField(packageName: String?, fields: Map<String, Any?>, secure: Boolean) = Unit

        override fun marker() = Unit

        override fun wrap(ic: InputConnection?): InputConnection? = ic
    }

    private lateinit var inputState: InputStateManager
    private lateinit var outputBridge: OutputBridge
    private lateinit var trace: RecordingTrace
    private lateinit var handler: OnUpdateSelectionHandler

    @Before
    fun setUp() {
        inputState = InputStateManager(
            viewCallback = mock(ViewCallback::class.java),
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )
        outputBridge = mock(OutputBridge::class.java)
        trace = RecordingTrace()
        handler = OnUpdateSelectionHandler(
            inputState = inputState,
            outputBridge = outputBridge,
            imeStateCoordinator = mock(ImeStateCoordinator::class.java),
            onCheckAutoCapitalization = {},
            trace = trace
        )
    }

    private fun lastSel(): Map<String, Any?> {
        val (type, fields) = trace.events.last()
        assertEquals(OnUpdateSelectionHandler.TRACE_EVENT, type)
        return fields
    }

    @Test
    fun trace_namesTrackedRegionEarlyReturn_whenAppDropsComposingRegion() {
        // The cursor-drag suspect: app reports -1 candidates, cursor still inside the tracked word.
        inputState.displayBuffer = "hello"
        inputState.composingRegionStart = 10
        inputState.selectionStateTracker.updateSelection(15, 15, 10, 15)

        handler.handle(newSelStart = 12, newSelEnd = 12, candidatesStart = -1, candidatesEnd = -1)

        val sel = lastSel()
        assertEquals("invalidation_in_tracked_region", sel["branch"])
        assertEquals(listOf(-1, -1), sel["cand"])
        assertEquals("hello", sel["buffer_before"])
        assertEquals(10, sel["region_start_before"])
    }

    @Test
    fun trace_namesReassertBranch_whenComposingRegionReasserted() {
        inputState.displayBuffer = "hello"
        inputState.composingRegionStart = 10
        inputState.selectionStateTracker.updateSelection(12, 12, 10, 15)
        `when`(outputBridge.reassertComposingRegion(5)).thenReturn(true)

        handler.handle(newSelStart = 5, newSelEnd = 5, candidatesStart = -1, candidatesEnd = -1)

        assertTrue((lastSel()["branch"] as String).endsWith("_reassert"))
    }

    @Test
    fun trace_namesInvalidateBranch_whenReassertFails() {
        inputState.displayBuffer = "hello"
        inputState.composingRegionStart = 10
        inputState.selectionStateTracker.updateSelection(12, 12, 10, 15)
        `when`(outputBridge.reassertComposingRegion(5)).thenReturn(false)

        handler.handle(newSelStart = 5, newSelEnd = 5, candidatesStart = -1, candidatesEnd = -1)

        assertTrue((lastSel()["branch"] as String).endsWith("_invalidate"))
    }

    @Test
    fun trace_namesDirectCommitBranch_inSecureField() {
        inputState.isSecureField = true

        handler.handle(5, 5, -1, -1)

        assertEquals("direct_commit_in_progress", lastSel()["branch"])
        assertNull(lastSel()["result"])
    }

    @Test
    fun trace_recordsNothing_whenDisabled() {
        trace.enabled = false
        inputState.displayBuffer = "hello"
        inputState.composingRegionStart = 10

        handler.handle(newSelStart = 12, newSelEnd = 12, candidatesStart = -1, candidatesEnd = -1)

        assertTrue(trace.events.isEmpty())
    }

    @Test
    fun noOpTrace_isInertAndPassesConnectionThrough() {
        val ic = mock(InputConnection::class.java)
        assertFalse(NoOpInputTrace.enabled)
        assertFalse(NoOpInputTrace.recording)
        assertSame(ic, NoOpInputTrace.wrap(ic))
        assertNull(NoOpInputTrace.wrap(null))
    }
}

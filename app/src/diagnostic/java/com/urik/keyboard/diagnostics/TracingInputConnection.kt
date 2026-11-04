package com.urik.keyboard.diagnostics

import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * Records every write the keyboard sends to the app (`ic_op` events), with the text
 * around the cursor once the edit has landed: outside batch edits, or when the
 * outermost batch closes.
 */
class TracingInputConnection(private val target: InputConnection, private val trace: InputTrace) :
    InputConnectionWrapper(target, false) {
    private var batchDepth = 0

    private fun op(name: String, vararg args: Pair<String, Any?>, result: Any?) {
        if (!trace.enabled) return
        val fields = LinkedHashMap<String, Any?>()
        fields["op"] = name
        for ((k, v) in args) fields[k] = v
        fields["ret"] = result
        fields["depth"] = batchDepth
        if (batchDepth == 0) addContext(fields)
        trace.record(TYPE_IC_OP, fields)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun addContext(fields: MutableMap<String, Any?>) {
        try {
            fields["before"] = target.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString()
            fields["after"] = target.getTextAfterCursor(CONTEXT_CHARS, 0)?.toString()
        } catch (_: Exception) {
            fields["context_error"] = true
        }
    }

    override fun beginBatchEdit(): Boolean {
        val r = super.beginBatchEdit()
        batchDepth++
        op("beginBatchEdit", result = r)
        return r
    }

    override fun endBatchEdit(): Boolean {
        val r = super.endBatchEdit()
        batchDepth = (batchDepth - 1).coerceAtLeast(0)
        op("endBatchEdit", result = r)
        return r
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val r = super.commitText(text, newCursorPosition)
        op("commitText", "text" to text?.toString(), "pos" to newCursorPosition, result = r)
        return r
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val r = super.setComposingText(text, newCursorPosition)
        op("setComposingText", "text" to text?.toString(), "pos" to newCursorPosition, result = r)
        return r
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val r = super.setComposingRegion(start, end)
        op("setComposingRegion", "start" to start, "end" to end, result = r)
        return r
    }

    override fun finishComposingText(): Boolean {
        val r = super.finishComposingText()
        op("finishComposingText", result = r)
        return r
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val r = super.deleteSurroundingText(beforeLength, afterLength)
        op("deleteSurroundingText", "before_len" to beforeLength, "after_len" to afterLength, result = r)
        return r
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val r = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        op("deleteSurroundingTextInCodePoints", "before_len" to beforeLength, "after_len" to afterLength, result = r)
        return r
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val r = super.setSelection(start, end)
        op("setSelection", "start" to start, "end" to end, result = r)
        return r
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        val r = super.sendKeyEvent(event)
        op(
            "sendKeyEvent",
            "action" to event?.action,
            "code" to event?.keyCode?.let { KeyEvent.keyCodeToString(it) },
            result = r
        )
        return r
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        val r = super.commitCorrection(correctionInfo)
        op(
            "commitCorrection",
            "old" to correctionInfo?.oldText?.toString(),
            "new" to correctionInfo?.newText?.toString(),
            "offset" to correctionInfo?.offset,
            result = r
        )
        return r
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        val r = super.commitCompletion(text)
        op("commitCompletion", "text" to text?.text?.toString(), result = r)
        return r
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        val r = super.performEditorAction(editorAction)
        op("performEditorAction", "action" to editorAction, result = r)
        return r
    }

    companion object {
        const val TYPE_IC_OP = "ic_op"
        const val CONTEXT_CHARS = 20
    }
}

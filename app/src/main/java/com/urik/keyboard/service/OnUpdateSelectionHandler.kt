package com.urik.keyboard.service

import androidx.annotation.VisibleForTesting
import com.urik.keyboard.diagnostics.InputTrace
import com.urik.keyboard.diagnostics.NoOpInputTrace
import com.urik.keyboard.diagnostics.event
import com.urik.keyboard.utils.CursorEditingUtils
import com.urik.keyboard.utils.SelectionChangeResult

class OnUpdateSelectionHandler(
    private val inputState: InputStateManager,
    private val outputBridge: OutputBridge,
    private val imeStateCoordinator: ImeStateCoordinator,
    private val onCheckAutoCapitalization: (textBefore: String) -> Unit,
    private val trace: InputTrace = NoOpInputTrace
) {
    private var lastSelectionResult: SelectionChangeResult? = null

    fun handle(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        val bufferBefore = if (trace.enabled) inputState.displayBuffer else ""
        val regionStartBefore = inputState.composingRegionStart
        val branch = route(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        trace.event(TRACE_EVENT) {
            mapOf(
                "sel" to listOf(newSelStart, newSelEnd),
                "cand" to listOf(candidatesStart, candidatesEnd),
                "branch" to branch,
                "result" to lastSelectionResult?.let { it::class.simpleName },
                "buffer_before" to bufferBefore,
                "region_start_before" to regionStartBefore,
                "buffer_after" to inputState.displayBuffer,
                "region_start_after" to inputState.composingRegionStart
            )
        }
    }

    /** Handles the selection update and returns the name of the branch that handled it. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun route(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int): String {
        lastSelectionResult = null
        if (handleDirectCommitInProgress()) return "direct_commit_in_progress"

        val selectionResult =
            inputState.selectionStateTracker.updateSelection(
                newSelStart = newSelStart,
                newSelEnd = newSelEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd
            )
        lastSelectionResult = selectionResult

        if (newSelStart == 0 && newSelEnd == 0) {
            val textBefore = outputBridge.safeGetTextBeforeCursor(50)
            val textAfter = outputBridge.safeGetTextAfterCursor(50)

            if (CursorEditingUtils.shouldClearStateOnEmptyField(
                    newSelStart,
                    newSelEnd,
                    textBefore,
                    textAfter,
                    inputState.displayBuffer,
                    inputState.wordState.hasContent
                )
            ) {
                invalidateComposingStateOnCursorJump()
            }

            if (!inputState.isUrlOrEmailField && !inputState.isTerminalField) {
                onCheckAutoCapitalization(textBefore)
            }
        }

        if (handleAppSelectionExtended(selectionResult, newSelStart)) return "app_selection_extended"

        if (handleUrlOrEmailField()) return "url_or_email_field"

        if (handleNonSequentialJump(selectionResult, newSelStart, newSelEnd)) return "non_sequential_jump"

        val ousConsumed = inputState.tryConsumeTypingOus(newSelStart, candidatesStart, candidatesEnd)
        if (handleTypingOusConsumed(ousConsumed, newSelStart)) return "typing_ous_consumed"

        val hasComposingText = candidatesStart != -1 && candidatesEnd != -1
        val cursorInComposingRegion =
            hasComposingText &&
                newSelStart >= candidatesStart &&
                newSelStart <= candidatesEnd &&
                newSelEnd >= candidatesStart &&
                newSelEnd <= candidatesEnd

        val trackedComposingEnd =
            if (inputState.composingRegionStart != -1 && inputState.displayBuffer.isNotEmpty()) {
                inputState.composingRegionStart + inputState.displayBuffer.length
            } else {
                -1
            }
        val cursorInTrackedComposingRegion =
            trackedComposingEnd != -1 &&
                newSelStart >= inputState.composingRegionStart &&
                newSelStart <= trackedComposingEnd &&
                newSelEnd >= inputState.composingRegionStart &&
                newSelEnd <= trackedComposingEnd

        if (handleActivelyEditing(newSelStart)) return "actively_editing"

        if (selectionResult.requiresStateInvalidation()) {
            if (cursorInComposingRegion || cursorInTrackedComposingRegion) {
                inputState.lastKnownCursorPosition = newSelStart
                return if (cursorInComposingRegion) "invalidation_in_region" else "invalidation_in_tracked_region"
            }
            val reasserted = inputState.displayBuffer.isNotEmpty() && outputBridge.reassertComposingRegion(newSelStart)
            if (!reasserted) invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return if (reasserted) "invalidation_reassert" else "invalidation_invalidate"
        }

        if (hasComposingText && !cursorInComposingRegion) {
            invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return "outside_composing_region"
        }

        if (!hasComposingText && (inputState.wordState.hasContent || inputState.displayBuffer.isNotEmpty())) {
            val reasserted = inputState.displayBuffer.isNotEmpty() && outputBridge.reassertComposingRegion(newSelStart)
            if (!reasserted) invalidateComposingStateOnCursorJump()
            inputState.lastKnownCursorPosition = newSelStart
            return if (reasserted) "no_region_reassert" else "no_region_invalidate"
        }

        inputState.lastKnownCursorPosition = newSelStart

        if (!hasComposingText && !inputState.isActivelyEditing && newSelStart == newSelEnd) {
            outputBridge.attemptRecompositionAtCursor(newSelStart)
            return "recompose_attempt"
        }
        return "in_region_no_op"
    }

    @VisibleForTesting
    internal fun handleDirectCommitInProgress(): Boolean = inputState.requiresDirectCommit

    @VisibleForTesting
    internal fun handleAppSelectionExtended(selectionResult: SelectionChangeResult, newSelStart: Int): Boolean {
        if (selectionResult !is SelectionChangeResult.AppSelectionExtended) return false
        if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
            inputState.clearInternalStateOnly()
            inputState.isActivelyEditing = false
        }
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    @VisibleForTesting
    internal fun handleUrlOrEmailField(): Boolean = inputState.isUrlOrEmailField

    @VisibleForTesting
    internal fun handleNonSequentialJump(
        selectionResult: SelectionChangeResult,
        newSelStart: Int,
        newSelEnd: Int
    ): Boolean {
        if (selectionResult !is SelectionChangeResult.NonSequentialJump) return false
        if (inputState.displayBuffer.isNotEmpty() || inputState.wordState.hasContent) {
            invalidateComposingStateOnCursorJump()
        }
        inputState.lastKnownCursorPosition = newSelStart
        if (newSelStart == newSelEnd) {
            outputBridge.attemptRecompositionAtCursor(newSelStart)
        }
        return true
    }

    @VisibleForTesting
    internal fun handleTypingOusConsumed(ousConsumed: Boolean, newSelStart: Int): Boolean {
        if (!ousConsumed) return false
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    @VisibleForTesting
    internal fun handleActivelyEditing(newSelStart: Int): Boolean {
        if (!inputState.isActivelyEditing) return false
        inputState.isActivelyEditing = false
        inputState.lastKnownCursorPosition = newSelStart
        return true
    }

    private fun invalidateComposingStateOnCursorJump() = imeStateCoordinator.invalidateComposingStateOnCursorJump()

    companion object {
        const val TRACE_EVENT = "sel"
    }
}

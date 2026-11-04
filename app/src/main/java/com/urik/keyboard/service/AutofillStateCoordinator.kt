package com.urik.keyboard.service

import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Suppress("NewApi")
class AutofillStateCoordinator(
    private val tracker: AutofillStateTracker,
    private val candidateBarController: CandidateBarController,
    private val serviceScope: CoroutineScope,
    private val displaySuggestions: (List<InlineSuggestion>) -> Unit
) {
    fun onFieldChanged(inputType: Int, imeOptions: Int, fieldId: Int, packageHash: Int) {
        tracker.cancelPendingClear()
        tracker.onFieldChanged(inputType, imeOptions, fieldId, packageHash)
    }

    fun onInputViewStarted(isViewReady: Boolean) {
        if (!isViewReady) return
        tracker.drainPendingResponse()?.let { buffered ->
            if (!tracker.isDismissed() && buffered.inlineSuggestions.isNotEmpty()) {
                displaySuggestions(buffered.inlineSuggestions)
            }
        }
    }

    fun onInputViewFinished() {
        tracker.scheduleClear(serviceScope) {
            candidateBarController.forceClearAllSuggestions()
        }
    }

    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse, isViewReady: Boolean): Boolean {
        val suggestions = response.inlineSuggestions

        if (suggestions.isEmpty()) {
            tracker.dropSuppressedResponse()
            serviceScope.launch(Dispatchers.Main) {
                candidateBarController.forceClearAllSuggestions()
            }
            return false
        }

        if (tracker.isDismissed()) {
            // Typing dismissed the strip for this field. Keep the latest offer in case the
            // user clears the field again (see onFieldEmptied).
            tracker.holdSuppressedResponse(response)
            return true
        }

        if (!isViewReady) {
            tracker.bufferResponse(response)
            return true
        }

        displaySuggestions(suggestions)
        return true
    }

    fun onKeyInput() {
        if (candidateBarController.clearAutofillIfShowing()) {
            tracker.dismiss()
        }
    }

    /**
     * The field became empty again (e.g. the user backspaced over what they typed).
     * Typing is no longer a reason to hide autofill, so end the dismissal and show the
     * offer that arrived meanwhile, if any. Later responses display normally either way,
     * so this works whether the framework's new response arrives before or after this call.
     */
    fun onFieldEmptied(isViewReady: Boolean) {
        if (!tracker.isDismissed()) return
        val held = tracker.clearDismissal() ?: return
        if (held.inlineSuggestions.isEmpty()) return
        if (isViewReady) displaySuggestions(held.inlineSuggestions) else tracker.bufferResponse(held)
    }

    fun cleanup() {
        tracker.cleanup()
    }
}

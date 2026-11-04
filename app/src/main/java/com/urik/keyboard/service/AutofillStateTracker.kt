package com.urik.keyboard.service

import android.view.inputmethod.InlineSuggestionsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages autofill inline suggestion state across IME lifecycle transitions.
 */
@Suppress("NewApi")
class AutofillStateTracker {
    var pendingResponse: InlineSuggestionsResponse? = null
        private set

    var userDismissed: Boolean = false
        private set

    /** Latest non-empty response that arrived while dismissed; restored if the field is emptied. */
    var suppressedResponse: InlineSuggestionsResponse? = null
        private set

    private var lastFieldIdentityHash: Int = 0
    private var clearJob: Job? = null

    fun onFieldChanged(inputType: Int, imeOptions: Int, fieldId: Int, packageHash: Int): Boolean {
        var identityHash = inputType
        identityHash = 31 * identityHash + imeOptions
        identityHash = 31 * identityHash + fieldId
        identityHash = 31 * identityHash + packageHash
        val changed = identityHash != lastFieldIdentityHash
        lastFieldIdentityHash = identityHash
        if (changed) {
            userDismissed = false
            suppressedResponse = null
        }
        return changed
    }

    fun bufferResponse(response: InlineSuggestionsResponse) {
        pendingResponse = response
    }

    fun drainPendingResponse(): InlineSuggestionsResponse? {
        val response = pendingResponse
        pendingResponse = null
        return response
    }

    fun dismiss() {
        userDismissed = true
    }

    fun isDismissed(): Boolean = userDismissed

    fun holdSuppressedResponse(response: InlineSuggestionsResponse) {
        suppressedResponse = response
    }

    fun dropSuppressedResponse() {
        suppressedResponse = null
    }

    /** Ends a typing dismissal (the field was emptied); returns any response held meanwhile. */
    fun clearDismissal(): InlineSuggestionsResponse? {
        userDismissed = false
        val held = suppressedResponse
        suppressedResponse = null
        return held
    }

    fun scheduleClear(scope: CoroutineScope, block: suspend () -> Unit) {
        clearJob?.cancel()
        clearJob =
            scope.launch {
                delay(100)
                block()
            }
    }

    fun cancelPendingClear() {
        clearJob?.cancel()
        clearJob = null
    }

    fun cleanup() {
        clearJob?.cancel()
        clearJob = null
        pendingResponse = null
        userDismissed = false
        suppressedResponse = null
        lastFieldIdentityHash = 0
    }
}

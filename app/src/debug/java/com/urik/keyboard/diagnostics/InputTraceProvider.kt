package com.urik.keyboard.diagnostics

import android.content.Context

/** Non-diagnostic builds: the recorder does not exist. */
object InputTraceProvider {
    @Suppress("UNUSED_PARAMETER")
    fun get(context: Context): InputTrace = NoOpInputTrace

    /** Settings activity for the recorder, or null when the build has none. */
    val settingsActivity: Class<*>? = null
}

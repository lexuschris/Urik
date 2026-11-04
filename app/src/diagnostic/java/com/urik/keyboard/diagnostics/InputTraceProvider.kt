package com.urik.keyboard.diagnostics

import android.content.Context
import androidx.core.content.edit
import java.io.File

/** Diagnostic build: a single process-wide recorder in app-private storage. */
object InputTraceProvider {
    private const val PREFS = "urik_diagnostics"
    private const val KEY_SINCE = "recording_since_wall_ms"
    private const val DIR = "trace"

    @Volatile private var instance: FileInputTrace? = null

    fun get(context: Context): InputTrace = file(context)

    fun file(context: Context): FileInputTrace = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val store =
                object : TraceStateStore {
                    override var recordingSinceWallMs: Long
                        get() = prefs.getLong(KEY_SINCE, 0L)
                        set(value) {
                            prefs.edit { putLong(KEY_SINCE, value) }
                        }
                }
            FileInputTrace(File(app.filesDir, DIR), store).also { instance = it }
        }
    }

    /** Settings activity for the recorder, or null when the build has none. */
    val settingsActivity: Class<*>? = DiagnosticsActivity::class.java
}

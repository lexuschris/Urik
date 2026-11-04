package com.urik.keyboard.diagnostics

import android.view.inputmethod.InputConnection

/**
 * Local, opt-in typing recorder used to diagnose keyboard behaviour.
 *
 * Only the `diagnostic` build type contains a real implementation. Release and debug
 * builds get [NoOpInputTrace] from their own source set, so the recorder code is not
 * compiled into those APKs at all.
 */
interface InputTrace {
    /** True only while recording is switched on and the current field is not secure. */
    val enabled: Boolean

    /** True while the user has recording switched on, regardless of the current field. */
    val recording: Boolean

    fun record(type: String, fields: Map<String, Any?>)

    /** Called at the start of every input field; secure fields suppress all events. */
    fun startField(packageName: String?, fields: Map<String, Any?>, secure: Boolean)

    /** User-placed marker ("something just went wrong"). */
    fun marker()

    /** Wraps the editor connection so every edit sent to the app is recorded. */
    fun wrap(ic: InputConnection?): InputConnection?
}

inline fun InputTrace.event(type: String, fields: () -> Map<String, Any?>) {
    if (enabled) record(type, fields())
}

object NoOpInputTrace : InputTrace {
    override val enabled: Boolean get() = false
    override val recording: Boolean get() = false

    override fun record(type: String, fields: Map<String, Any?>) = Unit

    override fun startField(packageName: String?, fields: Map<String, Any?>, secure: Boolean) = Unit

    override fun marker() = Unit

    override fun wrap(ic: InputConnection?): InputConnection? = ic
}

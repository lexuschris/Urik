package com.urik.keyboard.diagnostics

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Diagnostic build only: switch the typing recorder on/off, export or wipe the recording.
 * Strings are deliberately not localised; this screen never ships in a release build.
 */
@Suppress("MagicNumber")
class DiagnosticsActivity : AppCompatActivity() {
    private val trace by lazy { InputTraceProvider.file(this) }

    private lateinit var toggle: SwitchCompat
    private lateinit var status: TextView

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
            if (uri != null) export(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Diagnostics"
        val pad = (16 * resources.displayMetrics.density).toInt()

        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

        column.addView(
            TextView(this).apply {
                text =
                    "Records keys, suggestions, autocorrect decisions, cursor events and every edit sent " +
                    "to the app, with the 20 characters either side of the cursor. Stored only on this " +
                    "phone. Password and other secure fields are never recorded. Recording switches " +
                    "itself off after 4 hours. Switching it off deletes the recording.\n\n" +
                    "While recording, tap the flag in the suggestion bar the moment something goes wrong."
            }
        )

        toggle =
            SwitchCompat(this).apply {
                text = "Record typing"
                setPadding(0, pad, 0, pad)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        trace.start()
                    } else if (trace.recording) {
                        trace.stopAndWipe()
                    }
                    refresh()
                }
            }
        column.addView(toggle)

        status = TextView(this)
        column.addView(status)

        column.addView(
            Button(this).apply {
                text = "Export recording"
                setOnClickListener {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    exportLauncher.launch("urik_trace_$stamp.jsonl")
                }
            }
        )
        column.addView(
            Button(this).apply {
                text = "Wipe recording"
                setOnClickListener {
                    trace.wipe()
                    refresh()
                    Toast.makeText(this@DiagnosticsActivity, "Recording wiped", Toast.LENGTH_SHORT).show()
                }
            }
        )

        setContentView(
            ScrollView(this).apply {
                addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                fitsSystemWindows = true
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val recording = trace.recording
        if (toggle.isChecked != recording) toggle.isChecked = recording
        val size = trace.files.sumOf { it.length() }
        val remaining = TimeUnit.MILLISECONDS.toMinutes(trace.remainingMs)
        status.text =
            buildString {
                append(if (recording) "Recording: ${remaining / 60}h ${remaining % 60}m left" else "Not recording")
                append("\nRecorded: ${size / 1024} KB")
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun export(uri: Uri) {
        try {
            trace.flush()
            contentResolver.openOutputStream(uri)?.use { out ->
                trace.files.forEach { file -> file.inputStream().use { it.copyTo(out) } }
            }
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

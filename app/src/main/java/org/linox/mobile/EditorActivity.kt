package org.linox.mobile

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import java.io.File

/**
 * Native editor opened by the Linux `nano FILE` compatibility command.
 * v0.9 adds lightweight regex-based syntax highlighting (see SyntaxHighlighter)
 * driven off the file extension, debounced so retyping doesn't lag on longer files.
 */
class EditorActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var highlightPending: Runnable? = null
    private var applyingHighlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent.getStringExtra("linox_file") ?: ""
        val file = File(requested)
        val lang = SyntaxHighlighter.langForFile(file.name)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
        val title = TextView(this).apply {
            text = "LinOx nano  •  ${file.name.ifEmpty { "untitled" }}" + if (lang != SyntaxHighlighter.Lang.NONE) "  •  ${lang.name.lowercase()}" else ""
            textSize = 18f; setTextColor(0xffb95cff.toInt()); setPadding(8, 8, 8, 16)
        }
        val editor = EditText(this).apply {
            setText(if (file.isFile) runCatching { file.readText() }.getOrDefault("") else "")
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            textSize = 15f; setTextColor(0xfff1eaf7.toInt()); setBackgroundColor(0xff111116.toInt())
            isSingleLine = false
        }

        if (lang != SyntaxHighlighter.Lang.NONE) {
            editor.text?.let { SyntaxHighlighter.highlight(it, lang) }
            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (applyingHighlight || s == null) return
                    highlightPending?.let { mainHandler.removeCallbacks(it) }
                    val task = Runnable {
                        applyingHighlight = true
                        try {
                            SyntaxHighlighter.clear(s)
                            SyntaxHighlighter.highlight(s, lang)
                        } finally { applyingHighlight = false }
                    }
                    highlightPending = task
                    mainHandler.postDelayed(task, 180)
                }
            })
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val save = Button(this).apply { text = "Save" }
        val close = Button(this).apply { text = "Exit" }
        save.setOnClickListener {
            try { file.parentFile?.mkdirs(); file.writeText(editor.text.toString()); Toast.makeText(this, "Saved ${file.name}", Toast.LENGTH_SHORT).show() }
            catch (e: Exception) { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        close.setOnClickListener { finish() }
        buttons.addView(save); buttons.addView(close)
        root.addView(title); root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(buttons)
        setContentView(root)
    }

    override fun onDestroy() {
        highlightPending?.let { mainHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}

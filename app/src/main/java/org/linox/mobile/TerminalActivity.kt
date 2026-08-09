package org.linox.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.linox.mobile.terminal.TerminalView

/** LinOx terminal UI. Keeps the existing PTY/runtime path intact. */
class TerminalActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var terminal: TerminalView
    private lateinit var input: EditText
    private lateinit var setupBanner: LinearLayout
    private lateinit var setupTitle: TextView
    private lateinit var setupText: TextView
    private var pty: PtySession? = null
    private val history = ArrayList<String>()
    private var historyIndex = 0
    private var started = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        runtime = LinuxRuntime(this)
        buildUi()
        startPty()
    }

    override fun onResume() {
        super.onResume()
        val ready = runtime.isLinuxReady()
        setupBanner.visibility = if (ready) LinearLayout.GONE else LinearLayout.VISIBLE

        if (!started && ready) {
            startPty()
        } else if (!ready) {
            updateSetupMessage()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 15, 18))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        topBar.addView(TextView(this).apply {
            text = "🐧 LinOx"
            textSize = 18f
            setTextColor(0xffb95cff.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        topBar.addView(Button(this).apply {
            text = "☰ Menu"
            setOnClickListener {
                startActivity(android.content.Intent(this@TerminalActivity, MainActivity::class.java))
            }
        })
        root.addView(topBar)

        setupBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = GradientDrawable().apply {
                cornerRadius = 22f
                setColor(Color.rgb(30, 24, 40))
                setStroke(1, 0xff5f3d78.toInt())
            }
        }

        setupTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        setupText = TextView(this).apply {
            setTextColor(0xffc9c2d0.toInt())
            textSize = 13f
            setPadding(0, 6, 0, 12)
        }
        setupBanner.addView(setupTitle)
        setupBanner.addView(setupText)

        val setupBtn = Button(this).apply {
            text = "OPEN LINUX SETUP"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = (54 * resources.displayMetrics.density).toInt()
            setOnClickListener {
                startActivity(android.content.Intent(this@TerminalActivity, DistroActivity::class.java))
            }
        }
        setupBanner.addView(setupBtn, LinearLayout.LayoutParams(-1, -2))
        root.addView(
            setupBanner,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(10, 4, 10, 8) }
        )

        terminal = TerminalView(this)
        root.addView(terminal, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 10)
        }

        val prompt = TextView(this).apply {
            text = "root@linox:~#"
            setTextColor(0xffb95cff.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 0, 6, 0)
        }

        input = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xff77727d.toInt())
            hint = "command"
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 0, 12, 0)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(0xff191b20.toInt())
                setStroke(1, 0xff343840.toInt())
            }
            imeOptions = EditorInfo.IME_ACTION_SEND

            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    submitCommand()
                    true
                } else false
            }

            setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (key) {
                    KeyEvent.KEYCODE_ENTER -> { submitCommand(); true }
                    KeyEvent.KEYCODE_DEL -> { if (pty != null) sendText("\u007f"); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (pty != null) sendText("\u001b[A"); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (pty != null) sendText("\u001b[B"); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (pty != null) sendText("\u001b[D"); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pty != null) sendText("\u001b[C"); true }
                    else -> false
                }
            }
        }

        val send = Button(this).apply {
            text = "↵"
            textSize = 20f
            setOnClickListener { submitCommand() }
        }

        val ctrl = Button(this).apply {
            text = "Ctrl+C"
            setOnClickListener {
                pty?.interrupt() ?: terminal.feed("\r\n[LinOx] Shell is not running.\r\n")
            }
        }

        bar.addView(prompt)
        bar.addView(input, LinearLayout.LayoutParams(0, (50 * resources.displayMetrics.density).toInt(), 1f))
        bar.addView(send, LinearLayout.LayoutParams((52 * resources.displayMetrics.density).toInt(), (50 * resources.displayMetrics.density).toInt()))
        bar.addView(ctrl)
        root.addView(bar)

        setContentView(root)
        updateSetupMessage()
    }

    private fun updateSetupMessage() {
        if (runtime.isLinuxReady()) {
            setupTitle.text = "Linux is ready"
            setupText.text = "Starting the real ARM64 shell…"
            return
        }

        if (runtime.activeRootfs().resolve("bin/sh").isFile && !runtime.hasProot()) {
            setupTitle.text = "PRoot is missing"
            setupText.text = "Your Linux rootfs is installed, but the PRoot runtime is not. Open Linux Setup and select an ARM64 Android-compatible PRoot binary."
        } else {
            setupTitle.text = "Linux environment isn’t ready"
            setupText.text = "Install PRoot and an ARM64 Debian/Ubuntu userspace to start the real shell."
        }
    }

    private fun submitCommand() {
        val command = input.text.toString()

        if (command.isBlank()) {
            if (pty != null) sendText("\r")
            return
        }

        if (pty == null) {
            if (runtime.isLinuxReady()) {
                startPty()
            }
            if (pty == null) {
                updateSetupMessage()
                terminal.feed("\r\n[LinOx] Shell is not running. Open Linux Setup first.\r\n")
                return
            }
        }

        sendText(command + "\r")
        input.text.clear()
        input.requestFocus()
    }

    private fun startPty() {
        if (!runtime.isLinuxReady()) {
            updateSetupMessage()
            setupBanner.visibility = LinearLayout.VISIBLE
            return
        }

        try {
            pty?.close()
            pty = runtime.startInteractivePty { data ->
                runOnUiThread { terminal.feed(data) }
            }
            started = true
            setupBanner.visibility = LinearLayout.GONE

            terminal.postDelayed({
                pty?.let {
                    val (c, r) = terminal.dimensions()
                    it.resize(r, c)
                }
            }, 250)
        } catch (e: Exception) {
            pty = null
            started = false
            setupBanner.visibility = LinearLayout.VISIBLE
            terminal.feed("[LinOx] Failed to start shell: ${e.message}\r\n")
            updateSetupMessage()
        }

        input.requestFocus()
    }

    private fun sendText(s: String) {
        if (s.isEmpty()) return
        val session = pty ?: return
        session.write(s)

        if (s.trim().isNotEmpty() && !s.contains("\u001b")) {
            history.add(s.trim())
            historyIndex = history.size
        }
    }

    override fun onDestroy() {
        pty?.close()
        pty = null
        started = false
        super.onDestroy()
    }
}

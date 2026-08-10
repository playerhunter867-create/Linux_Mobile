package org.linox.mobile

import android.os.Bundle
import android.text.InputType
import android.widget.*
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var runtime: LinuxRuntime

    private val prootPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) installProot(uri)
    }

    private val rootfsPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) installRootfs(uri)
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            WorkspaceManager(this, runtime).rememberFolder(uri)
            startActivity(
                android.content.Intent(this, WorkspaceActivity::class.java)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        runtime = LinuxRuntime(this).also { it.installLayout() }
        content = findViewById(R.id.content)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btnTerminal).setOnClickListener {
            terminal()
        }
        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            folderPicker.launch(null)
        }
        findViewById<Button>(R.id.btnFiles).setOnLongClickListener {
            startActivity(
                android.content.Intent(this, WorkspaceActivity::class.java)
            )
            true
        }
        findViewById<Button>(R.id.btnCode).setOnClickListener { code() }
        findViewById<Button>(R.id.btnLinux).setOnClickListener {
            startActivity(android.content.Intent(this, DistroActivity::class.java))
        }
        findViewById<Button>(R.id.btnPackages).setOnClickListener {
            startActivity(
                android.content.Intent(this, PackageManagerActivity::class.java)
            )
        }
        findViewById<Button>(R.id.btnGit).setOnClickListener {
            startActivity(
                android.content.Intent(this, GitActivity::class.java)
            )
        }
        findViewById<Button>(R.id.btnAI).setOnClickListener { ai() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            settings()
        }
        findViewById<Button>(R.id.btnSettings).setOnLongClickListener {
            startActivity(
                android.content.Intent(this, DistroActivity::class.java)
            )
            true
        }

        desktop()
    }

    private fun textView(
        value: String,
        size: Float = 14f,
        padded: Boolean = false
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xffdce2ea.toInt())
            if (padded) {
                setPadding(24, 24, 24, 24)
            }
        }

    private fun updateStatus() {
        status.text = runtime.status()
    }

    private fun desktop() {
        content.removeAllViews()

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 24)
            setBackgroundColor(0xff090b0e.toInt())
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = rounded(0xff12161c.toInt(), 22)
        }
        hero.addView(textView("LinOx Mobile", 30f).apply {
            setTextColor(0xffffffff.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        hero.addView(textView("Linux userspace for Android • version 0.9", 14f).apply {
            setTextColor(0xffaeb6c3.toInt())
        })
        hero.addView(textView("\n${runtime.status()}", 15f).apply {
            setTextColor(if (runtime.isLinuxReady()) 0xff72f0ae.toInt() else 0xffffc56b.toInt())
        })
        layout.addView(hero)

        val setup = Button(this).apply {
            text = if (runtime.isLinuxReady()) "🐧 Manage installed Linux systems"
                   else "⚡ Install Ubuntu / Debian / other Linux"
            isAllCaps = false
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, DistroActivity::class.java))
            }
        }
        layout.addView(setup, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = 14
            bottomMargin = 8
        })

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(actionCard("Terminal", "Real interactive shell through PRoot") {
            terminal()
        })
        grid.addView(actionCard("Code", "Edit Python / Bash files and run them") {
            code()
        })
        grid.addView(actionCard("Files", "Sync a workspace with Android storage") {
            folderPicker.launch(null)
        })
        grid.addView(actionCard("Packages", "APT / APK / DNF / Pacman / Zypper") {
            startActivity(android.content.Intent(this, PackageManagerActivity::class.java))
        })
        grid.addView(actionCard("Git", "Version-control tools inside Linux") {
            startActivity(android.content.Intent(this, GitActivity::class.java))
        })
        grid.addView(actionCard("Settings & diagnostics", "Runtime, workspace and system tools") {
            settings()
        })
        layout.addView(grid)

        layout.addView(textView(
            "\nArchitecture\n" +
                "Android kernel + ARM64 PRoot + selectable Linux userspace.\n" +
                "Ubuntu/Debian are downloaded on demand from OCI registries; the APK does not fake a kernel.",
            12f
        ).apply { setTextColor(0xff7f8794.toInt()) })

        scroll.addView(layout)
        content.addView(scroll)
        updateStatus()
    }

    private fun actionCard(title: String, subtitle: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = rounded(0xff11151b.toInt(), 18)
            isClickable = true
            setOnClickListener { action() }

            addView(textView(title, 18f).apply {
                setTextColor(0xffffffff.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(textView(subtitle, 12f).apply {
                setTextColor(0xff9ca5b2.toInt())
                setPadding(0, 4, 0, 0)
            })

            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 8
            }
        }

    private fun rounded(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * resources.displayMetrics.density
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xff252b34.toInt())
        }

    private fun terminal() {
        startActivity(
            android.content.Intent(this, TerminalActivity::class.java)
        )
    }

    private fun legacyTerminal() {
        content.removeAllViews()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val out = textView(
            "LinOx Terminal\n\n${runtime.status()}\n\n",
            14f
        )

        val input = EditText(this).apply {
            hint = "ls -la"
            setSingleLine(true)
            setTextColor(0xffffffff.toInt())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val run = Button(this).apply { text = "Run" }
        val clear = Button(this).apply { text = "Clear" }

        fun execute() {
            val command = input.text.toString().trim()
            if (command.isEmpty()) return

            out.append("\n$ $command\n")

            Thread {
                val r = runtime.execute(command)

                runOnUiThread {
                    out.append(r.output)
                    if (!r.output.endsWith("\n")) out.append("\n")
                    out.append("[exit ${r.exitCode}]\n")
                    input.requestFocus()
                }
            }.start()

            input.text.clear()
        }

        run.setOnClickListener { execute() }

        clear.setOnClickListener {
            out.text = "LinOx Terminal\n\n${runtime.status()}\n\n"
        }

        input.setOnEditorActionListener { _, _, _ ->
            execute()
            true
        }

        row.addView(run)
        row.addView(clear)

        box.addView(
            out,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        box.addView(input)
        box.addView(row)
        content.addView(box)
    }

    private fun code() {
        content.removeAllViews()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        val path = EditText(this).apply {
            hint = "filename.py"
            setSingleLine(true)
            setTextColor(0xffffffff.toInt())
        }

        val editor = EditText(this).apply {
            hint = "Write Python, Bash, C..."
            gravity = android.view.Gravity.TOP
            setTextColor(0xffffffff.toInt())
            textSize = 14f
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val save = Button(this).apply { text = "Save" }
        val run = Button(this).apply { text = "Run" }

        fun file(): File {
            val n = path.text.toString().trim().ifEmpty { "main.py" }
            return File(filesDir, "workspace/$n")
        }

        save.setOnClickListener {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(editor.text.toString())
            Toast.makeText(
                this,
                "Saved: ${f.name}",
                Toast.LENGTH_SHORT
            ).show()
        }

        run.setOnClickListener {
            save.performClick()

            val f = file()

            val cmd = when {
                f.name.endsWith(".py") ->
                    "python3 '${f.absolutePath}'"
                f.name.endsWith(".sh") ->
                    "sh '${f.absolutePath}'"
                else ->
                    "echo 'No runner configured for ${f.name}'"
            }

            val r = runtime.execute(cmd)

            android.app.AlertDialog.Builder(this)
                .setTitle("LinOx output")
                .setMessage(r.output.ifEmpty { "(no output)" })
                .setPositiveButton("OK", null)
                .show()
        }

        buttons.addView(save)
        buttons.addView(run)

        box.addView(path)
        box.addView(
            editor,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        box.addView(buttons)

        content.addView(box)
    }

    private fun ai() {
        content.removeAllViews()
        content.addView(
            textView(
                "🤖 LinOx AI\n\n" +
                    "Provider settings are separate from the Linux runtime. " +
                    "Never hard-code API keys.",
                padded = true
            )
        )
    }

    private fun settings() {
        content.removeAllViews()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        box.addView(
            textView(
                "⚙ LinOx Settings\n\n" +
                    "Runtime: ${runtime.runtimePath().absolutePath}\n" +
                    "Status: ${runtime.status()}\n\n" +
                    "Step 1: choose a native ARM64 PRoot executable.\n" +
                    "Step 2: choose an ARM64 .tar.gz Linux rootfs (Debian/Ubuntu).\n\n" +
                    "The files are copied into LinOx's private storage.",
                padded = true
            )
        )

        val distros = Button(this).apply {
            text = "🐧 Linux Distributions"
            setOnClickListener {
                startActivity(
                    android.content.Intent(
                        this@MainActivity,
                        DistroActivity::class.java
                    )
                )
            }
        }

        val p = Button(this).apply {
            text = "Install PRoot (ARM64)"
        }

        val r = Button(this).apply {
            text = "Install Linux rootfs (.tar.gz)"
        }

        val check = Button(this).apply {
            text = "Test Linux"
        }

        p.setOnClickListener {
            prootPicker.launch(
                arrayOf(
                    "application/octet-stream",
                    "*/*"
                )
            )
        }

        r.setOnClickListener {
            rootfsPicker.launch(
                arrayOf(
                    "application/gzip",
                    "application/x-gzip",
                    "application/octet-stream",
                    "*/*"
                )
            )
        }

        check.setOnClickListener {
            Thread {
                val result = runtime.execute(
                    "uname -a; echo '---'; id; echo '---'; " +
                        "cat /etc/os-release | head -n 5"
                )

                runOnUiThread {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Linux test")
                        .setMessage(
                            result.output.ifEmpty { "(no output)" } +
                                "\n\nexit=${result.exitCode}"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.start()
        }

        box.addView(distros)
        box.addView(p)
        box.addView(r)
        box.addView(check)
        content.addView(box)
    }

    private fun installProot(uri: Uri) {
        try {
            runtime.installProot(uri)
            Toast.makeText(
                this,
                "PRoot installed",
                Toast.LENGTH_SHORT
            ).show()
            settings()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "PRoot error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun installRootfs(uri: Uri) {
        Toast.makeText(
            this,
            "Installing Linux rootfs…",
            Toast.LENGTH_SHORT
        ).show()

        Thread {
            try {
                runtime.installRootfsTarGz(uri) { message ->
                    runOnUiThread {
                        status.text = message
                    }
                }

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Linux rootfs installed",
                        Toast.LENGTH_LONG
                    ).show()
                    settings()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Rootfs error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    settings()
                }
            }
        }.start()
    }

}

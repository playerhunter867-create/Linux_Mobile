package org.linox.mobile

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * LinOx v0.9 package manager.
 *
 * Wraps `apt` inside the active Linux rootfs. Offers curated toolchain presets
 * (Python / C-C++ / Node.js / Git tools) as one-tap installs, plus a manual
 * package field for anything else in the distro's apt catalog.
 */
class PackageManagerActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var output: TextView
    private lateinit var packageField: EditText
    private lateinit var busyBar: ProgressBar
    private val executor = Executors.newSingleThreadExecutor()

    data class Preset(
        val title: String,
        val description: String,
        val packages: List<String>
    )

    companion object {
        val PRESETS = listOf(
            Preset(
                "🐍 Python dev",
                "python3, pip, venv",
                listOf("python3", "python3-pip", "python3-venv")
            ),
            Preset(
                "🛠 C / C++ dev",
                "build-essential, gdb, cmake",
                listOf("build-essential", "gdb", "cmake", "pkg-config")
            ),
            Preset(
                "🟩 Node.js dev",
                "nodejs + npm",
                listOf("nodejs", "npm")
            ),
            Preset(
                "🔧 Git & tools",
                "git, curl, wget, vim, unzip",
                listOf("git", "curl", "wget", "vim", "unzip")
            )
        )
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        runtime = LinuxRuntime(this)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.rgb(13, 15, 18))
        }

        root.addView(
            TextView(this).apply {
                text = "📦 LinOx Package Manager"
                textSize = 24f
                setTextColor(Color.WHITE)
                setPadding(8, 8, 8, 12)
            }
        )

        root.addView(
            TextView(this).apply {
                text = "Installs via apt inside the active Linux distribution."
                textSize = 13f
                setTextColor(Color.LTGRAY)
                setPadding(8, 0, 8, 12)
            }
        )

        val presetsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        PRESETS.forEach { preset ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 6, 8, 6)
            }

            val label = TextView(this).apply {
                text = "${preset.title}\n${preset.description}"
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val install = Button(this).apply {
                text = "Install"
                setOnClickListener {
                    runApt(
                        "install -y ${preset.packages.joinToString(" ")}",
                        "Installing ${preset.title}…"
                    )
                }
            }

            row.addView(label)
            row.addView(install)
            presetsBox.addView(row)
        }

        root.addView(presetsBox)

        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 12, 8, 4)
        }

        packageField = EditText(this).apply {
            hint = "package name, e.g. htop"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val install = Button(this).apply {
            text = "Install"
            setOnClickListener {
                manualPackage()?.let {
                    runApt("install -y $it", "Installing $it…")
                }
            }
        }

        val remove = Button(this).apply {
            text = "Remove"
            setOnClickListener {
                manualPackage()?.let {
                    runApt("remove -y $it", "Removing $it…")
                }
            }
        }

        manualRow.addView(packageField)
        manualRow.addView(install)
        manualRow.addView(remove)
        root.addView(manualRow)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 8)
        }

        val search = Button(this).apply {
            text = "Search apt-cache"
        }

        val update = Button(this).apply {
            text = "apt-get update"
        }

        search.setOnClickListener {
            manualPackage()?.let {
                runApt("", "Searching…", searchTerm = it)
            }
        }

        update.setOnClickListener {
            runApt(
                "update",
                "Refreshing package index…",
                updateOnly = true
            )
        }

        searchRow.addView(search)
        searchRow.addView(update)
        root.addView(searchRow)

        busyBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = ProgressBar.INVISIBLE
        }
        root.addView(busyBar)

        output = TextView(this).apply {
            setTextColor(Color.rgb(210, 220, 225))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
        }

        val scroll = ScrollView(this)
        scroll.addView(output, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val close = Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }

        root.addView(close)
        setContentView(root)
    }

    private fun manualPackage(): String? {
        val p = packageField.text.toString().trim()

        if (p.isEmpty()) {
            Toast.makeText(
                this,
                "Enter a package name first",
                Toast.LENGTH_SHORT
            ).show()
            return null
        }

        require(
            Regex("^[a-zA-Z0-9+._:-]+$").matches(p)
        ) { "Invalid package name" }

        return p
    }

    private fun runApt(
        aptArgs: String,
        statusMessage: String,
        updateOnly: Boolean = false,
        searchTerm: String? = null
    ) {
        if (!runtime.isLinuxReady()) {
            Toast.makeText(
                this,
                "Install a Linux distribution first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        output.text = statusMessage
        busyBar.visibility = ProgressBar.VISIBLE

        executor.execute {
            val cmd = when {
                searchTerm != null ->
                    "apt-cache search '${searchTerm.replace("'", "")}' | head -n 40"

                updateOnly ->
                    "apt-get update"

                else ->
                    "apt-get update >/dev/null 2>&1; " +
                        "DEBIAN_FRONTEND=noninteractive apt-get $aptArgs"
            }

            val result = runtime.execute(cmd, timeoutSeconds = 300)

            runOnUiThread {
                busyBar.visibility = ProgressBar.INVISIBLE
                output.text =
                    result.output.ifBlank { "(no output)" } +
                        "\n\n[exit ${result.exitCode}]"
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

package org.linox.mobile

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Universal package manager for the active Linux distribution. */
class PackageManagerActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var output: TextView
    private lateinit var packageField: EditText
    private lateinit var busyBar: ProgressBar
    private val executor = Executors.newSingleThreadExecutor()

    data class Preset(
        val title: String,
        val description: String,
        val apt: List<String>,
        val apk: List<String>,
        val dnf: List<String>,
        val zypper: List<String>,
        val pacman: List<String>
    )

    companion object {
        val PRESETS = listOf(
            Preset(
                "🐍 Python", "Python 3 + pip + venv",
                listOf("python3", "python3-pip", "python3-venv"),
                listOf("python3", "py3-pip"),
                listOf("python3", "python3-pip"),
                listOf("python3", "python3-pip"),
                listOf("python", "python-pip")
            ),
            Preset(
                "🛠 C / C++", "Compiler, debugger, CMake",
                listOf("build-essential", "gdb", "cmake", "pkg-config"),
                listOf("build-base", "gdb", "cmake", "pkgconf"),
                listOf("gcc", "gcc-c++", "make", "gdb", "cmake", "pkgconf-pkg-config"),
                listOf("gcc", "gcc-c++", "make", "gdb", "cmake", "pkg-config"),
                listOf("gcc", "make", "gdb", "cmake", "pkgconf")
            ),
            Preset(
                "🟩 Node.js", "Node.js + npm",
                listOf("nodejs", "npm"),
                listOf("nodejs", "npm"),
                listOf("nodejs", "npm"),
                listOf("nodejs", "npm"),
                listOf("nodejs", "npm")
            ),
            Preset(
                "🔧 Git / Web", "Git, curl, wget, editors",
                listOf("git", "curl", "wget", "vim", "unzip"),
                listOf("git", "curl", "wget", "vim", "unzip"),
                listOf("git", "curl", "wget", "vim-minimal", "unzip"),
                listOf("git", "curl", "wget", "vim", "unzip"),
                listOf("git", "curl", "wget", "vim", "unzip")
            ),
            Preset(
                "🌐 Network / SSH", "SSH, DNS and network tools",
                listOf("openssh-client", "dnsutils", "iproute2"),
                listOf("openssh-client", "bind-tools", "iproute2"),
                listOf("openssh-clients", "bind-utils", "iproute"),
                listOf("openssh-clients", "bind-utils", "iproute2"),
                listOf("openssh", "bind", "iproute2")
            ),
            Preset(
                "📦 Essentials", "bash, procps, jq, file, tree, less, rsync",
                listOf("bash", "procps", "jq", "file", "tree", "less", "rsync", "ca-certificates"),
                listOf("bash", "procps", "jq", "file", "tree", "less", "rsync", "ca-certificates"),
                listOf("bash", "procps-ng", "jq", "file", "tree", "less", "rsync", "ca-certificates"),
                listOf("bash", "procps", "jq", "file", "tree", "less", "rsync", "ca-certificates"),
                listOf("bash", "procps-ng", "jq", "file", "tree", "less", "rsync", "ca-certificates")
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

        root.addView(TextView(this).apply {
            text = "📦 LinOx Package Manager 1.0.0"
            textSize = 23f
            setTextColor(Color.WHITE)
            setPadding(8, 8, 8, 10)
        })

        root.addView(TextView(this).apply {
            text =
                "Automatically selects apt / apk / dnf / zypper / pacman " +
                "for the active Linux distribution."
            setTextColor(Color.LTGRAY)
        })

        root.addView(Button(this).apply {
            text = "INSTALL COMPLETE TOOLSET"
            setOnClickListener { installEverything() }
        })

        val presetsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        PRESETS.forEach { preset ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 5, 8, 5)
            }

            row.addView(TextView(this).apply {
                text = "${preset.title}\n${preset.description}"
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            row.addView(Button(this).apply {
                text = "Install"
                setOnClickListener { installPreset(preset) }
            })

            presetsBox.addView(row)
        }

        val scroll = ScrollView(this)
        scroll.addView(presetsBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 6, 8, 4)
        }

        packageField = EditText(this).apply {
            hint = "package name"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        manualRow.addView(packageField)
        manualRow.addView(Button(this).apply {
            text = "Install"
            setOnClickListener {
                packageName()?.let { runPackageManager(installCommand(it), "Installing $it…") }
            }
        })
        manualRow.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener {
                packageName()?.let { runPackageManager(removeCommand(it), "Removing $it…") }
            }
        })
        root.addView(manualRow)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        controls.addView(Button(this).apply {
            text = "Update"
            setOnClickListener { runPackageManager(updateCommand(), "Refreshing repositories…") }
        })
        controls.addView(Button(this).apply {
            text = "Network test"
            setOnClickListener {
                runPackageManager(
                    "getent hosts registry-1.docker.io || nslookup registry-1.docker.io || ping -c 1 1.1.1.1",
                    "Testing network…"
                )
            }
        })
        root.addView(controls)

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
        val outScroll = ScrollView(this)
        outScroll.addView(output)
        root.addView(outScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })

        setContentView(root)
    }

    private fun installEverything() {
        if (!runtime.isLinuxReady()) {
            Toast.makeText(
                this,
                "Install and activate a Linux distribution first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val packages = when (detectManager()) {
            "apk" ->
                "bash ca-certificates curl wget git openssh-client bind-tools iproute2 " +
                "python3 py3-pip nodejs npm build-base gdb cmake pkgconf jq file tree less rsync"
            "dnf" ->
                "bash ca-certificates curl wget git openssh-clients bind-utils iproute " +
                "python3 python3-pip nodejs npm gcc gcc-c++ make gdb cmake pkgconf-pkg-config " +
                "jq file tree less rsync"
            "zypper" ->
                "bash ca-certificates curl wget git openssh-clients bind-utils iproute2 " +
                "python3 python3-pip nodejs npm gcc gcc-c++ make gdb cmake pkg-config " +
                "jq file tree less rsync"
            "pacman" ->
                "bash ca-certificates curl wget git openssh bind iproute2 " +
                "python python-pip nodejs npm gcc make gdb cmake pkgconf jq file tree less rsync"
            else ->
                "bash ca-certificates curl wget git openssh-client dnsutils iproute2 " +
                "python3 python3-pip python3-venv nodejs npm build-essential gdb cmake " +
                "pkg-config jq file tree less rsync"
        }

        runPackageManager(
            updateCommand() + " && " + installCommand(packages),
            "Installing the complete LinOx toolset…"
        )
    }

    private fun installPreset(preset: Preset) {
        val list = when (detectManager()) {
            "apk" -> preset.apk
            "dnf" -> preset.dnf
            "zypper" -> preset.zypper
            "pacman" -> preset.pacman
            else -> preset.apt
        }

        runPackageManager(
            updateCommand() + " && " + installCommand(list.joinToString(" ")),
            "Installing ${preset.title}…"
        )
    }

    private fun detectManager(): String {
        return when {
            runtime.execute("command -v apk").exitCode == 0 -> "apk"
            runtime.execute("command -v dnf").exitCode == 0 -> "dnf"
            runtime.execute("command -v zypper").exitCode == 0 -> "zypper"
            runtime.execute("command -v pacman").exitCode == 0 -> "pacman"
            runtime.execute("command -v apt-get").exitCode == 0 -> "apt"
            else -> "unknown"
        }
    }

    private fun updateCommand(): String = when (detectManager()) {
        "apk" -> "apk update"
        "dnf" -> "dnf -y makecache"
        "zypper" -> "zypper --non-interactive refresh"
        "pacman" -> "pacman -Sy --noconfirm"
        "apt" -> "apt-get update"
        else -> "echo 'No supported package manager found'"
    }

    private fun installCommand(packages: String): String = when (detectManager()) {
        "apk" -> "apk add --no-cache $packages"
        "dnf" -> "dnf install -y $packages"
        "zypper" -> "zypper --non-interactive install -y $packages"
        "pacman" -> "pacman -S --noconfirm $packages"
        "apt" -> "DEBIAN_FRONTEND=noninteractive apt-get install -y $packages"
        else -> "echo 'No supported package manager found'"
    }

    private fun removeCommand(packageName: String): String = when (detectManager()) {
        "apk" -> "apk del $packageName"
        "dnf" -> "dnf remove -y $packageName"
        "zypper" -> "zypper --non-interactive remove -y $packageName"
        "pacman" -> "pacman -R --noconfirm $packageName"
        "apt" -> "DEBIAN_FRONTEND=noninteractive apt-get remove -y $packageName"
        else -> "echo 'No supported package manager found'"
    }

    private fun packageName(): String? {
        val p = packageField.text.toString().trim()
        if (p.isEmpty()) {
            Toast.makeText(this, "Enter a package name", Toast.LENGTH_SHORT).show()
            return null
        }
        if (!Regex("^[a-zA-Z0-9+._:@/-]+$").matches(p)) {
            Toast.makeText(this, "Invalid package name", Toast.LENGTH_SHORT).show()
            return null
        }
        return p
    }

    private fun runPackageManager(command: String, message: String) {
        if (!runtime.isLinuxReady()) {
            Toast.makeText(this, "Install and activate a Linux distribution first", Toast.LENGTH_LONG).show()
            return
        }

        output.text = message
        busyBar.visibility = ProgressBar.VISIBLE

        executor.execute {
            val result = runtime.execute(command, timeoutSeconds = 600)
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

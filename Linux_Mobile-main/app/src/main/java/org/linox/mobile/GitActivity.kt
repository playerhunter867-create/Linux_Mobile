package org.linox.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * LinOx v0.9 Git UI. Runs plain `git` inside the active Linux rootfs, scoped to
 * the shared workspace directory so it lines up with WorkspaceManager sync
 * and the Code editor.
 */
class GitActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var repoField: EditText
    private lateinit var messageField: EditText
    private lateinit var cloneUrlField: EditText
    private lateinit var output: TextView
    private lateinit var busyBar: ProgressBar
    private val executor = Executors.newSingleThreadExecutor()

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
                text = "🌿 LinOx Git"
                textSize = 24f
                setTextColor(Color.WHITE)
                setPadding(8, 8, 8, 12)
            }
        )

        repoField = EditText(this).apply {
            hint = "repo path, e.g. workspace/myproject"
            setText("workspace")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        root.addView(
            TextView(this).apply {
                text = "Repository (relative to /root)"
                setTextColor(Color.LTGRAY)
                textSize = 12f
            }
        )
        root.addView(repoField)

        val cloneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        cloneUrlField = EditText(this).apply {
            hint = "https://github.com/user/repo.git"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val clone = Button(this).apply {
            text = "Clone"
            setOnClickListener { clone() }
        }

        cloneRow.addView(cloneUrlField)
        cloneRow.addView(clone)
        root.addView(cloneRow)

        val actionsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }

        listOf(
            "Status" to "git status",
            "Add all" to "git add -A",
            "Pull" to "git pull",
            "Push" to "git push"
        ).forEach { (label, cmd) ->
            actionsGrid.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { run(cmd) }
                }
            )
        }

        root.addView(actionsGrid)

        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        logRow.addView(
            Button(this).apply {
                text = "Log"
                setOnClickListener {
                    run("git log --oneline --graph --decorate -n 30")
                }
            }
        )

        logRow.addView(
            Button(this).apply {
                text = "Diff"
                setOnClickListener { run("git diff") }
            }
        )

        logRow.addView(
            Button(this).apply {
                text = "Branch"
                setOnClickListener { run("git branch -vv") }
            }
        )

        root.addView(logRow)

        messageField = EditText(this).apply {
            hint = "commit message"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        root.addView(messageField)

        val commit = Button(this).apply {
            text = "Commit"
            setOnClickListener {
                val msg = messageField.text.toString().trim()

                if (msg.isEmpty()) {
                    Toast.makeText(
                        this@GitActivity,
                        "Enter a commit message",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                run("git add -A && git commit -m " + shellQuote(msg))
            }
        }

        root.addView(commit)

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

    /** Single-quotes a value safely for a POSIX shell (used for free-text like commit messages). */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun repoDir(): String {
        val raw = repoField.text.toString().trim().ifEmpty { "workspace" }
        require(!raw.contains("..")) { "Invalid repository path" }
        return raw.trimStart('/')
    }

    private fun clone() {
        val url = cloneUrlField.text.toString().trim()

        if (url.isEmpty()) {
            Toast.makeText(
                this,
                "Enter a repository URL",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        require(
            Regex("^(https?|git)://\\S+$").matches(url)
        ) { "Enter a valid https:// or git:// URL" }

        runInLinux(
            "cd /root && mkdir -p ${shellQuote(repoDir().substringBeforeLast('/', ""))} " +
                "2>/dev/null; git clone ${shellQuote(url)} ${shellQuote(repoDir())}"
        )
    }

    private fun run(gitCommand: String) {
        runInLinux(
            "cd /root/${repoDir()} 2>/dev/null && $gitCommand || " +
                "echo '[LinOx] Repository not found at /root/${repoDir()}. " +
                "Clone or check the path above.'"
        )
    }

    private fun runInLinux(command: String) {
        if (!runtime.isLinuxReady()) {
            Toast.makeText(
                this,
                "Install a Linux distribution first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        busyBar.visibility = ProgressBar.VISIBLE

        executor.execute {
            val result = runtime.execute(command, timeoutSeconds = 120)

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

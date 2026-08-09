package org.linox.mobile

import android.content.Intent
import android.graphics.Color
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
import java.io.File
import java.util.concurrent.Executors

/**
 * LinOx Linux distribution manager.
 *
 * This version intentionally uses the Android Activity Result API instead of
 * the deprecated onActivityResult API. It is compatible with the current
 * DistroManager data model (including packageManager).
 */
class DistroActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var manager: DistroManager
    private lateinit var list: LinearLayout
    private lateinit var progress: TextView
    private lateinit var prootStatus: TextView
    private lateinit var prootButton: Button

    private val executor = Executors.newSingleThreadExecutor()

    private val prootPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) installProot(uri)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        runtime = LinuxRuntime(this)
        manager = DistroManager(this, runtime)
        buildUi()
        render()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.rgb(13, 15, 18))
        }

        root.addView(TextView(this).apply {
            text = "🐧 LinOx Linux 1.0.0"
            textSize = 25f
            setTextColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        })

        root.addView(TextView(this).apply {
            text =
                "ARM64 Linux userspaces via PRoot.\n" +
                    "Ubuntu, Debian, Alpine, Fedora, openSUSE, Rocky, Arch ARM and Kali."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(8, 0, 8, 8)
        })

        prootStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(0xffb95cff.toInt())
            setPadding(8, 4, 8, 4)
        }
        root.addView(prootStatus)

        prootButton = Button(this).apply {
            text = "SELECT PRoot (ARM64)"
            setOnClickListener {
                prootPicker.launch(
                    arrayOf("application/octet-stream", "application/x-executable", "*/*")
                )
            }
        }
        root.addView(prootButton, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(8, 4, 8, 8)
        })

        root.addView(Button(this).apply {
            text = "OPEN TERMINAL"
            setOnClickListener {
                startActivity(Intent(this@DistroActivity, TerminalActivity::class.java))
            }
        })

        root.addView(Button(this).apply {
            text = "PACKAGE MANAGER / TOOLS"
            setOnClickListener {
                if (!runtime.isLinuxReady()) {
                    Toast.makeText(
                        this@DistroActivity,
                        "Install PRoot and activate a Linux distribution first",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    startActivity(Intent(this@DistroActivity, PackageManagerActivity::class.java))
                }
            }
        })

        progress = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(190, 160, 220))
            setPadding(8, 6, 8, 8)
        }
        root.addView(progress)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this)
        scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })

        setContentView(root)
    }

    private fun installProot(uri: Uri) {
        progress.text = "Installing and validating PRoot…"
        executor.execute {
            try {
                runtime.installProot(uri)
                runOnUiThread {
                    progress.text = "PRoot installed and validated"
                    Toast.makeText(
                        this,
                        "PRoot ARM64 installed",
                        Toast.LENGTH_LONG
                    ).show()
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.text = "PRoot error: ${e.message}"
                    Toast.makeText(
                        this,
                        "PRoot error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    render()
                }
            }
        }
    }

    private fun render() {
        list.removeAllViews()

        val hasProot = runtime.hasProot()
        prootStatus.text = if (hasProot) {
            "✓ PRoot: installed and executable"
        } else {
            "⚠ PRoot: missing — select an ARM64 PRoot binary"
        }
        prootButton.text = if (hasProot) {
            "REPLACE PRoot (ARM64)"
        } else {
            "SELECT PRoot (ARM64)"
        }

        DistroManager.CATALOG.forEach { distro ->
            val installed = manager.isInstalled(distro)
            val distroRoot = File(filesDir, "linox-distros/${distro.id}/rootfs")
            val active = runCatching {
                runtime.activeRootfs().canonicalPath == distroRoot.canonicalPath
            }.getOrDefault(false)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
            }

            card.addView(TextView(this).apply {
                text = buildString {
                    append(distro.title)
                    append('\n')
                    append(distro.description)
                    append('\nImage: ')
                    append(distro.image)
                    append("\nPackage manager: ")
                    append(distro.packageManager)
                    if (active) append("\n✓ ACTIVE")
                }
                textSize = 15f
                setTextColor(Color.WHITE)
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(Button(this).apply {
                text = if (installed) "Reinstall" else "Download"
                setOnClickListener { install(distro) }
            })

            row.addView(Button(this).apply {
                text = "Use"
                isEnabled = installed
                setOnClickListener {
                    try {
                        manager.activate(distro)
                        progress.text = "Active: ${distro.title}"
                        render()
                    } catch (e: Exception) {
                        progress.text = "Activation failed: ${e.message}"
                    }
                }
            })

            row.addView(Button(this).apply {
                text = "Remove"
                isEnabled = installed
                setOnClickListener {
                    manager.remove(distro)
                    progress.text = "Removed ${distro.title}"
                    render()
                }
            })

            card.addView(row)
            list.addView(card)
        }

        progress.text = "Active rootfs: ${runtime.activeRootfs().absolutePath}"
    }

    private fun install(distro: DistroManager.Distro) {
        if (!runtime.hasProot()) {
            Toast.makeText(
                this,
                "Select an ARM64 PRoot first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        progress.text = "Starting ${distro.title}…"
        executor.execute {
            try {
                manager.install(distro) { p ->
                    runOnUiThread {
                        progress.text = if (p.total > 0) {
                            "${p.message} ${p.downloaded}/${p.total} bytes"
                        } else {
                            p.message
                        }
                    }
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "${distro.title} installed and active",
                        Toast.LENGTH_LONG
                    ).show()
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.text = "Install failed: ${e.message}"
                    Toast.makeText(
                        this,
                        "Install failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

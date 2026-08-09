package org.linox.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class DistroActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var manager: DistroManager
    private lateinit var list: LinearLayout
    private lateinit var progress: TextView
    private lateinit var prootStatus: TextView
    private lateinit var prootButton: Button
    private val executor = Executors.newSingleThreadExecutor()

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
            text = "Rootless Linux for Android via selectable ARM64 PRoot.\n" +
                "Downloads public ARM64 Linux images over HTTPS and keeps them persistent."
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
            setOnClickListener { chooseProot() }
        }
        root.addView(prootButton, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(8, 4, 8, 8)
        })

        root.addView(Button(this).apply {
            text = "⚡ INSTALL UBUNTU 24.04 LTS (RECOMMENDED)"
            setOnClickListener {
                install(DistroManager.CATALOG.first { it.id == "ubuntu2404" })
            }
        })

        root.addView(Button(this).apply {
            text = "⬇ DOWNLOAD ALL DISTROS"
            setOnClickListener { installAll() }
        })

        root.addView(Button(this).apply {
            text = "OPEN TERMINAL"
            setOnClickListener {
                startActivity(Intent(this@DistroActivity, TerminalActivity::class.java))
            }
        })

        progress = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(190, 160, 220))
            setPadding(8, 6, 8, 8)
        }
        root.addView(progress)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })

        setContentView(root)
    }

    private fun chooseProot() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }
        startActivityForResult(intent, REQ_PROOT)
    }

    @Deprecated("Activity Result API retained for compatibility with the existing project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROOT || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return

        executor.execute {
            try {
                runtime.installProot(uri)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "PRoot ARM64 installed and validated",
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
                }
            }
        }
    }

    private fun hasUsableProot(): Boolean = runtime.hasProot()

    private fun render() {
        list.removeAllViews()

        val prootReady = runtime.hasProot()
        prootStatus.text = if (prootReady) {
            "✓ PRoot: installed and executable"
        } else {
            "⚠ PRoot: missing — select an ARM64 PRoot executable"
        }

        prootButton.text = if (prootReady) {
            "REPLACE PRoot (ARM64)"
        } else {
            "SELECT PRoot (ARM64)"
        }

        DistroManager.CATALOG.forEach { distro ->
            val installed = manager.isInstalled(distro)
            val distroRoot = java.io.File(
                filesDir,
                "linox-distros/${distro.id}/rootfs"
            )
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
                    append('\n')
                    append("Image: ${distro.image}")
                    append('\n')
                    append("Package manager: ${distro.packageManager}")
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

    private fun installAll() {
        if (!hasUsableProot()) {
            Toast.makeText(this, "PRoot is not ready", Toast.LENGTH_LONG).show()
            return
        }
        progress.text = "Starting download of all Linux distributions…"
        executor.execute {
            for (distro in DistroManager.CATALOG) {
                try {
                    manager.install(distro) { p ->
                        runOnUiThread {
                            progress.text = "${distro.title}: ${p.message}"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        progress.text = "${distro.title} failed: ${e.message}"
                    }
                }
            }
            runOnUiThread {
                Toast.makeText(this, "All available distros processed", Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun install(distro: DistroManager.Distro) {
        if (!hasUsableProot()) {
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

    companion object {
        private const val REQ_PROOT = 4107
    }
}

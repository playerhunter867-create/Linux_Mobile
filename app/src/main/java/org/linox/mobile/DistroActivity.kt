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

/** LinOx Linux distribution manager: installs ARM64 rootfs and the PRoot runtime dependency. */
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
            text = "🐧 LinOx Linux"
            textSize = 25f
            setTextColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        })

        root.addView(TextView(this).apply {
            text = "PRoot + ARM64 Linux userspace. Android stays the host kernel."
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
            text = "SELECT PRoot BINARY"
            textSize = 15f
            setOnClickListener { chooseProot() }
        }
        root.addView(
            prootButton,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(8, 4, 8, 8)
            }
        )

        progress = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(190, 160, 220))
            setPadding(8, 6, 8, 8)
        }
        root.addView(progress)

        val allTools = Button(this).apply {
            text = "INSTALL ALL TOOLS"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = (58 * resources.displayMetrics.density).toInt()
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
        }
        root.addView(
            allTools,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(8, 4, 8, 10)
            }
        )

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

    private fun chooseProot() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }
        startActivityForResult(intent, REQ_PROOT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROOT || resultCode != Activity.RESULT_OK) return

        val uri: Uri = data?.data ?: return
        executor.execute {
            try {
                runtime.installProot(uri)
                runOnUiThread {
                    Toast.makeText(this, "PRoot installed", Toast.LENGTH_LONG).show()
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.text = "PRoot install failed: ${e.message}"
                    Toast.makeText(
                        this,
                        "PRoot install failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun render() {
        list.removeAllViews()

        val prootInstalled = runtime.hasProot()
        prootStatus.text = if (prootInstalled) {
            "✓ PRoot: installed"
        } else {
            "⚠ PRoot: missing — select an ARM64 Android-compatible PRoot binary"
        }

        prootButton.text =
            if (prootInstalled) "REPLACE PRoot BINARY" else "SELECT PRoot BINARY"

        DistroManager.CATALOG.forEach { distro ->
            val installed = manager.isInstalled(distro)
            val active = runtime.activeRootfs().canonicalPath ==
                java.io.File(filesDir, "linox-distros/${distro.id}/rootfs").canonicalPath

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
            }

            card.addView(TextView(this).apply {
                text = "${distro.title}\n${distro.description}\nImage: ${distro.image}" +
                    if (active) "\n✓ ACTIVE" else ""
                textSize = 16f
                setTextColor(Color.WHITE)
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(Button(this).apply {
                text = if (installed) "Reinstall" else "Install"
                setOnClickListener { install(distro) }
            })

            row.addView(Button(this).apply {
                text = "Use"
                isEnabled = installed
                setOnClickListener {
                    try {
                        manager.activate(distro)
                        progress.text = "Active: ${distro.title}"
                        Toast.makeText(
                            this@DistroActivity,
                            "${distro.title} is active",
                            Toast.LENGTH_SHORT
                        ).show()
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
        progress.text = "Starting ${distro.title}…"
        executor.execute {
            try {
                manager.install(distro) { p ->
                    runOnUiThread {
                        progress.text =
                            if (p.total > 0) "${p.message} ${p.downloaded}/${p.total} bytes"
                            else p.message
                    }
                }

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "${distro.title} installed and activated",
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

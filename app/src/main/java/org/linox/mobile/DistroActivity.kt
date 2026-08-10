package org.linox.mobile

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Professional Linux/OS manager for LinOx Mobile 0.9. */
class DistroActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var manager: DistroManager
    private lateinit var list: LinearLayout
    private lateinit var progress: TextView
    private lateinit var prootStatus: TextView
    private val executor = Executors.newSingleThreadExecutor()

    private val prootPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runBackground {
            runtime.installProot(uri)
            ui { toast("PRoot ARM64 installed and validated"); render() }
        }
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
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(9, 11, 14))
        }

        root.addView(text("Linux Mobile", 30f, Color.WHITE, true))
        root.addView(text("OS Manager • LinOx 0.9", 14f, Color.rgb(157, 163, 173)))

        prootStatus = text("", 14f, Color.WHITE, false).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panel()
        }
        root.addView(prootStatus, lp(1, 0).apply { setMargins(0, dp(12), 0, dp(8)) })

        val prootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prootRow.addView(button("PRoot / runtime") {
            prootPicker.launch(arrayOf("application/octet-stream", "application/x-executable", "*/*"))
        }, lp(1, 0))
        prootRow.addView(button("Open terminal") {
            startActivity(Intent(this@DistroActivity, TerminalActivity::class.java))
        }, lp(1, 0))
        root.addView(prootRow)

        progress = text("Ready.", 13f, Color.rgb(175, 181, 191))
        progress.setPadding(dp(4), dp(8), dp(4), dp(8))
        root.addView(progress)

        val heading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(text("Available Linux systems", 20f, Color.WHITE, true), lp(1, 0))
        heading.addView(text("ARM64", 12f, Color.rgb(120, 220, 170), true).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = rounded(Color.rgb(22, 55, 42), 20)
        })
        root.addView(heading)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bottom.addView(button("Install all") { installAll() }, lp(1, 0))
        bottom.addView(button("Back") { finish() }, lp(1, 0))
        root.addView(bottom)

        setContentView(root)
    }

    private fun render() {
        list.removeAllViews()

        val device = if (runtime.isArm64Device()) "✓ ARM64 Android" else "⚠ ${runtime.architecture()} — this build requires ARM64"
        prootStatus.text =
            "$device\n" +
            if (runtime.hasProot()) "✓ PRoot executable is ready" else "⚠ PRoot is not installed"

        DistroManager.CATALOG.forEach { distro ->
            val installed = manager.isInstalled(distro)
            val active = runCatching {
                runtime.activeRootfs().canonicalPath ==
                    java.io.File(filesDir, "linox-distros/${distro.id}/rootfs").canonicalPath
            }.getOrDefault(false)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = panel()
            }

            val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(text(distro.title, 18f, Color.WHITE, true), lp(1, 0))
            if (active) {
                titleRow.addView(text("ACTIVE", 11f, Color.rgb(120, 255, 190), true).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = rounded(Color.rgb(18, 66, 46), 20)
                })
            }
            card.addView(titleRow)
            card.addView(text(
                "${distro.description}\n${distro.image}  •  ${distro.packageManager}",
                13f, Color.rgb(170, 176, 187)
            ).apply { setPadding(0, dp(6), 0, dp(10)) })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button(if (installed) "Reinstall" else "Install") {
                install(distro)
            }, lp(1, 0))
            row.addView(button("Use") {
                runCatching {
                    manager.activate(distro)
                    progress.text = "Active OS: ${distro.title}"
                    render()
                }.onFailure { progress.text = "Activation failed: ${it.message}" }
            }.apply { isEnabled = installed }, lp(1, 0))
            row.addView(button("Remove") {
                manager.remove(distro)
                progress.text = "Removed ${distro.title}"
                render()
            }.apply { isEnabled = installed }, lp(1, 0))
            card.addView(row)

            list.addView(card, ViewGroup.LayoutParams(-1, -2))
            list.addView(Space(this), ViewGroup.LayoutParams(1, dp(8)))
        }

        val usedMb = runtime.storageUsedBytes() / (1024.0 * 1024.0)
        progress.text = "Storage used by Linux runtime: ${"%.1f".format(usedMb)} MB"
    }

    private fun installAll() {
        if (!runtime.hasProot()) {
            toast("PRoot is required")
            return
        }
        runBackground {
            DistroManager.CATALOG.forEach { distro ->
                runCatching {
                    manager.install(distro) { p ->
                        ui { progress.text = "${distro.title}: ${p.message}" }
                    }
                }.onFailure { e ->
                    ui { progress.text = "${distro.title}: ${e.message}" }
                }
            }
            ui { toast("Install-all finished"); render() }
        }
    }

    private fun install(distro: DistroManager.Distro) {
        if (!runtime.hasProot()) {
            toast("Install/choose a valid ARM64 PRoot first")
            return
        }
        if (!runtime.isArm64Device()) {
            toast("This release targets ARM64 Android")
            return
        }

        runBackground {
            runCatching {
                manager.install(distro) { p ->
                    ui {
                        progress.text = "${distro.title}: ${p.message}" +
                            if (p.total > 0) " ${percent(p.downloaded, p.total)}%" else ""
                    }
                }
                ui { toast("${distro.title} is ready"); render() }
            }.onFailure { e ->
                ui {
                    progress.text = "Install failed: ${e.message}"
                    toast("Install failed")
                }
            }
        }
    }

    private fun runBackground(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Throwable) {
                ui { progress.text = "Error: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun panel() = rounded(Color.rgb(18, 21, 27), 18)
    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Color.rgb(34, 39, 48))
        }

    private fun lp(weight: Int, width: Int) =
        LinearLayout.LayoutParams(if (width == 0) 0 else dp(width), -2, weight.toFloat()).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun percent(done: Long, total: Long): Int =
        ((done.toDouble() / total.toDouble()) * 100).roundToInt().coerceIn(0, 100)
}

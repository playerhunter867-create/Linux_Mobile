package org.linox.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** LinOx Linux distribution manager: pulls public OCI images and installs arm64 rootfs. */
class DistroActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var manager: DistroManager
    private lateinit var list: LinearLayout
    private lateinit var progress: TextView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        runtime = LinuxRuntime(this)
        manager = DistroManager(this, runtime)
        buildUi()
        render()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); setBackgroundColor(Color.rgb(13,15,18)) }
        root.addView(TextView(this).apply { text = "🐧 LinOx Linux Distributions"; textSize = 25f; setTextColor(Color.WHITE); setPadding(8,8,8,12) })
        root.addView(TextView(this).apply { text = "Install a real ARM64 Linux userspace. LinOx uses PRoot; Android's kernel remains the host."; textSize = 14f; setTextColor(Color.LTGRAY); setPadding(8,0,8,12) })
        progress = TextView(this).apply { textSize=13f; setTextColor(Color.rgb(190,160,220)); setPadding(8,8,8,8) }
        root.addView(progress)
        list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroll = ScrollView(this); scroll.addView(list, ViewGroup.LayoutParams(-1,-2)); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        val close = Button(this).apply { text="Back"; setOnClickListener{finish()} }
        root.addView(close)
        setContentView(root)
    }

    private fun render() {
        list.removeAllViews()
        DistroManager.CATALOG.forEach { distro ->
            val installed = manager.isInstalled(distro)
            val card = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,12,12,12) }
            card.addView(TextView(this).apply { text="${distro.title}\n${distro.description}\nImage: ${distro.image}"; textSize=16f; setTextColor(Color.WHITE) })
            val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            val install = Button(this).apply { text=if(installed) "Reinstall" else "Install"; setOnClickListener{install(distro)} }
            val activate = Button(this).apply { text="Use"; isEnabled=installed; setOnClickListener{ manager.activate(distro); progress.text="Active: ${distro.title}"; Toast.makeText(this@DistroActivity,"${distro.title} is active",Toast.LENGTH_SHORT).show() } }
            val remove = Button(this).apply { text="Remove"; isEnabled=installed; setOnClickListener{ manager.remove(distro); progress.text="Removed ${distro.title}"; render() } }
            row.addView(install); row.addView(activate); row.addView(remove); card.addView(row); list.addView(card)
        }
        progress.text = "Active rootfs: ${runtime.activeRootfs().absolutePath}"
    }

    private fun install(distro: DistroManager.Distro) {
        progress.text = "Starting ${distro.title}…"
        executor.execute {
            try {
                manager.install(distro) { p -> runOnUiThread { progress.text = if(p.total>0) "${p.message} ${p.downloaded}/${p.total} bytes" else p.message } }
                runOnUiThread { Toast.makeText(this,"${distro.title} installed",Toast.LENGTH_LONG).show(); render() }
            } catch (e: Exception) {
                runOnUiThread { progress.text="Install failed: ${e.message}"; Toast.makeText(this,"Install failed: ${e.message}",Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}

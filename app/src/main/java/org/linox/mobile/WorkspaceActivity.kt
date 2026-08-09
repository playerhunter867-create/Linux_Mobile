package org.linox.mobile

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * LinOx v0.9 project/workspace sync between Android storage and the Linux
 * userspace. Backed by WorkspaceManager; this activity is just the UI shell.
 */
class WorkspaceActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var manager: WorkspaceManager
    private lateinit var folderLabel: TextView
    private lateinit var output: TextView
    private lateinit var busyBar: ProgressBar
    private val executor = Executors.newSingleThreadExecutor()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { manager.rememberFolder(uri); refreshFolderLabel() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        runtime = LinuxRuntime(this)
        manager = WorkspaceManager(this, runtime)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); setBackgroundColor(Color.rgb(13, 15, 18)) }
        root.addView(TextView(this).apply { text = "🔄 LinOx Workspace Sync"; textSize = 24f; setTextColor(Color.WHITE); setPadding(8, 8, 8, 12) })
        root.addView(TextView(this).apply {
            text = "Linux side: /root/workspace  (also reachable from the terminal and Code editor)"
            textSize = 13f; setTextColor(Color.LTGRAY); setPadding(8, 0, 8, 12)
        })

        folderLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.rgb(190, 160, 220)); setPadding(8, 0, 8, 12) }
        root.addView(folderLabel)

        val pick = Button(this).apply { text = "Choose Android folder"; setOnClickListener { folderPicker.launch(null) } }
        root.addView(pick)

        val syncRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
        val pull = Button(this).apply { text = "⬇ Pull to Linux"; setOnClickListener { sync(pull = true) } }
        val push = Button(this).apply { text = "⬆ Push to Android"; setOnClickListener { sync(pull = false) } }
        syncRow.addView(pull); syncRow.addView(push)
        root.addView(syncRow)

        busyBar = ProgressBar(this).apply { isIndeterminate = true; visibility = ProgressBar.INVISIBLE }
        root.addView(busyBar)

        output = TextView(this).apply { textColor = Color.rgb(210, 220, 225); textSize = 13f; setPadding(8, 8, 8, 8) }
        val scroll = ScrollView(this)
        scroll.addView(output, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val close = Button(this).apply { text = "Back"; setOnClickListener { finish() } }
        root.addView(close)
        setContentView(root)
        refreshFolderLabel()
    }

    private fun refreshFolderLabel() {
        val uri = manager.savedFolderUri()
        folderLabel.text = if (uri != null) "Android folder: $uri" else "No Android folder chosen yet."
    }

    private fun sync(pull: Boolean) {
        val uri: Uri = manager.savedFolderUri() ?: run { Toast.makeText(this, "Choose an Android folder first", Toast.LENGTH_SHORT).show(); return }
        busyBar.visibility = ProgressBar.VISIBLE
        output.text = if (pull) "Pulling from Android…" else "Pushing to Android…"
        executor.execute {
            try {
                val result = if (pull) manager.pullFromAndroid(uri) { m -> runOnUiThread { output.text = m } }
                             else manager.pushToAndroid(uri) { m -> runOnUiThread { output.text = m } }
                runOnUiThread {
                    busyBar.visibility = ProgressBar.INVISIBLE
                    output.text = "Files copied: ${result.filesCopied}\nBytes: ${result.bytesCopied}" +
                        if (result.errors.isNotEmpty()) "\n\nErrors:\n${result.errors.joinToString("\n").take(2000)}" else ""
                }
            } catch (e: Exception) {
                runOnUiThread { busyBar.visibility = ProgressBar.INVISIBLE; output.text = "Sync failed: ${e.message}" }
            }
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}

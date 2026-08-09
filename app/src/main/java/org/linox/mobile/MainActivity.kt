package org.linox.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.*
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var runtime: LinuxRuntime

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateStatus() }

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
            startActivity(android.content.Intent(this, WorkspaceActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        runtime = LinuxRuntime(this).also { it.installLayout() }
        content = findViewById(R.id.content)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.btnTerminal).setOnClickListener { terminal() }
        findViewById<Button>(R.id.btnFiles).setOnClickListener { folderPicker.launch(null) }
        findViewById<Button>(R.id.btnFiles).setOnLongClickListener { startActivity(android.content.Intent(this, WorkspaceActivity::class.java)); true }
        findViewById<Button>(R.id.btnCode).setOnClickListener { code() }
        findViewById<Button>(R.id.btnPackages).setOnClickListener { startActivity(android.content.Intent(this, PackageManagerActivity::class.java)) }
        findViewById<Button>(R.id.btnGit).setOnClickListener { startActivity(android.content.Intent(this, GitActivity::class.java)) }
        findViewById<Button>(R.id.btnAI).setOnClickListener { ai() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { settings() }
        findViewById<Button>(R.id.btnSettings).setOnLongClickListener { startActivity(android.content.Intent(this, DistroActivity::class.java)); true }
        requestUsefulPermissions()
        desktop()
    }

    private fun requestUsefulPermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (wanted.isNotEmpty()) permissions.launch(wanted)
    }

    private fun updateStatus() { status.text = runtime.status() }

    private fun desktop() {
        content.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24)
        }
        val title = textView("🐧 LinOx Mobile",28f).apply { setTextColor(0xffb95cff.toInt()) }
        val info = textView("\nLinux development environment for Android\n\n${runtime.status()}\n\n" +
            "Terminal: real command execution\nCode: built-in editor + run (now with syntax highlighting)\n" +
            "Packages: apt + toolchain presets\nGit: status/commit/push/pull from the workspace\n" +
            "Files: long-press to sync a folder with /root/workspace\n\n" +
            "With PRoot + Debian/Ubuntu rootfs, commands execute inside Linux.")
        layout.addView(title); layout.addView(info); content.addView(layout); updateStatus()
    }

    private fun terminal() { startActivity(android.content.Intent(this, TerminalActivity::class.java)) }

    private fun legacyTerminal() {
        content.removeAllViews()
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        val out = textView("LinOx Terminal\n\n${runtime.status()}\n\n",14f)
        val input = EditText(this).apply { hint="ls -la"; singleLine=true; setTextColor(0xffffffff.toInt()); inputType=InputType.TYPE_CLASS_TEXT }
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val run = Button(this).apply { text="Run" }; val clear=Button(this).apply{text="Clear"}
        fun execute() {
            val command=input.text.toString().trim(); if(command.isEmpty()) return
            out.append("\n$ $command\n")
            Thread {
                val r=runtime.execute(command)
                runOnUiThread { out.append(r.output); if(!r.output.endsWith("\n")) out.append("\n"); out.append("[exit ${r.exitCode}]\n"); input.requestFocus() }
            }.start(); input.text.clear()
        }
        run.setOnClickListener{execute()}; clear.setOnClickListener{out.text="LinOx Terminal\n\n${runtime.status()}\n\n"}
        input.setOnEditorActionListener{_,_,_->execute();true}
        row.addView(run); row.addView(clear)
        box.addView(out,LinearLayout.LayoutParams(-1,0,1f)); box.addView(input); box.addView(row); content.addView(box)
    }

    private fun code() {
        content.removeAllViews()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(12,12,12,12)}
        val path=EditText(this).apply{hint="filename.py";singleLine=true;setTextColor(0xffffffff.toInt())}
        val editor=EditText(this).apply{hint="Write Python, Bash, C...";gravity=android.view.Gravity.TOP;setTextColor(0xffffffff.toInt());textSize=14f;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        val buttons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val save=Button(this).apply{text="Save"}; val run=Button(this).apply{text="Run"}
        fun file():File{val n=path.text.toString().trim().ifEmpty{"main.py"};return File(filesDir,"workspace/$n")}
        save.setOnClickListener{val f=file();f.parentFile?.mkdirs();f.writeText(editor.text.toString());Toast.makeText(this,"Saved: ${f.name}",Toast.LENGTH_SHORT).show()}
        run.setOnClickListener{
            save.performClick(); val f=file()
            val cmd=when{f.name.endsWith(".py")->"python3 '${f.absolutePath}'";f.name.endsWith(".sh")->"sh '${f.absolutePath}'";else->"echo 'No runner configured for ${f.name}'"}
            val r=runtime.execute(cmd)
            android.app.AlertDialog.Builder(this).setTitle("LinOx output").setMessage(r.output.ifEmpty{"(no output)"}).setPositiveButton("OK",null).show()
        }
        buttons.addView(save);buttons.addView(run);box.addView(path);box.addView(editor,LinearLayout.LayoutParams(-1,0,1f));box.addView(buttons);content.addView(box)
    }

    private fun ai(){content.removeAllViews();content.addView(textView("🤖 LinOx AI\n\nProvider settings are separate from the Linux runtime. Never hard-code API keys."))}
    private fun settings(){
        content.removeAllViews()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        box.addView(textView("⚙ LinOx Settings\n\nRuntime: ${runtime.runtimePath().absolutePath}\nStatus: ${runtime.status()}\n\nStep 1: choose a native ARM64 PRoot executable.\nStep 2: choose an ARM64 .tar.gz Linux rootfs (Debian/Ubuntu).\n\nThe files are copied into LinOx's private storage."))
        val distros = Button(this).apply { text = "🐧 Linux Distributions"; setOnClickListener { startActivity(android.content.Intent(this@MainActivity, DistroActivity::class.java)) } }
        val p = Button(this).apply { text = "Install PRoot (ARM64)" }
        val r = Button(this).apply { text = "Install Linux rootfs (.tar.gz)" }
        val check = Button(this).apply { text = "Test Linux" }
        p.setOnClickListener { prootPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        r.setOnClickListener { rootfsPicker.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*")) }
        check.setOnClickListener {
            Thread {
                val result = runtime.execute("uname -a; echo '---'; id; echo '---'; cat /etc/os-release | head -n 5")
                runOnUiThread {
                    android.app.AlertDialog.Builder(this).setTitle("Linux test")
                        .setMessage(result.output.ifEmpty { "(no output)" } + "\n\nexit=${result.exitCode}")
                        .setPositiveButton("OK", null).show()
                }
            }.start()
        }
        box.addView(distros); box.addView(p); box.addView(r); box.addView(check); content.addView(box)
    }

    private fun installProot(uri: Uri) {
        try {
            runtime.installProot(uri)
            Toast.makeText(this, "PRoot installed", Toast.LENGTH_SHORT).show()
            settings()
        } catch (e: Exception) {
            Toast.makeText(this, "PRoot error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installRootfs(uri: Uri) {
        Toast.makeText(this, "Installing Linux rootfs…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                runtime.installRootfsTarGz(uri) { message -> runOnUiThread { status.text = message } }
                runOnUiThread { Toast.makeText(this, "Linux rootfs installed", Toast.LENGTH_LONG).show(); settings() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Rootfs error: ${e.message}", Toast.LENGTH_LONG).show(); settings() }
            }
        }.start()
    }
    private fun textView(text:String,size:Float=16f)=TextView(this).apply{this.text=text;textSize=size;setTextColor(0xfff1eaf7.toInt());setPadding(24,24,24,24)}
}

package org.linox.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.linox.mobile.terminal.TerminalView

/**
 * LinOx v0.9 terminal — now the app's launcher screen, so opening LinOx drops
 * you straight into a working shell instead of a dashboard.
 *
 * PTY + dependency-free VT/ANSI renderer. The command field is deliberately kept
 * as a mobile keyboard bridge; special keys are mapped to bytes sent directly
 * to the PTY. A small top bar gives one tap access to the rest of LinOx
 * (packages, git, files, distros, settings) via MainActivity, without making
 * that dashboard the thing you have to pass through first.
 */
class TerminalActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var terminal: TerminalView
    private lateinit var input: EditText
    private lateinit var setupBanner: LinearLayout
    private var pty: PtySession? = null
    private val history=ArrayList<String>(); private var historyIndex=0
    private var started=false

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        runtime=LinuxRuntime(this)
        buildUi()
        startPty()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from installing a distro (DistroActivity/MainActivity) — start the
        // shell automatically if it wasn't running yet, so there's no extra tap needed.
        if (!started && runtime.isLinuxReady()) startPty()
        setupBanner.visibility = if (runtime.isLinuxReady()) LinearLayout.GONE else LinearLayout.VISIBLE
    }

    private fun buildUi() {
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(13,15,18))}

        val topBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(12,8,12,8);gravity=android.view.Gravity.CENTER_VERTICAL}
        val title=TextView(this).apply{text="🐧 LinOx";textSize=17f;setTextColor(0xffb95cff.toInt());layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}
        val menu=Button(this).apply{text="☰ Menu";setOnClickListener{startActivity(android.content.Intent(this@TerminalActivity, MainActivity::class.java))}}
        topBar.addView(title); topBar.addView(menu)
        root.addView(topBar)

        setupBanner=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,12,16,12);setBackgroundColor(Color.rgb(30,22,40));visibility=LinearLayout.GONE}
        setupBanner.addView(TextView(this).apply{text="No Linux distribution installed yet.";setTextColor(Color.WHITE);textSize=14f})
        val setupBtn=Button(this).apply{text="Install Ubuntu/Debian";setOnClickListener{startActivity(android.content.Intent(this@TerminalActivity, DistroActivity::class.java))}}
        setupBanner.addView(setupBtn)
        root.addView(setupBanner)

        terminal=TerminalView(this)
        root.addView(terminal,LinearLayout.LayoutParams(-1,0,1f))
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(6,4,6,4)}
        input=EditText(this).apply{
            singleLine=true;setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);hint="type here or use the keys"
            imeOptions=EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener{_,_,_->sendText(text.toString()+"\r");text.clear();true}
            setOnKeyListener{_,key,event->
                if(event.action!=KeyEvent.ACTION_DOWN)return@setOnKeyListener false
                when(key){
                    KeyEvent.KEYCODE_ENTER->{sendText("\r");true}
                    KeyEvent.KEYCODE_DEL->{sendText("\u007f");true}
                    KeyEvent.KEYCODE_DPAD_UP->{sendText("\u001b[A");true}
                    KeyEvent.KEYCODE_DPAD_DOWN->{sendText("\u001b[B");true}
                    KeyEvent.KEYCODE_DPAD_LEFT->{sendText("\u001b[D");true}
                    KeyEvent.KEYCODE_DPAD_RIGHT->{sendText("\u001b[C");true}
                    else->false
                }
            }
        }
        val send=Button(this).apply{text="Send";setOnClickListener{sendText(input.text.toString()+"\r");input.text.clear()}}
        val ctrl=Button(this).apply{text="Ctrl+C";setOnClickListener{pty?.interrupt()}}
        bar.addView(input,LinearLayout.LayoutParams(0,52,1f));bar.addView(send);bar.addView(ctrl)
        root.addView(bar)
        setContentView(root)
    }
    private fun startPty(){
        if (!runtime.isLinuxReady()) {
            setupBanner.visibility = LinearLayout.VISIBLE
            terminal.feed("[LinOx] No Linux distribution installed yet.\r\n[LinOx] Tap \"Install Ubuntu/Debian\" above, or Menu -> Packages/Distros, to get set up.\r\n")
            return
        }
        try {
            pty=runtime.startInteractivePty { data->runOnUiThread{terminal.feed(data)} }
            started=true
            setupBanner.visibility = LinearLayout.GONE
            terminal.postDelayed({pty?.let{val (c,r)=terminal.dimensions();it.resize(r,c)}},250)
        } catch(e:Exception){terminal.feed("[LinOx] ${e.message}\r\n")}
        input.requestFocus()
    }
    private fun sendText(s:String){if(s.isEmpty())return;pty?.write(s);if(s.trim().isNotEmpty()&&!s.contains("\u001b")){history.add(s.trim());historyIndex=history.size}}
    override fun onDestroy(){pty?.close();pty=null;started=false;super.onDestroy()}
}

package org.linox.mobile

import java.nio.charset.Charset

class PtySession private constructor(private val handle: Long) : AutoCloseable {
    companion object {
        init { System.loadLibrary("linoxpty") }
        fun start(command: String, args: List<String>, env: Map<String,String>, cwd: String): PtySession {
            val all = ArrayList<String>(); all.add(command); all.addAll(args)
            val h = nativeStart(command, all.toTypedArray(), env.map { "${it.key}=${it.value}" }.toTypedArray(), cwd)
            require(h != 0L) { "Unable to start PTY" }
            return PtySession(h)
        }
        @JvmStatic private external fun nativeStart(command:String,args:Array<String>,env:Array<String>,cwd:String):Long
        @JvmStatic private external fun nativeRead(handle:Long,out:ByteArray):Int
        @JvmStatic private external fun nativeWrite(handle:Long,data:ByteArray):Int
        @JvmStatic private external fun nativeResize(handle:Long,rows:Int,cols:Int)
        @JvmStatic private external fun nativeSignal(handle:Long,sig:Int)
        @JvmStatic private external fun nativeClose(handle:Long):Int
    }
    fun readLoop(onText:(String)->Unit,onExit:()->Unit) { Thread { val b=ByteArray(8192); try { while(true){ val n=nativeRead(handle,b); if(n<=0) break; onText(String(b,0,n,Charset.forName("UTF-8"))) } } finally { onExit() } }.start() }
    fun write(text:String) { nativeWrite(handle,text.toByteArray(Charsets.UTF_8)) }
    fun resize(rows:Int,cols:Int) { nativeResize(handle,rows,cols) }
    fun interrupt() { nativeSignal(handle,2) }
    override fun close(){nativeClose(handle)}
}

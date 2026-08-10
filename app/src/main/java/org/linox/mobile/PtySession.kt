package org.linox.mobile

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

class PtySession private constructor(private val handle: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    companion object {
        init {
            System.loadLibrary("linoxpty")
        }

        fun start(
            command: String,
            args: List<String>,
            env: Map<String, String>,
            cwd: String
        ): PtySession {
            val all = ArrayList<String>(args.size + 1)
            all.add(command)
            all.addAll(args)
            val handle = nativeStart(
                command,
                all.toTypedArray(),
                env.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd
            )
            require(handle != 0L) { "Unable to start PTY" }
            return PtySession(handle)
        }

        @JvmStatic private external fun nativeStart(
            command: String,
            args: Array<String>,
            env: Array<String>,
            cwd: String
        ): Long

        @JvmStatic private external fun nativeRead(handle: Long, out: ByteArray): Int
        @JvmStatic private external fun nativeWrite(handle: Long, data: ByteArray): Int
        @JvmStatic private external fun nativeResize(handle: Long, rows: Int, cols: Int)
        @JvmStatic private external fun nativeSignal(handle: Long, sig: Int)
        @JvmStatic private external fun nativeClose(handle: Long): Int
    }

    fun readLoop(onText: (String) -> Unit, onExit: () -> Unit) {
        Thread {
            val buffer = ByteArray(8192)
            try {
                while (!closed.get()) {
                    val n = nativeRead(handle, buffer)
                    if (n <= 0) break
                    onText(String(buffer, 0, n, Charset.forName("UTF-8")))
                }
            } finally {
                onExit()
            }
        }.apply {
            name = "LinOx-PTY"
            isDaemon = true
            start()
        }
    }

    fun write(text: String) {
        if (!closed.get() && text.isNotEmpty()) {
            nativeWrite(handle, text.toByteArray(Charsets.UTF_8))
        }
    }

    fun resize(rows: Int, cols: Int) {
        if (!closed.get()) nativeResize(handle, rows, cols)
    }

    fun interrupt() {
        if (!closed.get()) nativeSignal(handle, 2)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) nativeClose(handle)
    }
}

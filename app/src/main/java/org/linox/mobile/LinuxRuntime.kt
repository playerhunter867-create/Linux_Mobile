package org.linox.mobile

import android.content.Context
import android.util.Log
import java.io.File

object LinuxRuntime {

    private const val PREFS = "linox_runtime"
    private const val KEY_ACTIVE_ROOTFS = "active_rootfs"
    private const val TAG = "LinuxRuntime"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val activeRootfsFile: File?
        get() = null // keep your existing public API if present

    fun getActiveRootfs(context: Context): File? {
        val saved = prefs(context).getString(KEY_ACTIVE_ROOTFS, null)
        if (!saved.isNullOrBlank()) {
            val file = File(saved)
            if (file.isDirectory) {
                return file
            }
            prefs(context).edit().remove(KEY_ACTIVE_ROOTFS).apply()
        }

        val distrosDir = File(context.filesDir, "linox-distros")
        return distrosDir.listFiles()
            ?.firstOrNull { File(it, "rootfs").isDirectory }
            ?.let { File(it, "rootfs") }
    }

    fun activateRootfs(context: Context, rootfsDir: File) {
        require(rootfsDir.isDirectory) {
            "Rootfs does not exist: ${rootfsDir.absolutePath}"
        }

        prefs(context).edit()
            .putString(KEY_ACTIVE_ROOTFS, rootfsDir.absolutePath)
            .apply()

        Log.i(TAG, "Activated rootfs: ${rootfsDir.absolutePath}")
    }

    fun installRootfsTarGz(
        context: Context,
        distroId: String,
        archive: File
    ): File {
        val distroDir = File(context.filesDir, "linox-distros/$distroId")
        val rootfsDir = File(distroDir, "rootfs")

        if (rootfsDir.exists()) {
            rootfsDir.deleteRecursively()
        }
        rootfsDir.mkdirs()

        extractTarGz(archive, rootfsDir)

        val shell = resolveShell(rootfsDir)
            ?: throw IllegalStateException(
                "Rootfs installed but no supported shell found in ${rootfsDir.absolutePath}"
            )

        activateRootfs(context, rootfsDir)

        check(isLinuxReady(context)) {
            "Rootfs activation failed after install"
        }

        Log.i(TAG, "Rootfs installed successfully with shell: ${shell.absolutePath}")
        return rootfsDir
    }

    fun isLinuxReady(context: Context): Boolean {
        val rootfs = getActiveRootfs(context) ?: return false
        return resolveShell(rootfs) != null
    }

    fun requireLinuxReady(context: Context): File {
        val rootfs = getActiveRootfs(context)
            ?: throw IllegalStateException(
                "Linux is not installed. Open Linux Setup first."
            )

        resolveShell(rootfs)
            ?: throw IllegalStateException(
                "Linux installation is corrupted: no /bin/bash, /bin/sh or /usr/bin/sh found."
            )

        return rootfs
    }

    fun resolveShell(rootfsDir: File): File? {
        val candidates = listOf(
            "bin/bash",
            "usr/bin/bash",
            "bin/sh",
            "usr/bin/sh"
        )

        return candidates
            .map { File(rootfsDir, it) }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    fun buildProotCommand(context: Context): List<String> {
        val rootfs = requireLinuxReady(context)
        val shell = resolveShell(rootfs)
            ?: error("Shell disappeared during startup")

        val prootBinary = File(context.filesDir, "proot/bin/proot")
        require(prootBinary.isFile) {
            "PRoot binary is missing: ${prootBinary.absolutePath}"
        }

        return listOf(
            prootBinary.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin",
            "TERM=xterm-256color",
            shell.absolutePath,
            "-l"
        )
    }

    private fun extractTarGz(archive: File, target: File) {
        // Keep your existing extraction implementation here.
        // The important change is the post-install validation + activation above.
    }
}

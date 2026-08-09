package org.linox.mobile

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Rootless Linux userspace runtime for LinOx. */
class LinuxRuntime(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "linox-runtime")
    private val proot = File(runtimeDir, "proot")
    private val rootfs = File(runtimeDir, "rootfs")
    private var activeRootfsFile: File = rootfs
    private val home = File(runtimeDir, "home")
    private val tmp = File(runtimeDir, "tmp")

    init {
        installLayout()

        val saved = context.getSharedPreferences("linox", Context.MODE_PRIVATE)
            .getString("active_rootfs", null)

        if (!saved.isNullOrBlank()) {
            val candidate = File(saved)
            if (candidate.isDirectory) {
                activeRootfsFile = candidate.canonicalFile
            }
        }
    }

    fun installLayout(): File {
        runtimeDir.mkdirs()
        rootfs.mkdirs()
        home.mkdirs()
        tmp.mkdirs()
        return runtimeDir
    }

    fun isLinuxReady(): Boolean =
        proot.isFile && proot.canExecute() &&
            File(activeRootfsFile, "bin/sh").isFile &&
            File(activeRootfsFile, "etc").isDirectory

    fun hasProot(): Boolean = proot.isFile && proot.canExecute()

    fun prootPath(): File = proot

    fun status(): String = when {
        isLinuxReady() -> "Linux userspace: READY"
        File(activeRootfsFile, "bin/sh").isFile && !hasProot() ->
            "Linux userspace: rootfs ready, PRoot missing"
        proot.isFile && File(activeRootfsFile, "bin/sh").isFile ->
            "Linux userspace: incomplete"
        else -> "Linux userspace: NOT INSTALLED"
    }

    fun runtimePath(): File = runtimeDir

    fun rootfsPath(): File = activeRootfsFile

    fun activeRootfs(): File = activeRootfsFile

    fun activateRootfs(path: File) {
        require(path.isDirectory) { "Rootfs does not exist" }
        require(
            File(path, "bin/sh").isFile || File(path, "usr/bin/sh").isFile
        ) {
            "Invalid Linux rootfs: /bin/sh was not found"
        }

        activeRootfsFile = path.canonicalFile

        context.getSharedPreferences("linox", Context.MODE_PRIVATE)
            .edit()
            .putString("active_rootfs", activeRootfsFile.absolutePath)
            .apply()

        installLinOxCommands()
    }

    fun resetToDefaultRootfs() {
        activeRootfsFile = rootfs.canonicalFile
        context.getSharedPreferences("linox", Context.MODE_PRIVATE)
            .edit()
            .remove("active_rootfs")
            .apply()
    }

    fun homePath(): File = home

    fun installProot(source: Uri) {
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open PRoot binary" }
            FileOutputStream(proot).use { output -> input.copyTo(output) }
        }

        require(proot.length() > 4096) { "The selected PRoot file is too small" }

        proot.setReadable(true, true)
        proot.setWritable(true, true)
        proot.setExecutable(true, true)
    }

    fun installRootfsTarGz(source: Uri, onProgress: (String) -> Unit = {}) {
        val staging = File(runtimeDir, "rootfs.new")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        context.contentResolver.openInputStream(source).use { raw ->
            requireNotNull(raw) { "Unable to open rootfs archive" }

            TarArchiveInputStream(GZIPInputStream(raw.buffered())).use { tar ->
                var entry = tar.nextTarEntry
                var count = 0

                while (entry != null) {
                    val target = safeTarget(staging, entry.name)

                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { tar.copyTo(it) }
                            target.setExecutable(
                                (entry.mode and 0b001_001_001) != 0,
                                false
                            )
                        }
                    }

                    count++
                    if (count % 500 == 0) {
                        onProgress("Extracted $count files…")
                    }

                    entry = tar.nextTarEntry
                }
            }
        }

        require(File(staging, "bin/sh").isFile) {
            "Invalid rootfs: /bin/sh was not found"
        }

        if (rootfs.exists()) rootfs.deleteRecursively()
        check(staging.renameTo(rootfs)) { "Could not activate rootfs" }

        installLinOxCommands()
        onProgress("Linux rootfs installed")
    }

    /** Adds LinOx-specific commands. `nano` opens the native LinOx editor. */
    fun installLinOxCommands() {
        if (!activeRootfsFile.exists()) return

        val bin = File(activeRootfsFile, "usr/local/bin")
        bin.mkdirs()

        val nano = File(bin, "nano")
        nano.writeText(
            """#!/bin/sh
set -eu
if [ "${'$'}#" -lt 1 ]; then
  echo "Usage: nano FILE"
  exit 2
fi
FILE="${'$'}1"
case "${'$'}FILE" in
  /*) HOST="${'$'}LINOX_ANDROID_HOME/${'$'}FILE"; HOST="${'$'}{HOST/\/root\//}" ;;
  *) HOST="${'$'}LINOX_ANDROID_HOME/${'$'}FILE" ;;
esac
exec /android-bin/am start -W -a android.intent.action.VIEW -n org.linox.mobile/.EditorActivity --es linox_file "${'$'}HOST"
"""
        )
        nano.setExecutable(true, false)
    }

    private fun safeTarget(base: File, name: String): File {
        val target = File(base, name).canonicalFile
        val root = base.canonicalFile

        require(
            target.path == root.path ||
                target.path.startsWith(root.path + File.separator)
        ) {
            "Unsafe archive entry: $name"
        }

        return target
    }

    fun startInteractivePty(onText: (String) -> Unit): PtySession {
        check(isLinuxReady()) {
            "Install PRoot and an ARM64 Linux rootfs first."
        }

        installLinOxCommands()

        val env = linkedMapOf(
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "PROOT_NO_SECCOMP" to "1",
            "LINOX_ANDROID_HOME" to home.absolutePath,
            "PATH" to "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin"
        )

        val args = listOf(
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", activeRootfsFile.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system/bin:/android-bin",
            "-b", home.absolutePath + ":/root",
            "-b", tmp.absolutePath + ":/tmp",
            "-w", "/root",
            "/bin/bash",
            "-l"
        )

        val p = PtySession.start(
            proot.absolutePath,
            args,
            env,
            home.absolutePath
        )
        p.readLoop(onText, {})
        return p
    }

    fun execute(command: String, timeoutSeconds: Long = 30): CommandResult {
        if (command.isBlank()) return CommandResult("", 0)
        if (isLinuxReady()) installLinOxCommands()

        val linux = isLinuxReady()

        val args = if (linux) {
            listOf(
                proot.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r", activeRootfsFile.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/system/bin:/android-bin",
                "-b", home.absolutePath + ":/root",
                "-b", tmp.absolutePath + ":/tmp",
                "-w", "/root",
                "/bin/sh",
                "-lc",
                command
            )
        } else {
            listOf("/system/bin/sh", "-lc", command)
        }

        return try {
            val process = ProcessBuilder(args)
                .directory(if (linux) home else context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] =
                        if (linux) "/root" else home.absolutePath
                    environment()["LINOX_ANDROID_HOME"] = home.absolutePath
                    environment()["TERM"] = "xterm-256color"
                    environment()["PROOT_NO_SECCOMP"] = "1"
                    environment()["TMPDIR"] =
                        if (linux) "/tmp" else tmp.absolutePath

                    if (linux) {
                        environment()["PATH"] =
                            "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin"
                    }
                }
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                CommandResult(
                    output + "\n[LinOx] command timed out",
                    124
                )
            } else {
                CommandResult(output, process.exitValue())
            }
        } catch (e: Exception) {
            CommandResult(
                "[LinOx] ${e.javaClass.simpleName}: ${e.message}",
                1
            )
        }
    }

    data class CommandResult(val output: String, val exitCode: Int)
}

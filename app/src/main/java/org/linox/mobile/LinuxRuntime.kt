package org.linox.mobile

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
            val candidate = runCatching { File(saved).canonicalFile }.getOrNull()
            if (candidate?.isDirectory == true && isSafeRootfsPath(candidate)) {
                activeRootfsFile = candidate
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

    fun isLinuxReady(): Boolean {
        if (!proot.isFile || !proot.canExecute()) return false
        if (!activeRootfsFile.isDirectory) return false
        return File(activeRootfsFile, "bin/sh").isFile &&
            File(activeRootfsFile, "etc").isDirectory
    }

    fun status(): String = when {
        isLinuxReady() -> "Linux userspace: READY"
        proot.isFile && File(activeRootfsFile, "bin/sh").isFile ->
            "Linux userspace: incomplete (PRoot/rootfs check failed)"
        else -> "Linux userspace: NOT INSTALLED"
    }

    fun runtimePath(): File = runtimeDir
    fun rootfsPath(): File = activeRootfsFile
    fun activeRootfs(): File = activeRootfsFile
    fun homePath(): File = home

    fun activateRootfs(path: File) {
        val canonical = path.canonicalFile
        require(canonical.isDirectory) { "Rootfs does not exist: $canonical" }
        require(File(canonical, "bin/sh").isFile) { "Rootfs has no /bin/sh" }
        activeRootfsFile = canonical
        context.getSharedPreferences("linox", Context.MODE_PRIVATE)
            .edit()
            .putString("active_rootfs", canonical.absolutePath)
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

    fun installProot(source: Uri) {
        val staging = File(runtimeDir, "proot.new")
        if (staging.exists()) staging.delete()
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open PRoot binary" }
            FileOutputStream(staging).use { output -> input.copyTo(output) }
        }
        require(staging.length() >= 1024) { "The selected PRoot file is too small" }
        require(isArm64Elf(staging)) {
            "Selected PRoot is not an ARM64 ELF executable"
        }
        staging.setReadable(true, true)
        staging.setWritable(true, true)
        staging.setExecutable(true, true)

        if (proot.exists()) proot.delete()
        check(staging.renameTo(proot)) { "Could not activate PRoot" }

        val result = runCatching {
            ProcessBuilder(proot.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
                .also { process ->
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        error("PRoot validation timed out")
                    }
                }
        }.getOrElse { e ->
            error("PRoot cannot be executed: ${e.message}")
        }

        if (result.exitValue() != 0) {
            val text = result.inputStream.bufferedReader().use { it.readText() }.trim()
            error("PRoot failed to start${if (text.isNotEmpty()) ": $text" else ""}")
        }
    }

    fun installRootfsTarGz(source: Uri, onProgress: (String) -> Unit = {}) {
        val staging = File(runtimeDir, "rootfs.new")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        try {
            context.contentResolver.openInputStream(source).use { raw ->
                requireNotNull(raw) { "Unable to open rootfs archive" }
                GZIPInputStream(raw.buffered()).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        val entries = HashMap<String, File>()
                        var entry = tar.nextTarEntry
                        var count = 0
                        while (entry != null) {
                            val name = normalizeArchiveName(entry!!.name)
                            if (name.isEmpty()) {
                                entry = tar.nextTarEntry
                                continue
                            }
                            val target = safeTarget(staging, name)
                            when {
                                entry!!.isDirectory -> target.mkdirs()
                                entry!!.isSymbolicLink -> {
                                    deleteAny(target)
                                    target.parentFile?.mkdirs()
                                    createSafeSymlink(
                                        staging, target, entry!!.linkName, name
                                    )
                                }
                                entry!!.isLink -> {
                                    deleteAny(target)
                                    target.parentFile?.mkdirs()
                                    val linkName = normalizeArchiveName(entry!!.linkName)
                                    val linked = entries[linkName] ?: safeTarget(staging, linkName)
                                    require(linked.isFile) {
                                        "Broken hardlink: ${entry!!.linkName}"
                                    }
                                    linked.copyTo(target, overwrite = true)
                                }
                                entry!!.isFile -> {
                                    deleteAny(target)
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { tar.copyTo(it) }
                                    applyMode(target, entry!!.mode)
                                }
                            }
                            entries[name] = target
                            count++
                            if (count % 500 == 0) onProgress("Extracted $count files…")
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }

            require(File(staging, "bin/sh").isFile) {
                "Invalid rootfs: /bin/sh was not found"
            }
            require(File(staging, "etc").isDirectory) {
                "Invalid rootfs: /etc was not found"
            }

            if (rootfs.exists()) rootfs.deleteRecursively()
            check(staging.renameTo(rootfs)) { "Could not activate rootfs" }
            activeRootfsFile = rootfs.canonicalFile
            context.getSharedPreferences("linox", Context.MODE_PRIVATE)
                .edit()
                .remove("active_rootfs")
                .apply()
            installLinOxCommands()
            onProgress("Linux rootfs installed")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    fun installLinOxCommands() {
        if (!activeRootfsFile.isDirectory) return
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
  /*) HOST="${'$'}LINOX_ANDROID_HOME/${'$'}{FILE#/root/}" ;;
  *) HOST="${'$'}LINOX_ANDROID_HOME/${'$'}FILE" ;;
esac
exec /android-bin/am start -W -a android.intent.action.VIEW -n org.linox.mobile/.EditorActivity --es linox_file "${'$'}HOST"
""".trimStart()
        )
        nano.setReadable(true, true)
        nano.setExecutable(true, false)
    }

    fun startInteractivePty(onText: (String) -> Unit): PtySession {
        check(isLinuxReady()) {
            "Linux shell is not ready. Install a valid ARM64 PRoot and an ARM64 Debian/Ubuntu rootfs first."
        }
        installLinOxCommands()

        val env = linkedMapOf(
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
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
            "--login"
        )

        val session = PtySession.start(proot.absolutePath, args, env, home.absolutePath)
        session.readLoop(onText) {}
        return session
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
                "/bin/sh", "-lc", command
            )
        } else {
            listOf("/system/bin/sh", "-lc", command)
        }

        return try {
            val process = ProcessBuilder(args)
                .directory(if (linux) home else context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = if (linux) "/root" else home.absolutePath
                    environment()["LINOX_ANDROID_HOME"] = home.absolutePath
                    environment()["TERM"] = "xterm-256color"
                    environment()["PROOT_NO_SECCOMP"] = "1"
                    environment()["TMPDIR"] = if (linux) "/tmp" else tmp.absolutePath
                    if (linux) {
                        environment()["PATH"] =
                            "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin"
                    }
                }
                .start()

            val output = process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val text = StringBuilder()
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    text.append(buffer, 0, n)
                }
                text.toString()
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandResult(output + "\n[LinOx] command timed out", 124)
            } else {
                CommandResult(output, process.exitValue())
            }
        } catch (e: Exception) {
            CommandResult("[LinOx] ${e.javaClass.simpleName}: ${e.message}", 1)
        }
    }

    data class CommandResult(val output: String, val exitCode: Int)

    private fun normalizeArchiveName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
        require(name.isNotEmpty() || raw.isNotEmpty()) {
            "Invalid empty archive entry"
        }
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Unsafe archive entry: $raw" }
        return parts.joinToString("/")
    }

    private fun safeTarget(base: File, name: String): File {
        val root = base.canonicalFile
        val target = File(root, name).canonicalFile
        require(
            target.path == root.path ||
                target.path.startsWith(root.path + File.separator)
        ) { "Unsafe archive path: $name" }
        return target
    }

    private fun createSafeSymlink(
        base: File,
        target: File,
        linkName: String,
        entryName: String
    ) {
        require(linkName.isNotEmpty()) {
            "Empty symlink target: $entryName"
        }

        val targetPath = if (linkName.startsWith('/')) {
            "/" + linkName.trimStart('/')
        } else {
            "/" + target.relativeTo(base).parent.orEmpty()
                .replace(File.separatorChar, '/') + "/" + linkName
        }

        val parts = targetPath.split('/').filter { it.isNotEmpty() }
        var depth = 0
        for (part in parts) {
            if (part == ".") continue
            if (part == "..") depth-- else depth++
            require(depth >= 0) {
                "Unsafe symlink target: $entryName -> $linkName"
            }
        }

        java.nio.file.Files.createSymbolicLink(
            target.toPath(),
            java.nio.file.Paths.get(linkName)
        )
    }

    private fun deleteAny(file: File) {
        if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            file.deleteRecursively()
        }
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable((mode and 0b100_100_100) != 0, false)
        file.setWritable((mode and 0b010_010_010) != 0, false)
        file.setExecutable((mode and 0b001_001_001) != 0, false)
    }

    private fun isSafeRootfsPath(candidate: File): Boolean {
        val runtime = runtimeDir.canonicalFile
        return candidate.path == runtime.path ||
            candidate.path.startsWith(runtime.path + File.separator)
    }

    private fun isArm64Elf(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val ident = ByteArray(20)
            raf.readFully(ident)
            ident[0] == 0x7f.toByte() &&
                ident[1] == 'E'.code.toByte() &&
                ident[2] == 'L'.code.toByte() &&
                ident[3] == 'F'.code.toByte() &&
                ident[4].toInt() == 2 &&
                ident[5].toInt() == 1 &&
                (ident[18].toInt() and 0xff) == 183
        }
    }.getOrDefault(false)
}

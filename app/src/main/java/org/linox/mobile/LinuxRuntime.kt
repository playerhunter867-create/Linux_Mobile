package org.linox.mobile

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Rootless Linux runtime for LinOx.
 *
 * The Android kernel remains the host kernel. PRoot provides the filesystem
 * view needed by the Linux userspace; no root permission is required.
 */
class LinuxRuntime(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "linox-runtime")
    private val proot = File(runtimeDir, "proot")
    private val rootfs = File(runtimeDir, "rootfs")
    private val home = File(runtimeDir, "home")
    private val tmp = File(runtimeDir, "tmp")
    private var activeRootfsFile = rootfs

    init {
        installLayout()
        val saved = context.getSharedPreferences("linox", Context.MODE_PRIVATE)
            .getString("active_rootfs", null)
        if (!saved.isNullOrBlank()) {
            val candidate = runCatching { File(saved).canonicalFile }.getOrNull()
            if (candidate != null && candidate.isDirectory && isSafeRootfsPath(candidate)) {
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

    fun hasProot(): Boolean = proot.isFile && proot.canExecute()

    fun isLinuxReady(): Boolean =
        hasProot() &&
            activeRootfsFile.isDirectory &&
            (File(activeRootfsFile, "bin/sh").isFile ||
             File(activeRootfsFile, "usr/bin/sh").isFile) &&
            File(activeRootfsFile, "etc").isDirectory

    fun status(): String = when {
        isLinuxReady() -> "Linux userspace: READY"
        hasProot() -> "PRoot installed — choose a Linux distribution"
        activeRootfsFile.resolve("bin/sh").isFile ->
            "Linux rootfs found — PRoot is missing"
        else -> "Linux userspace: NOT INSTALLED"
    }

    fun runtimePath(): File = runtimeDir
    fun rootfsPath(): File = activeRootfsFile
    fun activeRootfs(): File = activeRootfsFile
    fun homePath(): File = home
    fun prootPath(): File = proot

    fun activateRootfs(path: File) {
        val canonical = path.canonicalFile
        require(canonical.isDirectory) { "Rootfs does not exist: $canonical" }
        require(
            File(canonical, "bin/sh").isFile ||
            File(canonical, "usr/bin/sh").isFile
        ) { "Rootfs has no usable /bin/sh or /usr/bin/sh" }

        activeRootfsFile = canonical
        context.getSharedPreferences("linox", Context.MODE_PRIVATE)
            .edit()
            .putString("active_rootfs", canonical.absolutePath)
            .apply()

        prepareNetworking()
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

        require(staging.length() >= 4096) { "Selected PRoot file is too small" }
        require(isArm64Elf(staging)) {
            "Selected PRoot is not a 64-bit ARM (AArch64) ELF executable"
        }

        staging.setReadable(true, true)
        staging.setWritable(true, true)
        staging.setExecutable(true, true)

        val test = runCatching {
            ProcessBuilder(staging.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
                .also { p ->
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                        error("PRoot validation timed out")
                    }
                }
        }.getOrElse { e ->
            staging.delete()
            error("PRoot cannot be executed on this Android device: ${e.message}")
        }

        if (test.exitValue() != 0) {
            val text = test.inputStream.bufferedReader().use { it.readText() }.trim()
            staging.delete()
            error("PRoot failed validation${if (text.isNotEmpty()) ": $text" else ""}")
        }

        if (proot.exists()) proot.delete()
        check(staging.renameTo(proot)) { "Could not activate PRoot" }
        proot.setExecutable(true, true)
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
                        var entry = tar.nextTarEntry
                        var count = 0

                        while (entry != null) {
                            val name = normalizeArchiveName(entry.name)
                            if (name.isEmpty()) {
                                entry = tar.nextTarEntry
                                continue
                            }

                            val target = safeTarget(staging, name)

                            when {
                                entry.isDirectory -> target.mkdirs()
                                entry.isSymbolicLink -> {
                                    deleteAny(target)
                                    target.parentFile?.mkdirs()
                                    createSafeSymlink(staging, target, entry.linkName, name)
                                }
                                entry.isFile -> {
                                    deleteAny(target)
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { tar.copyTo(it) }
                                    applyMode(target, entry.mode)
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
            }

            require(
                File(staging, "bin/sh").isFile ||
                File(staging, "usr/bin/sh").isFile
            ) { "Invalid rootfs: no /bin/sh or /usr/bin/sh" }
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

            prepareNetworking()
            installLinOxCommands()
            onProgress("Linux rootfs installed")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /**
     * Fix DNS inside PRoot. Android supplies the actual network stack; the
     * Linux userspace needs a resolver file that points at usable DNS servers.
     */
    fun prepareNetworking() {
        if (!activeRootfsFile.isDirectory) return

        val etc = File(activeRootfsFile, "etc")
        etc.mkdirs()
        val resolv = File(etc, "resolv.conf")

        val dns = listOf(
            readAndroidProperty("net.dns1"),
            readAndroidProperty("net.dns2"),
            readAndroidProperty("net.dns3"),
            readAndroidProperty("net.dns4")
        ).filter { it.isNotBlank() && it != "0.0.0.0" }.distinct()

        val servers = if (dns.isNotEmpty()) dns else listOf("1.1.1.1", "8.8.8.8")

        runCatching {
            if (java.nio.file.Files.isSymbolicLink(resolv.toPath()) || resolv.exists()) {
                resolv.delete()
            }
            resolv.writeText(
                servers.joinToString("\n") { "nameserver $it" } + "\n"
            )
        }

        File(etc, "hostname").writeText("linox\n")
    }

    fun installLinOxCommands() {
        if (!activeRootfsFile.isDirectory) return
        prepareNetworking()

        val bin = File(activeRootfsFile, "usr/local/bin")
        bin.mkdirs()

        File(bin, "linox-info").apply {
            writeText(
                """#!/bin/sh
echo "LinOx Linux 1.0.0"
echo "Kernel: $(uname -a)"
echo "Architecture: $(uname -m)"
echo "Root: ${'$'}HOME"
echo "Network: $(getent hosts registry-1.docker.io 2>/dev/null | head -n 1 || echo DNS-check-failed)"
"""
            )
            setExecutable(true, false)
        }

        File(bin, "nano").apply {
            writeText(
                """#!/bin/sh
if [ "${'$'}#" -lt 1 ]; then
  echo "Usage: nano FILE"
  exit 2
fi
FILE="${'$'}1"
case "${'$'}FILE" in
  /root/*) HOST="${'$'}LINOX_ANDROID_HOME/${'$'}{FILE#/root/}" ;;
  /*) HOST="${'$'}LINOX_ANDROID_HOME/${'$'}FILE" ;;
  *) HOST="${'$'}LINOX_ANDROID_HOME/${'$'}FILE" ;;
esac
exec /android-bin/am start -W -a android.intent.action.VIEW \
  -n org.linox.mobile/.EditorActivity --es linox_file "${'$'}HOST"
"""
            )
            setReadable(true, true)
            setExecutable(true, false)
        }
    }

    fun startInteractivePty(onText: (String) -> Unit): PtySession {
        check(isLinuxReady()) {
            "Install an ARM64 PRoot and a Linux ARM64 distribution first."
        }

        prepareNetworking()
        installLinOxCommands()

        val shell = when {
            File(activeRootfsFile, "bin/bash").isFile -> listOf("/bin/bash", "--login")
            File(activeRootfsFile, "usr/bin/bash").isFile -> listOf("/usr/bin/bash", "--login")
            else -> listOf("/bin/sh", "-l")
        }

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

        val args = baseProotArgs() + shell
        val session = PtySession.start(
            proot.absolutePath,
            args,
            env,
            "/root"
        )
        session.readLoop(onText) {}
        return session
    }

    fun execute(command: String, timeoutSeconds: Long = 60): CommandResult {
        if (command.isBlank()) return CommandResult("", 0)
        if (isLinuxReady()) {
            prepareNetworking()
            installLinOxCommands()
        }

        val linux = isLinuxReady()
        val args = if (linux) {
            baseProotArgs() + listOf("/bin/sh", "-lc", command)
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

    private fun baseProotArgs(): List<String> = listOf(
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
        "-w", "/root"
    )

    private fun readAndroidProperty(name: String): String = runCatching {
        Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", name))
            .inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrDefault("")

    private fun normalizeArchiveName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
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

        val targetText = if (linkName.startsWith("/")) {
            linkName
        } else {
            "/" + target.relativeTo(base).parent.orEmpty()
                .replace(File.separatorChar, '/') + "/" + linkName
        }

        val parts = targetText.split('/').filter { it.isNotEmpty() }
        var depth = 0
        for (part in parts) {
            when (part) {
                "." -> Unit
                ".." -> depth--
                else -> depth++
            }
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

    data class CommandResult(val output: String, val exitCode: Int)
}

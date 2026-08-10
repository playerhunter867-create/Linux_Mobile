package org.linox.mobile

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Rootless Linux runtime for LinOx.
 *
 * Android stays the host kernel. PRoot supplies the rootfs view; no root is required.
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
        ensureBundledProot()
        val saved = context.getSharedPreferences("linox", Context.MODE_PRIVATE)
            .getString("active_rootfs", null)
        if (!saved.isNullOrBlank()) {
            val candidate = runCatching { File(saved).canonicalFile }.getOrNull()
            if (candidate != null && candidate.isDirectory && isAllowedRootfsPath(candidate)) {
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

    /** Explicit public API used by the distro UI and terminal. */
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
        File(activeRootfsFile, "bin/sh").isFile ->
            "Linux rootfs found — PRoot is missing"
        else -> "Linux userspace: NOT INSTALLED"
    }

    fun runtimePath(): File = runtimeDir
    fun rootfsPath(): File = activeRootfsFile
    fun activeRootfs(): File = activeRootfsFile
    fun homePath(): File = home
    fun prootPath(): File = proot

    /** Host-side directory that is bind-mounted as /root inside Linux. */
    fun workspacePath(): File = File(home, "workspace").apply { mkdirs() }

    fun activateRootfs(path: File) {
        val canonical = path.canonicalFile
        require(isAllowedRootfsPath(canonical)) {
            "Rootfs must live inside LinOx app storage"
        }
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

    /** Install a user-selected ARM64 PRoot binary. */
    fun installProot(source: Uri) {
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open PRoot binary" }
            installProotStream(input)
        }
    }

    /** Install the bundled ARM64 PRoot shipped with LinOx 0.9.0 when available. */
    private fun ensureBundledProot() {
        if (hasProot()) return
        runCatching {
            context.assets.open("proot-aarch64-static").use { input ->
                installProotStream(input)
            }
        }
    }

    private fun installProotStream(input: java.io.InputStream) {
        val staging = File(runtimeDir, "proot.new")
        if (staging.exists()) staging.delete()

        FileOutputStream(staging).use { output -> input.copyTo(output) }

        require(staging.length() >= 4096) { "Selected PRoot file is too small" }
        require(isArm64Elf(staging)) {
            "PRoot is not a 64-bit ARM (AArch64) ELF executable"
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
        proot.setReadable(true, true)
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
                            if (count % 500 == 0) onProgress("Extracted $count files…")
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
                .edit().remove("active_rootfs").apply()

            prepareNetworking()
            installLinOxCommands()
            onProgress("Linux rootfs installed")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** Android network is reused by PRoot; this only supplies DNS inside the guest. */
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
            resolv.writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
        }
        File(etc, "hostname").writeText("linox\n")
    }

    /** Install a small, predictable developer toolset inside the active distro. */
    fun bootstrapDevelopmentTools(packageManager: String) {
        check(isLinuxReady()) { "Linux rootfs is not ready" }
        prepareNetworking()

        val command = when (packageManager.lowercase()) {
            "apt" -> "export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y python3 python3-pip nano git curl wget ca-certificates bash"
            "apk" -> "apk update && apk add --no-cache python3 py3-pip nano git curl wget ca-certificates bash"
            "dnf" -> "dnf -y install python3 python3-pip nano git curl wget ca-certificates bash"
            "pacman" -> "pacman -Sy --noconfirm python python-pip nano git curl wget ca-certificates bash"
            "zypper" -> "zypper --non-interactive refresh && zypper --non-interactive install -y python3 python3-pip nano git curl wget ca-certificates bash"
            else -> "true"
        }

        val result = execute(command, 20 * 60)
        require(result.exitCode == 0) {
            "Developer tools installation failed (exit ${result.exitCode}): ${result.output.takeLast(4000)}"
        }

        // Many distros expose only python3. Make `python` convenient without
        // overwriting a distro-provided executable.
        execute("if command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then ln -s \"$(command -v python3)\" /usr/local/bin/python 2>/dev/null || true; fi", 30)
        execute("if command -v pip3 >/dev/null 2>&1 && ! command -v pip >/dev/null 2>&1; then ln -s \"$(command -v pip3)\" /usr/local/bin/pip 2>/dev/null || true; fi", 30)
        installLinOxCommands()
    }

    fun installLinOxCommands() {
        if (!activeRootfsFile.isDirectory) return
        prepareNetworking()

        val bin = File(activeRootfsFile, "usr/local/bin")
        bin.mkdirs()

        File(bin, "linox-info").apply {
            writeText(
                """#!/bin/sh
echo "LinOx Mobile 0.9"
echo "Userspace: $(cat /etc/os-release 2>/dev/null | sed -n '1p' || echo Linux)"
echo "Architecture: $(uname -m)"
echo "Host kernel: $(uname -sr)"
echo "Python: $(python --version 2>&1 || python3 --version 2>&1 || echo not-installed)"
echo "Git: $(git --version 2>/dev/null || echo not-installed)"
echo "Nano: $(nano --version 2>/dev/null | head -n 1 || echo not-installed)"
"""
            )
            setExecutable(true, false)
        }

        File(bin, "linox-doctor").apply {
            writeText(
                """#!/bin/sh
ok=0
command -v sh >/dev/null 2>&1 && echo "[OK] shell" || { echo "[FAIL] shell"; ok=1; }
command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 && echo "[OK] Python" || echo "[WARN] Python not installed"
command -v git >/dev/null 2>&1 && echo "[OK] Git" || echo "[WARN] Git not installed"
test -f /etc/resolv.conf && echo "[OK] DNS configuration" || echo "[WARN] /etc/resolv.conf missing"
printf "Kernel: "; uname -sr
printf "Arch: "; uname -m
exit $ok
"""
            )
            setExecutable(true, false)
        }

        File(bin, "linox").apply {
            writeText(
                """#!/bin/sh
case "$1" in
  info) linox-info ;;
  doctor) linox-doctor ;;
  shell) exec /bin/sh ;;
  *) echo "LinOx commands: info | doctor | shell" ;;
esac
"""
            )
            setExecutable(true, false)
        }

        File(bin, "ll").apply {
            if (!exists()) {
                writeText("#!/bin/sh\nls -lah \"${'$'}@\"\n")
                setExecutable(true, false)
            }
        }

        val profile = File(activeRootfsFile, "etc/profile.d/linox.sh")
        profile.parentFile?.mkdirs()
        profile.writeText(
            """
            export TERM="${'$'}{TERM:-xterm-256color}"
            export PATH="/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin:${'$'}PATH"
            export LANG="${'$'}{LANG:-C.UTF-8}"
            """.trimIndent() + "\n"
        )
    }

    fun startInteractivePty(onText: (String) -> Unit): PtySession {
        check(isLinuxReady()) {
            "Install an ARM64 PRoot and a Linux ARM64 distribution first."
        }

        prepareNetworking()
        installLinOxCommands()

        val shell = when {
            File(activeRootfsFile, "bin/bash").exists() -> listOf("/bin/bash", "--login")
            File(activeRootfsFile, "usr/bin/bash").exists() -> listOf("/usr/bin/bash", "--login")
            File(activeRootfsFile, "bin/sh").exists() -> listOf("/bin/sh", "-l")
            else -> listOf("/usr/bin/sh", "-l")
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

        val session = PtySession.start(
            proot.absolutePath,
            baseProotArgs() + shell,
            env,
            home.absolutePath
        )
        session.readLoop(onText) {}
        return session
    }

    fun execute(command: String, timeoutSeconds: Long = 60): CommandResult {
        if (command.isBlank()) return CommandResult("", 0)

        val linux = isLinuxReady()
        if (linux) {
            prepareNetworking()
            installLinOxCommands()
        }

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

            val output = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(8192)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            synchronized(output) { output.append(buffer, 0, n) }
                        }
                    }
                }
            }.apply {
                name = "LinOx-command-output"
                isDaemon = true
                start()
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                readerThread.join(2000)
                CommandResult(
                    synchronized(output) { output.toString() } + "\n[LinOx] command timed out",
                    124
                )
            } else {
                readerThread.join(2000)
                CommandResult(
                    synchronized(output) { output.toString() },
                    process.exitValue()
                )
            }
        } catch (e: Exception) {
            CommandResult("[LinOx] ${e.javaClass.simpleName}: ${e.message}", 1)
        }
    }

    private fun baseProotArgs(): List<String> = buildList {
        addAll(listOf(
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", activeRootfsFile.absolutePath
        ))
        listOf("/dev", "/proc", "/sys").forEach { mount ->
            addAll(listOf("-b", mount))
        }
        addAll(listOf("-b", "/system/bin:/android-bin"))
        addAll(listOf("-b", home.absolutePath + ":/root"))
        addAll(listOf("-b", tmp.absolutePath + ":/tmp"))
        addAll(listOf("-w", "/root"))
    }

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

    private fun createSafeSymlink(base: File, target: File, linkName: String, entryName: String) {
        require(linkName.isNotEmpty()) { "Empty symlink target: $entryName" }
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

    private fun isAllowedRootfsPath(candidate: File): Boolean {
        val files = context.filesDir.canonicalFile
        val path = candidate.canonicalFile.path
        return path == files.path ||
            path.startsWith(files.path + File.separator)
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

    fun architecture(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    fun isArm64Device(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun storageUsedBytes(): Long {
        fun dirSize(file: File): Long =
            if (file.isFile) file.length()
            else file.listFiles()?.sumOf(::dirSize) ?: 0L
        return dirSize(runtimeDir) + dirSize(File(context.filesDir, "linox-distros"))
    }

    data class CommandResult(val output: String, val exitCode: Int)
}

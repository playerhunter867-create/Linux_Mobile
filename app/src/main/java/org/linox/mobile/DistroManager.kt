package org.linox.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Linux Mobile 0.9 distribution manager.
 *
 * Images are fetched from public OCI/Docker registries, resolved to linux/arm64,
 * verified by the OCI SHA-256 digest, and materialised as a persistent rootfs.
 *
 * Nothing here is a second kernel: Android supplies the kernel, PRoot supplies
 * the rootless Linux userspace view.
 */
class DistroManager(
    private val context: Context,
    private val runtime: LinuxRuntime
) {
    data class Distro(
        val id: String,
        val title: String,
        val image: String,
        val description: String,
        val packageManager: String,
        val category: String = "Linux"
    )

    data class Progress(
        val message: String,
        val downloaded: Long = 0,
        val total: Long = -1
    )

    companion object {
        private const val CONNECT_TIMEOUT = 20_000
        private const val READ_TIMEOUT = 120_000
        private const val USER_AGENT = "LinOx-Mobile/0.9 Android"

        private const val ACCEPT_MANIFEST =
            "application/vnd.oci.image.index.v1+json," +
            "application/vnd.docker.distribution.manifest.list.v2+json," +
            "application/vnd.oci.image.manifest.v1+json," +
            "application/vnd.docker.distribution.manifest.v2+json"

        /**
         * All entries are userspace images. They are downloaded on demand;
         * the APK does not contain multi-hundred-MB root filesystems.
         */
        val CATALOG = listOf(
            Distro(
                "ubuntu2404", "Ubuntu 24.04 LTS", "ubuntu:24.04",
                "Stable Ubuntu LTS development environment", "apt", "Debian family"
            ),
            Distro(
                "debian12", "Debian 12 Bookworm", "debian:bookworm-slim",
                "Minimal stable Debian userspace", "apt", "Debian family"
            ),
            Distro(
                "alpine322", "Alpine Linux 3.22", "alpine:3.22",
                "Very small musl-based Linux", "apk", "Minimal"
            ),
            Distro(
                "fedora44", "Fedora 44", "fedora:44",
                "Modern Fedora developer userspace", "dnf", "RPM family"
            ),
            Distro(
                "archlinux", "Arch Linux ARM64", "archlinux:latest",
                "Rolling-release Arch userspace", "pacman", "Rolling"
            ),
            Distro(
                "kali", "Kali Linux Rolling", "kalilinux/kali-rolling:latest",
                "Security-testing userspace; install only the tools you need", "apt", "Security"
            ),
            Distro(
                "rocky10", "Rocky Linux 10", "rockylinux:10",
                "Enterprise-compatible RHEL-family userspace", "dnf", "RPM family"
            ),
            Distro(
                "opensuse156", "openSUSE Leap 15.6", "opensuse/leap:15.6",
                "Stable openSUSE userspace", "zypper", "RPM family"
            )
        )
    }

    private val distroDir = File(context.filesDir, "linox-distros").apply { mkdirs() }
    private val cacheDir = File(distroDir, "cache").apply { mkdirs() }

    fun installed(): List<Distro> = CATALOG.filter(::isInstalled)

    fun isInstalled(distro: Distro): Boolean {
        val root = File(distroDir, "${distro.id}/rootfs")
        return root.isDirectory &&
            (File(root, "bin/sh").isFile || File(root, "usr/bin/sh").isFile) &&
            File(root, "etc").isDirectory
    }

    fun install(distro: Distro, onProgress: (Progress) -> Unit = {}) {
        val ref = parseImage(distro.image)
        onProgress(Progress("Connecting to ${ref.registry}/${ref.repository}:${ref.tag}…"))
        val token = bearerToken(ref)

        val manifestOrIndex = getJson(
            "https://${ref.registry}/v2/${ref.repository}/manifests/${ref.tag}",
            token,
            ACCEPT_MANIFEST
        )

        val manifest = if (manifestOrIndex.has("manifests")) {
            val digest = selectArm64(manifestOrIndex.getJSONArray("manifests"))
            onProgress(Progress("Selected linux/arm64 image $digest"))
            getJson(
                "https://${ref.registry}/v2/${ref.repository}/manifests/$digest",
                token,
                ACCEPT_MANIFEST
            )
        } else {
            manifestOrIndex
        }

        val layers = manifest.optJSONArray("layers")
            ?: error("Registry returned an image without layers")

        val staging = File(distroDir, "${distro.id}.new")
        if (staging.exists()) staging.deleteRecursively()
        val root = File(staging, "rootfs").apply { mkdirs() }

        try {
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                val digest = layer.getString("digest")
                val mediaType = layer.optString("mediaType")
                onProgress(Progress("Downloading layer ${i + 1}/${layers.length()}…"))
                val archive = downloadBlob(ref, token, digest, mediaType, onProgress)
                try {
                    onProgress(Progress("Applying layer ${i + 1}/${layers.length()}…"))
                    extractLayer(archive, root)
                } finally {
                    // Cache files are intentionally retained for reinstall/resume.
                }
            }

            validateRootfs(root)

            File(staging, "metadata.json").writeText(
                JSONObject()
                    .put("schema", 1)
                    .put("id", distro.id)
                    .put("title", distro.title)
                    .put("image", distro.image)
                    .put("packageManager", distro.packageManager)
                    .put("installedAt", System.currentTimeMillis())
                    .toString()
            )

            val finalDir = File(distroDir, distro.id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            check(staging.renameTo(finalDir)) { "Could not activate ${distro.title}" }

            runtime.activateRootfs(File(finalDir, "rootfs"))
            onProgress(Progress("${distro.title} installed. Bootstrapping developer tools…"))

            runCatching {
                runtime.bootstrapDevelopmentTools(distro.packageManager)
            }.onFailure {
                onProgress(
                    Progress(
                        "${distro.title} is installed, but tool bootstrap failed: ${it.message}"
                    )
                )
            }

            onProgress(Progress("${distro.title} is ready"))
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    fun activate(distro: Distro) {
        val root = File(distroDir, "${distro.id}/rootfs")
        require(isInstalled(distro)) { "${distro.title} is not installed" }
        runtime.activateRootfs(root)
    }

    fun remove(distro: Distro) {
        val target = File(distroDir, distro.id).canonicalFile
        val active = runtime.activeRootfs().canonicalFile
        File(distroDir, distro.id).deleteRecursively()
        if (active.path == target.path || active.path.startsWith(target.path + File.separator)) {
            runtime.resetToDefaultRootfs()
        }
    }

    private data class Ref(
        val registry: String,
        val repository: String,
        val tag: String
    )

    private fun parseImage(image: String): Ref {
        val slash = image.indexOf('/')
        val first = if (slash >= 0) image.substring(0, slash) else ""
        val registry = if (
            first.contains('.') || first.contains(':') || first == "localhost"
        ) first else "registry-1.docker.io"

        val withoutRegistry = if (registry == "registry-1.docker.io") {
            image
        } else {
            image.substring(registry.length + 1)
        }

        val parts = withoutRegistry.split(":", limit = 2)
        val name = parts[0]
        val tag = parts.getOrElse(1) { "latest" }
        val repo = if (registry == "registry-1.docker.io" && '/' !in name) {
            "library/$name"
        } else name

        return Ref(registry, repo, tag)
    }

    private fun bearerToken(ref: Ref): String {
        val url = "https://${ref.registry}/v2/${ref.repository}/manifests/${ref.tag}"
        val c = open(url, null, ACCEPT_MANIFEST)
        return try {
            val code = c.responseCode
            if (code in 200..299) return ""

            val challenge = c.getHeaderField("WWW-Authenticate")
                ?: error("Registry authentication failed: HTTP $code")

            val realm = Regex("""realm="([^"]+)"""")
                .find(challenge)?.groupValues?.get(1)
                ?: error("Docker auth realm missing")
            val service = Regex("""service="([^"]+)"""")
                .find(challenge)?.groupValues?.get(1)
                ?: "registry.docker.io"
            val scope = Regex("""scope="([^"]+)"""")
                .find(challenge)?.groupValues?.get(1)
                ?: "repository:${ref.repository}:pull"

            val authUrl =
                "$realm?service=${URLEncoder.encode(service, "UTF-8")}" +
                    "&scope=${URLEncoder.encode(scope, "UTF-8")}"

            val auth = open(authUrl, null, "application/json")
            try {
                require(auth.responseCode in 200..299) {
                    "Registry token request failed: HTTP ${auth.responseCode}"
                }
                auth.inputStream.bufferedReader().use { reader ->
                    JSONObject(reader.readText()).optString("token")
                        .ifBlank { error("Docker registry returned an empty token") }
                }
            } finally {
                auth.disconnect()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun getJson(url: String, token: String, accept: String): JSONObject {
        val c = request(url, token, accept)
        return try {
            c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            c.disconnect()
        }
    }

    private fun request(url: String, token: String?, accept: String): HttpURLConnection {
        val c = open(url, token, accept)
        val code = c.responseCode
        if (code !in 200..299) {
            val errorBody = runCatching {
                c.errorStream?.bufferedReader()?.readText()?.take(500)
            }.getOrNull()
            c.disconnect()
            error("HTTP $code for $url${if (errorBody.isNullOrBlank()) "" else ": $errorBody"}")
        }
        return c
    }

    private fun open(
        url: String,
        token: String?,
        accept: String
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT
        c.readTimeout = READ_TIMEOUT
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", USER_AGENT)
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun selectArm64(manifests: JSONArray): String {
        for (i in 0 until manifests.length()) {
            val entry = manifests.getJSONObject(i)
            val p = entry.optJSONObject("platform") ?: continue
            if (
                p.optString("os") == "linux" &&
                p.optString("architecture") == "arm64"
            ) {
                return entry.getString("digest")
            }
        }
        error("No linux/arm64 image found. This LinOx build targets ARM64 Android.")
    }

    private fun downloadBlob(
        ref: Ref,
        token: String,
        digest: String,
        mediaType: String,
        progress: (Progress) -> Unit
    ): File {
        val safe = digest.replace(':', '-')
        val target = File(cacheDir, safe)
        val part = File(cacheDir, "$safe.part")
        val expected = digest.substringAfter(':')
        if (target.isFile && verifySha256(target, expected)) return target
        if (target.exists()) target.delete()

        if (
            mediaType.isNotBlank() &&
            !mediaType.contains("gzip", ignoreCase = true) &&
            !mediaType.contains("zstd", ignoreCase = true) &&
            !mediaType.contains("tar", ignoreCase = true)
        ) {
            error("Unsupported OCI layer media type: $mediaType")
        }

        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                if (part.exists()) part.delete()
                val c = request(
                    "https://${ref.registry}/v2/${ref.repository}/blobs/$digest",
                    token,
                    "application/octet-stream"
                )
                try {
                    val total = c.contentLengthLong
                    var done = 0L
                    val buffer = ByteArray(128 * 1024)

                    c.inputStream.use { input ->
                        FileOutputStream(part).use { out ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                progress(
                                    Progress(
                                        "Downloading ${digest.take(19)}…",
                                        done,
                                        total
                                    )
                                )
                            }
                            out.fd.sync()
                        }
                    }
                } finally {
                    c.disconnect()
                }

                require(verifySha256(part, expected)) {
                    "SHA-256 mismatch for $digest"
                }
                check(part.renameTo(target)) { "Could not finalize downloaded layer" }
                return target
            } catch (t: Throwable) {
                lastError = t
                if (attempt < 2) progress(Progress("Retrying download…"))
            }
        }

        throw IllegalStateException(
            "Download failed for $digest after 3 attempts: ${lastError?.message}",
            lastError
        )
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (!file.isFile) return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == expected
    }

    /**
     * OCI whiteouts are handled before materialising the normal entry.
     * This matters for multi-layer Docker images where later layers delete
     * files from earlier layers.
     */
    private fun extractLayer(archive: File, root: File) {
        archive.inputStream().buffered().use { raw ->
            val stream: InputStream = when {
                isGzip(archive) -> GZIPInputStream(raw, 128 * 1024)
                isZstd(archive) -> ZstdCompressorInputStream(raw)
                else -> raw
            }

            TarArchiveInputStream(stream).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val e = entry
                    val name = normalizeArchiveName(e.name)

                    if (name.isEmpty()) {
                        entry = tar.nextTarEntry
                        continue
                    }

                    val baseName = name.substringAfterLast('/')
                    val parentName = name.substringBeforeLast('/', "")
                    val target = safeTarget(root, name)

                    if (baseName.startsWith(".wh.")) {
                        val marker = baseName.removePrefix(".wh.")
                        if (marker == ".wh..opq") {
                            target.parentFile?.listFiles()?.forEach { deleteAny(it) }
                        } else {
                            val victim = if (parentName.isEmpty()) {
                                File(root, marker)
                            } else {
                                File(root, "$parentName/$marker")
                            }
                            deleteAny(victim)
                        }
                        entry = tar.nextTarEntry
                        continue
                    }

                    deleteAny(target)
                    target.parentFile?.mkdirs()

                    when {
                        e.isDirectory -> target.mkdirs()

                        e.isSymbolicLink -> {
                            require(e.linkName.isNotBlank()) {
                                "Empty symlink target: $name"
                            }
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(e.linkName)
                            )
                        }

                        e.isLink -> {
                            // OCI layers may encode hardlinks. A byte-for-byte copy
                            // is safer than depending on hard-link support across
                            // Android filesystems and preserves the executable image.
                            val linkName = normalizeArchiveName(e.linkName)
                            val source = safeTarget(root, linkName)
                            require(source.isFile) {
                                "Hardlink source is missing: ${e.linkName}"
                            }
                            source.inputStream().use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                            applyMode(target, e.mode)
                        }

                        e.isFile -> {
                            FileOutputStream(target).use { tar.copyTo(it) }
                            applyMode(target, e.mode)
                        }
                    }

                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun validateRootfs(root: File) {
        require(root.isDirectory) { "Rootfs directory was not created" }
        require(
            File(root, "bin/sh").isFile || File(root, "usr/bin/sh").isFile
        ) { "Image did not contain /bin/sh or /usr/bin/sh" }
        require(File(root, "etc").isDirectory) { "Image did not contain /etc" }
    }

    private fun normalizeArchiveName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Unsafe OCI path: $raw" }
        return parts.joinToString("/")
    }

    private fun safeTarget(base: File, name: String): File {
        val root = base.canonicalFile
        val target = File(root, name).canonicalFile
        require(
            target.path == root.path ||
                target.path.startsWith(root.path + File.separator)
        ) { "Unsafe OCI path: $name" }
        return target
    }

    private fun deleteAny(file: File) {
        if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable((mode and 0b100_100_100) != 0, false)
        file.setWritable((mode and 0b010_010_010) != 0, false)
        file.setExecutable((mode and 0b001_001_001) != 0, false)
    }

    private fun isGzip(file: File): Boolean =
        file.inputStream().use { it.read() == 0x1f && it.read() == 0x8b }

    private fun isZstd(file: File): Boolean =
        file.inputStream().use {
            it.read() == 0x28 && it.read() == 0xB5 &&
                it.read() == 0x2F && it.read() == 0xFD
        }
}

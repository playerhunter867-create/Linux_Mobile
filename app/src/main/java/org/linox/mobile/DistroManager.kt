package org.linox.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Downloads public ARM64 Linux userspaces from Docker Hub/OCI registry.
 * Images are cached and every downloaded layer is SHA-256 verified.
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
        val packageManager: String
    )

    data class Progress(
        val message: String,
        val downloaded: Long = 0,
        val total: Long = -1
    )

    companion object {
        private const val ACCEPT_MANIFEST =
            "application/vnd.oci.image.index.v1+json," +
            "application/vnd.docker.distribution.manifest.list.v2+json," +
            "application/vnd.oci.image.manifest.v1+json," +
            "application/vnd.docker.distribution.manifest.v2+json"

        val CATALOG = listOf(
            Distro("ubuntu2404", "Ubuntu 24.04 LTS", "ubuntu:24.04",
                "Ubuntu LTS development environment", "apt"),
            Distro("debian12", "Debian 12", "debian:12",
                "Stable Debian userspace", "apt"),
            Distro("alpine323", "Alpine Linux 3.23", "alpine:3.23",
                "Small and fast Linux", "apk"),
            Distro("fedora44", "Fedora 44", "fedora:44",
                "Fedora development environment", "dnf"),
            Distro("archarm", "Arch Linux ARM64", "danhunsaker/archlinuxarm:20260517",
                "ARM64 rolling-release Arch environment", "pacman"),
            Distro("kali", "Kali Linux Rolling", "kalilinux/kali-rolling:latest",
                "Security-focused Linux userspace", "apt"),
            Distro("rocky10", "Rocky Linux 10", "rockylinux:10",
                "RHEL-compatible Linux userspace", "dnf"),
            Distro("opensuse", "openSUSE Leap", "opensuse/leap:15",
                "Stable openSUSE userspace", "zypper")
        )
    }

    private val distroDir = File(context.filesDir, "linox-distros")
    private val cacheDir = File(distroDir, "cache")

    init {
        distroDir.mkdirs()
        cacheDir.mkdirs()
    }

    fun installed(): List<Distro> =
        CATALOG.filter { isInstalled(it) }

    fun isInstalled(distro: Distro): Boolean {
        val root = File(distroDir, "${distro.id}/rootfs")
        return (File(root, "bin/sh").isFile || File(root, "usr/bin/sh").isFile) &&
            File(root, "etc").isDirectory
    }

    fun install(distro: Distro, onProgress: (Progress) -> Unit = {}) {
        val ref = parseImage(distro.image)
        onProgress(Progress("Connecting to ${ref.registry}…"))
        val token = bearerToken(ref)

        val index = getJson(
            "https://${ref.registry}/v2/${ref.repository}/manifests/${ref.tag}",
            token,
            ACCEPT_MANIFEST
        )

        val manifest = if (index.has("manifests")) {
            val digest = selectArm64(index.getJSONArray("manifests"))
            getJson(
                "https://${ref.registry}/v2/${ref.repository}/manifests/$digest",
                token,
                ACCEPT_MANIFEST
            )
        } else index

        val layers = manifest.getJSONArray("layers")
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
                onProgress(Progress("Extracting layer ${i + 1}/${layers.length()}…"))
                extractLayer(archive, root)
                archive.delete()
            }

            require(
                File(root, "bin/sh").isFile || File(root, "usr/bin/sh").isFile
            ) { "Image did not contain /bin/sh or /usr/bin/sh" }
            require(File(root, "etc").isDirectory) { "Image did not contain /etc" }

            File(staging, "metadata.json").writeText(
                JSONObject()
                    .put("id", distro.id)
                    .put("image", distro.image)
                    .put("packageManager", distro.packageManager)
                    .toString()
            )

            val finalDir = File(distroDir, distro.id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            check(staging.renameTo(finalDir)) {
                "Could not activate ${distro.title}"
            }

            runtime.activateRootfs(File(finalDir, "rootfs"))
            onProgress(Progress("${distro.title} installed — installing Python, nano, Git and network tools…"))
            runCatching {
                runtime.bootstrapDevelopmentTools(distro.packageManager)
            }.onFailure {
                onProgress(Progress("${distro.title} installed, but automatic tools setup failed: ${it.message}"))
            }
            onProgress(Progress("${distro.title} installed and ready"))
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    fun activate(distro: Distro) {
        val root = File(distroDir, "${distro.id}/rootfs")
        require(isInstalled(distro)) { "Distribution is not installed" }
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

        val withoutRegistry =
            if (registry == "registry-1.docker.io") image
            else image.substring(registry.length + 1)

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
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("Accept", ACCEPT_MANIFEST)

        return try {
            val code = c.responseCode
            if (code in 200..299) return ""
            val challenge = c.getHeaderField("WWW-Authenticate")
                ?: error("Registry authentication failed: HTTP $code")
            val realm = Regex("""realm="([^"]+)"""").find(challenge)?.groupValues?.get(1)
                ?: error("Docker auth realm missing")
            val service = Regex("""service="([^"]+)"""").find(challenge)?.groupValues?.get(1)
                ?: "registry.docker.io"
            val scope = Regex("""scope="([^"]+)"""").find(challenge)?.groupValues?.get(1)
                ?: "repository:${ref.repository}:pull"

            val authUrl = URL(
                "$realm?service=${URLEncoder.encode(service, "UTF-8")}" +
                    "&scope=${URLEncoder.encode(scope, "UTF-8")}"
            )
            val auth = authUrl.openConnection() as HttpURLConnection
            auth.connectTimeout = 20000
            auth.readTimeout = 30000
            try {
                auth.inputStream.bufferedReader().use { reader ->
                    JSONObject(reader.readText()).optString("token")
                        .ifBlank { error("Docker registry did not return a token") }
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
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", accept)
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")

        val code = c.responseCode
        if (code !in 200..299) {
            val auth = c.getHeaderField("WWW-Authenticate")
            c.disconnect()
            error("HTTP $code for $url${if (auth != null) " ($auth)" else ""}")
        }
        return c
    }

    private fun selectArm64(manifests: JSONArray): String {
        for (i in 0 until manifests.length()) {
            val p = manifests.getJSONObject(i).optJSONObject("platform") ?: continue
            if (
                p.optString("os") == "linux" &&
                p.optString("architecture") == "arm64"
            ) {
                return manifests.getJSONObject(i).getString("digest")
            }
        }
        error("No linux/arm64 image found for this distribution")
    }

    private fun downloadBlob(
        ref: Ref,
        token: String,
        digest: String,
        mediaType: String,
        progress: (Progress) -> Unit
    ): File {
        val safe = digest.replace(":", "-")
        val target = File(cacheDir, safe)
        val expected = digest.substringAfter(':')
        if (!target.isFile || !verifySha256(target, expected)) {
            val c = request(
                "https://${ref.registry}/v2/${ref.repository}/blobs/$digest",
                token,
                "application/octet-stream"
            )
            try {
                c.inputStream.use { input ->
                    FileOutputStream(target).use { out ->
                        val total = c.contentLengthLong
                        var done = 0L
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done % (1024 * 1024) < 65536) {
                                progress(Progress("Downloading ${digest.take(18)}…", done, total))
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            require(verifySha256(target, expected)) {
                "SHA-256 mismatch for $digest"
            }
        }

        if (
            mediaType.isNotBlank() &&
            !mediaType.contains("gzip") &&
            !mediaType.contains("zstd") &&
            !mediaType.contains("tar")
        ) {
            error("Unsupported OCI layer media type: $mediaType")
        }
        return target
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (!file.isFile) return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)
    }

    private fun extractLayer(archive: File, root: File) {
        archive.inputStream().buffered().let { raw ->
            val stream: java.io.InputStream = when {
                isGzip(archive) -> GZIPInputStream(raw)
                isZstd(archive) -> ZstdCompressorInputStream(raw)
                else -> raw
            }
            TarArchiveInputStream(stream).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    val e = entry!!
                    val name = e.name.trimStart('/')
                    if (name.isBlank()) {
                        entry = tar.nextTarEntry
                        continue
                    }
                    require(
                        !name.contains("../") && name != ".." && !name.startsWith("../")
                    ) { "Unsafe OCI path: $name" }

                    val target = File(root, name).canonicalFile
                    require(
                        target.path == root.canonicalPath ||
                            target.path.startsWith(root.canonicalPath + File.separator)
                    ) { "Unsafe OCI path: $name" }

                    target.parentFile?.mkdirs()
                    when {
                        e.isDirectory -> target.mkdirs()
                        e.isSymbolicLink -> {
                            target.deleteRecursively()
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(e.linkName)
                            )
                        }
                        e.isFile -> {
                            FileOutputStream(target).use { tar.copyTo(it) }
                            target.setExecutable(
                                (e.mode and 0b001_001_001) != 0,
                                false
                            )
                        }
                    }

                    if (name.substringAfterLast('/').startsWith(".wh.")) {
                        val base = name.substringBeforeLast('/', "")
                        val marker = name.substringAfterLast('/').removePrefix(".wh.")
                        if (marker == ".wh..opq") {
                            target.parentFile?.listFiles()?.forEach { it.deleteRecursively() }
                        } else {
                            File(root, if (base.isEmpty()) marker else "$base/$marker")
                                .deleteRecursively()
                        }
                        target.delete()
                    }

                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun isGzip(file: File): Boolean =
        file.inputStream().use { it.read() == 0x1f && it.read() == 0x8b }

    private fun isZstd(file: File): Boolean =
        file.inputStream().use {
            val b0 = it.read()
            val b1 = it.read()
            val b2 = it.read()
            val b3 = it.read()
            b0 == 0x28 && b1 == 0xB5 && b2 == 0x2F && b3 == 0xFD
        }

}

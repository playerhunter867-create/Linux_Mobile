package org.linox.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Downloads public OCI/Docker images, selects Linux ARM64 when a manifest list
 * is supplied, verifies every blob and materialises it as a rootfs.
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
        /**
         * ARM64 choices. These are deliberately limited to images that are
         * practical in a PRoot-style environment.
         */
        val CATALOG = listOf(
            Distro(
                "ubuntu2404", "Ubuntu 24.04 LTS", "ubuntu:24.04",
                "Ubuntu LTS — general development", "apt"
            ),
            Distro(
                "debian12", "Debian 12", "debian:12",
                "Stable Debian — lightweight general Linux", "apt"
            ),
            Distro(
                "alpine323", "Alpine Linux 3.23", "alpine:3.23",
                "Very small Linux userspace", "apk"
            ),
            Distro(
                "fedora44", "Fedora 44", "fedora:44",
                "Modern Fedora development environment", "dnf"
            ),
            Distro(
                "opensuse15", "openSUSE Leap 15", "opensuse/leap:15",
                "RPM-based openSUSE userspace", "zypper"
            ),
            Distro(
                "rocky10", "Rocky Linux 10", "rockylinux:10",
                "RHEL-compatible development userspace", "dnf"
            ),
            Distro(
                "archlinuxarm", "Arch Linux ARM", "danhunsaker/archlinuxarm:latest",
                "Rolling Arch userspace for ARM64", "pacman"
            ),
            Distro(
                "kali", "Kali Linux Rolling", "kalilinux/kali-rolling:latest",
                "Security-testing Linux userspace", "apt"
            )
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

    fun isInstalled(distro: Distro): Boolean =
        File(distroDir, "${distro.id}/rootfs").let {
            it.isDirectory &&
                (File(it, "bin/sh").isFile || File(it, "usr/bin/sh").isFile)
        }

    fun install(distro: Distro, onProgress: (Progress) -> Unit = {}) {
        val ref = parseImage(distro.image)

        onProgress(Progress("Authorising Docker registry…"))
        val token = bearerToken(ref.repository)

        onProgress(Progress("Resolving ${distro.title}…"))
        val manifestJson = getJson(
            "https://${ref.registry}/v2/${ref.repository}/manifests/${ref.tag}",
            token,
            ACCEPT_MANIFEST
        )

        val manifest = if (
            manifestJson.has("manifests") ||
            manifestJson.optString("mediaType").contains("manifest.list") ||
            manifestJson.optString("mediaType").contains("image.index")
        ) {
            val digest = selectArm64(manifestJson.getJSONArray("manifests"))
            getJson(
                "https://${ref.registry}/v2/${ref.repository}/manifests/$digest",
                token,
                ACCEPT_MANIFEST
            )
        } else {
            manifestJson
        }

        val layers = manifest.optJSONArray("layers")
            ?: error("Image manifest has no layers")

        val staging = File(distroDir, "${distro.id}.new")
        if (staging.exists()) staging.deleteRecursively()
        val root = File(staging, "rootfs").apply { mkdirs() }

        try {
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                val digest = layer.getString("digest")
                val mediaType = layer.optString("mediaType")

                onProgress(Progress("Downloading ${i + 1}/${layers.length()}…"))
                val archive = downloadBlob(ref, token, digest, mediaType, onProgress)

                onProgress(Progress("Extracting ${i + 1}/${layers.length()}…"))
                extractLayer(archive, root)
                archive.delete()
            }

            require(
                File(root, "bin/sh").isFile ||
                File(root, "usr/bin/sh").isFile
            ) { "Image did not contain a usable shell" }

            require(File(root, "etc").isDirectory) {
                "Image did not contain /etc"
            }

            File(staging, "metadata.json").writeText(
                JSONObject()
                    .put("id", distro.id)
                    .put("title", distro.title)
                    .put("image", distro.image)
                    .put("packageManager", distro.packageManager)
                    .put("version", "1.0.0")
                    .toString()
            )

            val finalDir = File(distroDir, distro.id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            check(staging.renameTo(finalDir)) {
                "Could not activate ${distro.title}"
            }

            runtime.activateRootfs(File(finalDir, "rootfs"))
            onProgress(Progress("${distro.title} installed and active"))
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    fun activate(distro: Distro) {
        val root = File(distroDir, "${distro.id}/rootfs")
        require(root.isDirectory) { "Distribution is not installed" }
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
        val parts = image.split(":", limit = 2)
        val name = parts[0]
        val tag = parts.getOrElse(1) { "latest" }
        val repo = if ('/' in name) name else "library/$name"
        return Ref("registry-1.docker.io", repo, tag)
    }

    private fun bearerToken(repository: String): String {
        val challenge = try {
            val connection = request(
                "https://registry-1.docker.io/v2/$repository/manifests/latest",
                null,
                "application/json"
            )
            connection.disconnect()
            ""
        } catch (e: HttpException) {
            e.wwwAuthenticate ?: throw e
        }

        if (challenge.isEmpty()) return ""

        val realm = Regex("realm=\"([^\"]+)\"")
            .find(challenge)?.groupValues?.get(1)
            ?: error("Docker auth realm missing")

        val service = Regex("service=\"([^\"]+)\"")
            .find(challenge)?.groupValues?.get(1)
            ?: "registry.docker.io"

        val scope = Regex("scope=\"([^\"]+)\"")
            .find(challenge)?.groupValues?.get(1)
            ?: "repository:$repository:pull"

        val authUrl = URL(
            "$realm?service=${java.net.URLEncoder.encode(service, "UTF-8")}" +
                "&scope=${java.net.URLEncoder.encode(scope, "UTF-8")}"
        )

        val connection = authUrl.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.inputStream.bufferedReader().use { reader ->
                JSONObject(reader.readText()).getString("token")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String, token: String, accept: String): JSONObject {
        val connection = request(url, token, accept)
        return try {
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        url: String,
        token: String?,
        accept: String
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 180_000
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "LinOx-Mobile/1.0.0")

        if (!token.isNullOrBlank()) {
            c.setRequestProperty("Authorization", "Bearer $token")
        }

        val code = c.responseCode
        if (code !in 200..299) {
            val auth = c.getHeaderField("WWW-Authenticate")
            c.disconnect()
            throw HttpException("HTTP $code for $url", auth)
        }

        return c
    }

    private fun selectArm64(manifests: JSONArray): String {
        for (i in 0 until manifests.length()) {
            val m = manifests.getJSONObject(i)
            val p = m.optJSONObject("platform") ?: continue
            if (
                p.optString("os") == "linux" &&
                p.optString("architecture") == "arm64"
            ) {
                return m.getString("digest")
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
                        val buffer = ByteArray(64 * 1024)

                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
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
            !mediaType.contains("tar")
        ) {
            error("Unsupported OCI layer: $mediaType")
        }

        return target
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (!file.isFile) return false

        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }

        return md.digest().joinToString("") { "%02x".format(it) }
            .equals(expected, true)
    }

    private fun extractLayer(archive: File, root: File) {
        archive.inputStream().buffered().let { raw ->
            val stream = if (isGzip(archive)) GZIPInputStream(raw) else raw

            TarArchiveInputStream(stream).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry

                while (entry != null) {
                    val name = entry!!.name.trimStart('/')

                    if (name.startsWith("./") || name.isBlank()) {
                        entry = tar.nextTarEntry
                        continue
                    }

                    require(
                        !name.contains("../") &&
                        name != ".." &&
                        !name.startsWith("../")
                    ) { "Unsafe OCI path: $name" }

                    val target = File(root, name).canonicalFile
                    require(
                        target.path == root.canonicalPath ||
                        target.path.startsWith(root.canonicalPath + File.separator)
                    ) { "Unsafe path: $name" }

                    val leaf = name.substringAfterLast('/')

                    // OCI/Docker whiteout entries represent deletion from a
                    // lower layer and must be processed before removing the
                    // whiteout file itself.
                    if (leaf.startsWith(".wh.")) {
                        val parent = target.parentFile ?: root
                        val victim = leaf.removePrefix(".wh.")
                        if (victim == ".wh..opq") {
                            parent.listFiles()?.forEach { it.deleteRecursively() }
                        } else {
                            File(parent, victim).deleteRecursively()
                        }
                        target.deleteRecursively()
                        entry = tar.nextTarEntry
                        continue
                    }

                    target.parentFile?.mkdirs()

                    when {
                        entry!!.isDirectory -> target.mkdirs()

                        entry!!.isSymbolicLink -> {
                            target.deleteRecursively()
                            Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(entry!!.linkName)
                            )
                        }

                        entry!!.isFile -> {
                            target.deleteRecursively()
                            FileOutputStream(target).use { tar.copyTo(it) }
                            target.setExecutable(
                                (entry!!.mode and 0b001_001_001) != 0,
                                false
                            )
                        }
                    }

                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun isGzip(file: File): Boolean =
        file.inputStream().use {
            it.read() == 0x1f && it.read() == 0x8b
        }

    private class HttpException(
        message: String,
        val wwwAuthenticate: String?
    ) : Exception(message)

    companion object {
        private const val ACCEPT_MANIFEST =
            "application/vnd.oci.image.index.v1+json," +
            "application/vnd.docker.distribution.manifest.list.v2+json," +
            "application/vnd.oci.image.manifest.v1+json," +
            "application/vnd.docker.distribution.manifest.v2+json"
    }
}

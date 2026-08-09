package org.linox.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Small OCI/Docker Hub client for LinOx distributions.
 * It pulls public images directly, selects linux/arm64, verifies every blob
 * by its sha256 digest, then materialises the layers into a rootfs.
 */
class DistroManager(private val context: Context, private val runtime: LinuxRuntime) {
    data class Distro(val id: String, val title: String, val image: String, val description: String)
    data class Progress(val message: String, val downloaded: Long = 0, val total: Long = -1)

    companion object {
        val CATALOG = listOf(
            Distro("ubuntu2404", "Ubuntu 24.04 LTS", "ubuntu:24.04", "Ubuntu LTS development environment"),
            Distro("debian12", "Debian 12", "debian:12", "Stable Debian development environment")
        )
    }

    private val distroDir = File(context.filesDir, "linox-distros")
    private val cacheDir = File(distroDir, "cache")

    init { distroDir.mkdirs(); cacheDir.mkdirs() }

    fun installed(): List<Distro> = CATALOG.filter { File(distroDir, "${it.id}/rootfs/bin/sh").isFile }
    fun isInstalled(distro: Distro) = File(distroDir, "${distro.id}/rootfs/bin/sh").isFile

    fun install(distro: Distro, onProgress: (Progress) -> Unit = {}) {
        val ref = parseImage(distro.image)
        val token = bearerToken(ref.repository)
        onProgress(Progress("Resolving ${distro.title}…"))
        val index = getJson("https://${ref.registry}/v2/${ref.repository}/manifests/${ref.tag}", token,
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json")
        val manifest = if (index.optString("mediaType").contains("manifest.list") || index.has("manifests")) {
            val digest = selectArm64(index.getJSONArray("manifests"))
            getJson("https://${ref.registry}/v2/${ref.repository}/manifests/$digest", token,
                "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json")
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
            require(File(root, "bin/sh").isFile || File(root, "usr/bin/sh").isFile) { "Image did not contain a shell" }
            File(staging, "metadata.json").writeText(JSONObject().put("id", distro.id).put("image", distro.image).toString())
            val finalDir = File(distroDir, distro.id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            check(staging.renameTo(finalDir)) { "Could not activate ${distro.title}" }
            runtime.activateRootfs(File(finalDir, "rootfs"))
            onProgress(Progress("${distro.title} installed"))
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
        File(distroDir, distro.id).deleteRecursively()
        if (runtime.activeRootfs().canonicalFile.path.startsWith(File(distroDir, distro.id).canonicalPath)) {
            runtime.resetToDefaultRootfs()
        }
    }

    private data class Ref(val registry: String, val repository: String, val tag: String)
    private fun parseImage(image: String): Ref {
        val parts = image.split(":", limit = 2)
        val name = parts[0]
        val tag = parts.getOrElse(1) { "latest" }
        val repo = if ('/' in name) name else "library/$name"
        return Ref("registry-1.docker.io", repo, tag)
    }

    private fun bearerToken(repository: String): String {
        val challenge = try {
            request("https://registry-1.docker.io/v2/$repository/manifests/latest", null, "application/json").use { it }
            ""
        } catch (e: HttpException) {
            e.wwwAuthenticate ?: throw e
        }
        if (challenge.isEmpty()) return ""
        val realm = Regex("realm=\\\"([^\\\"]+)\\\"").find(challenge)?.groupValues?.get(1) ?: error("Docker auth realm missing")
        val service = Regex("service=\\\"([^\\\"]+)\\\"").find(challenge)?.groupValues?.get(1) ?: "registry.docker.io"
        val scope = Regex("scope=\\\"([^\\\"]+)\\\"").find(challenge)?.groupValues?.get(1) ?: "repository:$repository:pull"
        return URL("$realm?service=${java.net.URLEncoder.encode(service, "UTF-8")}&scope=${java.net.URLEncoder.encode(scope, "UTF-8")}")
            .openConnection().let { c ->
                (c as HttpURLConnection).apply { requestMethod = "GET" }.inputStream.bufferedReader().use { JSONObject(it.readText()).getString("token") }
            }
    }

    private fun getJson(url: String, token: String, accept: String): JSONObject = request(url, token, accept).use { JSONObject(it.inputStream.bufferedReader().readText()) }

    private fun request(url: String, token: String?, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 120_000; c.requestMethod = "GET"
        c.setRequestProperty("Accept", accept)
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
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
            val m = manifests.getJSONObject(i); val p = m.optJSONObject("platform") ?: continue
            if (p.optString("os") == "linux" && p.optString("architecture") == "arm64") return m.getString("digest")
        }
        error("No linux/arm64 image found")
    }

    private fun downloadBlob(ref: Ref, token: String, digest: String, mediaType: String, progress: (Progress) -> Unit): File {
        val safe = digest.replace(":", "-")
        val target = File(cacheDir, safe)
        if (!target.isFile || !verifySha256(target, digest.substringAfter(':'))) {
            val c = request("https://${ref.registry}/v2/${ref.repository}/blobs/$digest", token, "application/octet-stream")
            c.inputStream.use { input -> FileOutputStream(target).use { out ->
                val total = c.contentLengthLong; var done = 0L; val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; if (done % (1024*1024) < 65536) progress(Progress("Downloading ${digest.take(18)}…", done, total)) }
            }}
            require(verifySha256(target, digest.substringAfter(':'))) { "SHA-256 mismatch for $digest" }
        }
        if (mediaType.isNotBlank() && !mediaType.contains("gzip") && !mediaType.contains("tar")) error("Unsupported OCI layer: $mediaType")
        return target
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (!file.isFile) return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(64*1024); while (true) { val n=input.read(b); if(n<0)break; md.update(b,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)
    }

    private fun extractLayer(archive: File, root: File) {
        archive.inputStream().buffered().let { raw ->
            val stream = if (isGzip(archive)) GZIPInputStream(raw) else raw
            TarArchiveInputStream(stream).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val name = entry.name.trimStart('/')
                    if (name.startsWith("./")) { entry = tar.nextTarEntry; continue }
                    if (name.contains("../") || name == ".." || name.startsWith("../")) error("Unsafe OCI path: $name")
                    val target = File(root, name).canonicalFile
                    require(target.path == root.canonicalPath || target.path.startsWith(root.canonicalPath + File.separator)) { "Unsafe path: $name" }
                    if (name.contains('/')) target.parentFile?.mkdirs()
                    if (entry.isDirectory) target.mkdirs()
                    else if (entry.isSymbolicLink) { target.deleteRecursively(); target.parentFile?.mkdirs(); Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName)) }
                    else if (entry.isFile) { target.parentFile?.mkdirs(); FileOutputStream(target).use { tar.copyTo(it) }; target.setExecutable((entry.mode and 0b001_001_001) != 0, false) }
                    if (name.substringAfterLast('/').startsWith(".wh.")) {
                        val base = name.substringBeforeLast('/'); val victim = name.substringAfterLast('/').removePrefix(".wh.")
                        if (victim == ".wh..opq") target.parentFile?.listFiles()?.forEach { it.deleteRecursively() } else File(root, if(base.isEmpty()) victim else "$base/$victim").deleteRecursively()
                        target.delete()
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun isGzip(file: File): Boolean = file.inputStream().use { it.read() == 0x1f && it.read() == 0x8b }
    private class HttpException(message: String, val wwwAuthenticate: String?) : Exception(message)
}

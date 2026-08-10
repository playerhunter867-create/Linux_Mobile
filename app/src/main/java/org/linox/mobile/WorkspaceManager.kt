package org.linox.mobile

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * LinOx v0.9 workspace sync.
 *
 * The Linux rootfs already sees `home/workspace` as `/root/workspace` (bind mount
 * in LinuxRuntime). This manager copies files between that directory and an
 * Android folder picked through Storage Access Framework, so a project can be
 * edited from the Linux terminal/toolchain and still live somewhere the user
 * can reach from other Android apps (or vice versa).
 */
class WorkspaceManager(private val context: Context, private val runtime: LinuxRuntime) {
    data class SyncResult(val filesCopied: Int, val bytesCopied: Long, val errors: List<String>)

    fun linuxWorkspaceDir(): File = runtime.workspacePath()

    fun savedFolderUri(): Uri? {
        val saved = context.getSharedPreferences("linox", Context.MODE_PRIVATE).getString("workspace_folder", null)
        return saved?.let { Uri.parse(it) }
    }

    fun rememberFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences("linox", Context.MODE_PRIVATE).edit().putString("workspace_folder", uri.toString()).apply()
    }

    /** Copies files from the Android folder into the Linux workspace. */
    fun pullFromAndroid(treeUri: Uri, onProgress: (String) -> Unit = {}): SyncResult {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open selected folder")
        val dest = linuxWorkspaceDir()
        var count = 0; var bytes = 0L; val errors = ArrayList<String>()
        fun walk(doc: DocumentFile, target: File) {
            doc.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                if (child.isDirectory) {
                    walk(child, File(target, name).apply { mkdirs() })
                } else {
                    try {
                        val out = File(target, name)
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            out.outputStream().use { output -> bytes += input.copyTo(output) }
                        }
                        count++
                        if (count % 20 == 0) onProgress("Copied $count files…")
                    } catch (e: Exception) {
                        errors.add("$name: ${e.message}")
                    }
                }
            }
        }
        walk(root, dest)
        onProgress("Pulled $count files from Android")
        return SyncResult(count, bytes, errors)
    }

    /** Copies files from the Linux workspace back into the Android folder. */
    fun pushToAndroid(treeUri: Uri, onProgress: (String) -> Unit = {}): SyncResult {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open selected folder")
        val src = linuxWorkspaceDir()
        var count = 0; var bytes = 0L; val errors = ArrayList<String>()
        fun existingChild(dir: DocumentFile, name: String, isDir: Boolean): DocumentFile? =
            dir.listFiles().firstOrNull { it.name == name && it.isDirectory == isDir }
        fun walk(dir: File, target: DocumentFile) {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    val next = existingChild(target, child.name, true) ?: target.createDirectory(child.name)
                    if (next != null) walk(child, next) else errors.add("${child.name}: could not create folder")
                } else {
                    try {
                        val docFile = existingChild(target, child.name, false) ?: target.createFile("application/octet-stream", child.name)
                        if (docFile == null) { errors.add("${child.name}: could not create file"); return@forEach }
                        context.contentResolver.openOutputStream(docFile.uri, "wt")?.use { output ->
                            child.inputStream().use { input -> bytes += input.copyTo(output) }
                        }
                        count++
                        if (count % 20 == 0) onProgress("Copied $count files…")
                    } catch (e: Exception) {
                        errors.add("${child.name}: ${e.message}")
                    }
                }
            }
        }
        walk(src, root)
        onProgress("Pushed $count files to Android")
        return SyncResult(count, bytes, errors)
    }
}

package com.jarvis.assistant

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/** Unpacks the bundled wake-word model on first run (the zip ships inside the APK). */
object VoskModel {
    private const val MODEL_VERSION = "small-en-us-0.15"

    fun ensure(context: Context): File {
        val dir = File(context.filesDir, "vosk-model")
        val marker = File(dir, ".ready-$MODEL_VERSION")
        if (marker.exists()) return dir

        dir.deleteRecursively()
        dir.mkdirs()
        val root = dir.canonicalFile
        context.assets.open("vosk-model.zip").use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Drop the top-level "vosk-model-small-en-us-0.15/" folder.
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) continue
                    val out = File(root, relative).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) continue // zip-slip guard
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        marker.createNewFile()
        return dir
    }
}

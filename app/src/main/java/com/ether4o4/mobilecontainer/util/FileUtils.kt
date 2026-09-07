package com.ether4o4.mobilecontainer.util

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Small file helpers used across the app.
 */
object FileUtils {

    fun humanReadableSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return if (i == 0) "${bytes} B" else String.format("%.2f %s", v, units[i])
    }

    fun copyTo(src: File, dst: File): Long {
        dst.parentFile?.mkdirs()
        var total = 0L
        src.inputStream().use { input ->
            dst.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    total += n
                }
            }
        }
        return total
    }

    fun copyStream(input: InputStream, output: OutputStream): Long {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            total += n
        }
        return total
    }

    /** chmod +x via java.io.File.setExecutable. */
    fun ensureExecutable(file: File): Boolean {
        if (!file.exists()) return false
        return file.setExecutable(true, false)
    }

    fun readTail(file: File, maxLines: Int = 500): String {
        if (!file.exists()) return ""
        val lines = file.readLines()
        return if (lines.size <= maxLines) lines.joinToString("\n")
        else lines.takeLast(maxLines).joinToString("\n")
    }
}

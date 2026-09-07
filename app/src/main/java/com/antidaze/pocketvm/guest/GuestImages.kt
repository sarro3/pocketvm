package com.antidaze.pocketvm.guest

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Finds and downloads the prebuilt Android 9 guest image from GitHub releases. */
object GuestImages {

    const val REPO = "sarro3/pocketvm"
    const val ASSET_PREFIX = "pocketvm-android9"

    /** Fixed-name asset URL — uses github.com redirects, never the api.github.com host. */
    const val FIXED_ASSET_URL =
        "https://github.com/$REPO/releases/latest/download/pocketvm-android9-arm64.zip"

    /**
     * Known-good pinned image (first published android9 bundle from this
     * fork's CI). 404s until the guest workflow has run at least once — the
     * fixed-name URL above is the primary candidate.
     */
    const val PINNED_ASSET_URL =
        "https://github.com/$REPO/releases/download/android9-v1/pocketvm-android9-arm64-v1.zip"

    private val CANDIDATE_URLS = listOf(FIXED_ASSET_URL, PINNED_ASSET_URL)

    /** Returns (download URL, size) — HEAD-checks candidates, following redirects. */
    fun latestAndroidAsset(): Pair<String, Long>? {
        for (url in CANDIDATE_URLS) {
            val size = headSize(url)
            if (size != null) return url to size
        }
        return null
    }

    private fun headSize(url: String): Long? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "pocketvm")
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode in 200..299) return conn.contentLengthLong
            return null
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Streams a URL to a file with progress callbacks. */
    fun download(url: String, dest: File, onProgress: (read: Long, total: Long) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 19)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /** Extracts vmlinuz + rootfs.img.gz from the guest bundle zip. */
    fun unzip(zip: File, into: File): List<String> {
        val names = mutableListOf<String>()
        into.mkdirs()
        zip.inputStream().use { raw ->
            ZipInputStream(raw.buffered(1 shl 19)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory) {
                        File(into, e.name).outputStream().use { z.copyTo(it, 1 shl 19) }
                        names.add(e.name)
                    }
                    z.closeEntry()
                }
            }
        }
        return names
    }

    /** Decompresses rootfs.img.gz -> rootfs.img (raw ext4 for QEMU). */
    fun gunzip(src: File, dest: File) {
        GZIPInputStream(src.inputStream().buffered(1 shl 19), 1 shl 17).use { input ->
            dest.outputStream().buffered(1 shl 19).use { output ->
                input.copyTo(output, 1 shl 19)
            }
        }
    }
}

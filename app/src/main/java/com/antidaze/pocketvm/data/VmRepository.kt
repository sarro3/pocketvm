package com.antidaze.pocketvm.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * File layout on disk:
 *
 *   <externalFilesDir>/vms/<id>/config.json
 *                                    /system.img | system.iso
 *                                    /data.img     (sparse raw disk)
 *                                    /serial.log, engine.log
 *   <filesDir>/runtime/<bin, lib, share>/   extracted QEMU engine
 */
class VmRepository(context: Context) {

    private val appContext = context.applicationContext

    val vmsRoot: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "vms")

    /**
     * Shared image store: downloads land here ONCE and are reused by every VM,
     * so deleting a VM never throws away a multi-GB image.
     */
    val imagesRoot: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "images")

    fun sharedAndroidDir(): File = File(imagesRoot, "android9").apply { mkdirs() }

    fun sharedAndroidBase(): File = File(sharedAndroidDir(), "rootfs.img")

    fun sharedAndroidKernel(): File = File(sharedAndroidDir(), "vmlinuz")

    fun sharedAlpineIso(): File = File(imagesRoot, "alpine.iso")

    val runtimeRoot: File
        get() = File(appContext.filesDir, "runtime")

    fun listVms(): List<VmConfig> {
        val dir = vmsRoot
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { d ->
                val cfg = File(d, "config.json")
                if (!cfg.isFile) return@mapNotNull null
                try {
                    VmConfig.fromJson(JSONObject(cfg.readText()))
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun vmDir(id: String): File = File(vmsRoot, id)

    fun saveConfig(cfg: VmConfig) {
        val d = vmDir(cfg.id)
        d.mkdirs()
        File(d, "config.json").writeText(cfg.toJson().toString(2))
    }

    /** Creates the VM directory, config and a sparse empty data disk. Returns the config. */
    fun createVm(name: String, ramMb: Int, cpus: Int): VmConfig {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val cfg = VmConfig(id = id, name = name, ramMb = ramMb, cpus = cpus)
        vmDir(id).mkdirs()
        createSparseFile(File(vmDir(id), "data.img"), DATA_DISK_BYTES)
        saveConfig(cfg)
        return cfg
    }

    fun deleteVm(id: String) {
        vmDir(id).deleteRecursively()
    }

    /** Copies a user-selected image into the VM dir. Returns the stored file. */
    fun importImage(id: String, uri: Uri, fileName: String): File {
        val dest = File(vmDir(id), fileName)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 1 shl 19) }
        } ?: throw IllegalStateException("Cannot open selected file")
        return dest
    }

    /** Streams a remote file (e.g. the Alpine test ISO) into the VM dir. */
    fun downloadImage(id: String, url: String, fileName: String, onProgress: (read: Long, total: Long) -> Unit): File =
        downloadTo(url, vmDir(id), fileName) { read, total -> onProgress(read, total) }

    /**
     * Downloads into an arbitrary dir (used for the shared image store).
     * Resumable: keeps .part across attempts with HTTP Range, retries on
     * stalls/timeouts so flaky mobile networks can finish multi-GB files.
     */
    fun downloadTo(
        url: String,
        dir: File,
        fileName: String,
        maxAttempts: Int = 60,
        onProgress: (read: Long, total: Long) -> Unit
    ): File {
        val dest = File(dir, fileName)
        val tmp = File(dir, "$fileName.part")
        dir.mkdirs()
        var attempts = 0
        while (true) {
            attempts++
            val have = if (tmp.exists()) tmp.length() else 0L
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            var resumed = false
            try {
                conn.connect()
                val code = conn.responseCode
                resumed = code == 206 && have > 0
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val total = if (resumed) have + conn.contentLengthLong else conn.contentLengthLong
                val out = java.io.FileOutputStream(tmp, resumed)
                conn.inputStream.use { input ->
                    out.use { output ->
                        val buf = ByteArray(1 shl 19)
                        var read = if (resumed) have else 0L
                        var lastMarked = read
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (read - lastMarked >= (1 shl 21)) {
                                lastMarked = read
                                onProgress(read, total)
                            }
                        }
                        onProgress(read, total)
                    }
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                return dest
            } catch (e: Exception) {
                if (attempts >= maxAttempts) throw e
                try { Thread.sleep(3000) } catch (x: InterruptedException) { throw e }
                // loop and resume from wherever tmp got to
            } finally {
                conn.disconnect()
            }
        }
    }

    fun serialLogFile(id: String): File = File(vmDir(id), "serial.log")
    fun engineLogFile(id: String): File = File(vmDir(id), "engine.log")

    fun systemDiskFile(id: String): File = File(vmDir(id), "system.img")
    fun dataDiskFile(id: String): File = File(vmDir(id), "data.img")

    companion object {
        /** 8 GiB sparse data disk: occupies ~0 bytes until the guest writes. */
        const val DATA_DISK_BYTES: Long = 8L * 1024 * 1024 * 1024
    }
}

/** Creates a sparse (holey) file that reports [length] but uses almost no space. */
fun createSparseFile(f: File, length: Long) {
    RandomAccessFile(f, "rw").use { it.setLength(length) }
}

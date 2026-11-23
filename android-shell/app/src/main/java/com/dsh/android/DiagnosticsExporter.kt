package com.dsh.android

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a zipped diagnostics bundle for troubleshooting without root:
 *
 *   dsh-diag-<ts>.zip
 *     env.json            device/kernel/storage facts (no secrets)
 *     runtime.stamp.txt   asset pipeline stamp (rootfs/proot hashes)
 *     rescue.json         ladder state (failures / level / parked)
 *     engine-log.tail.txt last ≤200 KiB of the guest-side engine log
 *     host-log.tail.txt   last ≤200 KiB of the host-captured stdout
 *     incoming.txt        asset drop-box listing
 *     url-redacted.txt    current engine URL with the token truncated
 *
 * Written to <getExternalFilesDir>/exports/ (visible to the system Files app;
 * no broad storage permission needed). FileProvider-based sharing: TODO M2.
 */
object DiagnosticsExporter {

    private const val TAIL_BYTES = 200 * 1024L

    fun export(ctx: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val exports = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val zip = File(exports, "dsh-diag-$ts.zip")
        val runtime = AssetInstaller.runtimeDir(ctx)
        val rootfs = File(runtime, "rootfs")

        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            put(z, "env.json", JSONObject()
                .put("appVersion", runCatching {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                }.getOrDefault("?"))
                .put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("kernel", System.getProperty("os.version") ?: "?")
                .put("abis", Build.SUPPORTED_ABIS.joinToString())
                .put("internalFreeMB", ctx.filesDir.usableSpace / 1048576)
                .put("externalFreeMB", ctx.getExternalFilesDir(null)?.usableSpace?.div(1048576) ?: -1)
                .toString(1))

            putFile(z, "runtime.stamp.txt", File(runtime, "runtime.stamp"))
            putFile(z, "rescue.json", File(runtime, "rescue.json"))
            put(z, "incoming.txt", AssetInstaller.incomingDir(ctx).listFiles()
                ?.joinToString("\n") { "${it.name}  ${it.length()}" } ?: "(empty)")
            putTail(z, "engine-log.tail.txt", File(rootfs, "root/logs/dsh-web.log"))
            putTail(z, "host-log.tail.txt", File(runtime, "host-stdout.log"))
            ctx.readTextOrNull(File(ctx.filesDir, DshEngineService.URL_FILE))?.let { url ->
                // Never ship the one-shot process token: keep origin+path only.
                // trimEnd guards the ".../?token=…" shape from yielding "//?".
                val clean = url.substringBefore('?').let { base ->
                    if (url.contains('?')) base.trimEnd('/') + "/?…token-redacted" else base
                }
                put(z, "url-redacted.txt", clean)
            }
        }
        return zip
    }

    private fun put(z: ZipOutputStream, name: String, text: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(text.toByteArray())
        z.closeEntry()
    }

    private fun putFile(z: ZipOutputStream, name: String, f: File) {
        if (f.isFile) put(z, name, f.readText())
    }

    private fun putTail(z: ZipOutputStream, name: String, f: File) {
        if (!f.isFile) return
        val size = f.length()
        val skip = maxOf(0L, size - TAIL_BYTES)
        FileInputStream(f).use { ins ->
            if (skip > 0) ins.skip(skip)
            z.putNextEntry(ZipEntry(name))
            ins.copyTo(z)
            z.closeEntry()
        }
    }

    private fun Context.readTextOrNull(f: File): String? =
        runCatching { if (f.isFile) f.readText() else null }.getOrNull()
}

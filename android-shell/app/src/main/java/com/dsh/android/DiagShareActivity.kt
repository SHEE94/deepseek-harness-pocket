package com.dsh.android

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.app.PendingIntent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Headless (NoDisplay) hop for the notification's "导出诊断" action: services
 * may not present UI, so the tap lands here — export (small: tails-capped zip,
 * synchronous on purpose to finish before onResume per NoDisplay rules),
 * hand it to the system share sheet, finish. The engine notification shows the
 * saved path so nothing is lost if the user cancels sharing.
 */
class DiagShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (zip, note) = runCatching {
            DiagnosticsExporter.export(this) to "诊断包已生成"
        }.getOrElse {
            null to "诊断导出失败：${it.message}"
        }
        getSystemService(NotificationManager::class.java)
            .notify(DshEngineService.NOTIF_ID, engineNotification(this, note))
        if (zip != null) share(zip)
        finish()
    }

    private fun share(zip: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, "分享 DSH 诊断包")) }
    }

    private fun engineNotification(ctx: Context, text: String): android.app.Notification {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return android.app.Notification.Builder(ctx, DshEngineService.CHANNEL_ID)
            .setContentTitle("DSH Pocket")
            .setContentText(text)
            // 通知小图标必须是单色剪影（系统会整体着色）
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

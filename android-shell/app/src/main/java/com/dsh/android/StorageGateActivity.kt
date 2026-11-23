package com.dsh.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Transparent hop: notifications cannot open the system "All files access"
 * screen directly, and MainActivity shouldn't hijack the boot path. Opened
 * from the engine notification's「存储映射」action (visible only while the
 * grant is missing). On return the storage watcher recycles the engine ≤5 s,
 * which re-binds /sdcard — no manual restart needed.
 */
class StorageGateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")),
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        finish()
    }
}

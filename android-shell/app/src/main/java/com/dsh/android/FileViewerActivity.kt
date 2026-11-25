package com.dsh.android

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Transparent dialog-activity showing AND editing a guest config file
 *  (opened via the DSH_ANDROID_OPEN bridge, or the shell's 查看配置文件 menu).
 *  The guest path maps to the host file under runtime/rootfs (containment
 *  validated — the same rule the pump applies). */
class FileViewerActivity : Activity() {
    private lateinit var hostFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val guestPath = intent.getStringExtra("path") ?: "(未知路径)"
        hostFile = resolveHostFile(guestPath)
        val content = if (hostFile.isFile) runCatching {
            hostFile.readText(Charsets.UTF_8)
        }.getOrElse { "（读取失败：${it.message}）" } else (intent.getStringExtra("content") ?: "")

        val title = TextView(this).apply {
            text = guestPath
            setTextColor(0xFFC7D2DC.toInt())
            textSize = 13f
            setPadding(48, 64, 48, 16)
        }
        val body = EditText(this).apply {
            setText(content.ifBlank { "" })
            setTextColor(0xFFE6EDF3.toInt())
            setBackgroundColor(0xFF12171D.toInt())
            highlightColor = 0xFF3FB950.toInt()
            setTextSize(12f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 8, 48, 8)
            gravity = android.view.Gravity.TOP
            isVerticalScrollBarEnabled = true
            minLines = 12
        }
        val save = Button(this).apply {
            text = "保存"
            setOnClickListener {
                if (!hostFile.isFile) {
                    Toast.makeText(this@FileViewerActivity, "文件不存在，无法保存", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                runCatching {
                    hostFile.writeText(body.text.toString(), Charsets.UTF_8)
                }.onSuccess {
                    Toast.makeText(this@FileViewerActivity, "已保存 ${hostFile.name}", Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure {
                    Toast.makeText(this@FileViewerActivity, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val close = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(48, 8, 48, 16)
            addView(close)
            addView(save)
        }
        val scroll = ScrollView(this).apply {
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF212171D.toInt())
            addView(title)
            addView(scroll)
            addView(buttons)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0x66000000.toInt())
            addView(box, android.widget.FrameLayout.LayoutParams(
                -1, (resources.displayMetrics.heightPixels * 0.82).toInt()))
        }
        setContentView(wrap)
        // tap outside the box to close
        wrap.setOnClickListener { finish() }
        box.setOnClickListener { }
    }

    private fun resolveHostFile(guestPath: String): File {
        val root = File(AssetInstaller.runtimeDir(this), "rootfs")
        if (!guestPath.startsWith("/")) return File(root, guestPath)
        val f = File(root, guestPath)
        return runCatching {
            if (f.canonicalPath.startsWith(root.canonicalPath)) f else File(root, "(rejected)")
        }.getOrDefault(File(root, "(rejected)"))
    }
}

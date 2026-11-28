package com.dsh.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Native-level settings hub: accessibility enablement today, an additive
 *  place for every future native capability (overlays, notification access…). */
class NativeSettingsActivity : Activity() {
    private lateinit var a11yStatus: TextView
    private lateinit var bridgeStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = 56
        val title = TextView(this).apply {
            text = "原生设置"
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 22f
            setPadding(pad, pad, pad, 16)
        }
        a11yStatus = TextView(this).apply {
            textSize = 15f
            setPadding(pad, 24, pad, 8)
        }
        val openA11y = Button(this).apply {
            text = "打开系统无障碍设置（启用 DSH 手机操控）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        bridgeStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8B949E.toInt())
            setPadding(pad, 24, pad, 8)
        }
        val future = TextView(this).apply {
            text = "后续原生能力将在此逐项加入：悬浮窗、通知监听、快捷磁贴…"
            textSize = 13f
            setTextColor(0xFF6E7681.toInt())
            setPadding(pad, 40, pad, pad)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(LinearLayout(this@NativeSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(a11yStatus)
                addView(openA11y)
                addView(bridgeStatus)
                addView(future)
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12171D.toInt())
            addView(title)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        val on = DshAccessibilityService.isEnabled()
        a11yStatus.text = "无障碍服务（手机画面感知 + 手势模拟）：" + if (on) "已启用" else "未启用"
        a11yStatus.setTextColor(if (on) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
        bridgeStatus.text = "原生桥：127.0.0.1:${NativeBridgeServer.PORT}（环回专用，仅 DSH 引擎可访问）· 截屏能力：Android 11+"
    }
}

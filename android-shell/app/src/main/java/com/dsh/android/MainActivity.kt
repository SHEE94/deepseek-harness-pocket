package com.dsh.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * WebView container for the official DSH Web GUI served by the loopback engine.
 *
 * Boot: ensure the engine service, then load the last tokenized URL (token is
 * one-shot: the GUI exchanges it for a session cookie and redirects; cookie
 * persistence keeps us logged in across restarts).
 *
 * While the engine boots (first unpack can take minutes) a live wait page
 * shows the pipeline stage fed by real state — asset readiness, stamp, and
 * the rescue ladder — instead of a black-box spinner. No URL/token is ever
 * rendered into the wait page.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    @Volatile private var waitActive = false
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, DshEngineService::class.java))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : android.webkit.WebViewClient() {
                // Keep navigation inside the engine origin; anything else is refused
                // (guards against "any web page driving the engine").
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !url.contains("127.0.0.1") && !url.contains("localhost")
                override fun onReceivedError(
                    view: WebView?, request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    // Engine mid-restart: only the main document failure matters —
                    // fall back to the wait page whose poller re-loads on the fresh URL.
                    if (request?.isForMainFrame == true && waitActive != true) {
                        waitActive = true
                        runOnUiThread {
                            web.loadDataWithBaseURL(null, WAIT_HTML, "text/html", "utf-8", null)
                        }
                        startWaitPoller()
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (waitActive) pushFacts()
                }
            }
            // Loopback JS bridge for the official GUI's mobile-comfort plugin:
            // 「检查更新」chip → engine service ACTION_CHECK_UPDATE. Origin-gated —
            // the engine page (127.0.0.1/localhost) is the ONLY caller accepted;
            // anything else gets a no-op, mirroring shouldOverrideUrlLoading's rule.
            // NOTE: must use the apply-receiver (`this`), NOT the `web` field —
            // the field is still uninitialized while this block runs (crashed:
            // UninitializedPropertyAccessException during onCreate).
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun checkUpdate() {
                    val url = url.orEmpty()
                    if (!(url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))) return
                    startService(Intent(this@MainActivity, DshEngineService::class.java)
                        .setAction(DshEngineService.ACTION_CHECK_UPDATE))
                }
            }, "DshAndroid")
        }
        topBar = buildTopBar()
        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F14.toInt())
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, ins ->
                val sb = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                v.setPadding(0, sb.top, 0, 0)
                ins
            }
        }
        rootLayout.addView(topBar, android.widget.LinearLayout.LayoutParams(-1, dp(44)))
        rootLayout.addView(web, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(rootLayout)
        // 定制系统 kills the Activity on background; WebView.saveState bundle is
        // the only way to restore the RENDERED page (back-forward list + scroll
        // position). If present, skip loadEngine entirely.
        val restored = savedInstanceState != null &&
            runCatching { web.restoreState(savedInstanceState) != null }.getOrDefault(false)
        android.util.Log.i("dsh-loop", "restoreState hit=$restored saved=${savedInstanceState != null}")
        if (!restored) {
            maybeAskStorageAccess()
            loadEngine()
        }
    }
    /** One-time nudge toward "All files access" (L3 mapping) — only after the
     *  engine assets are ready, and never more than once per install. */
    private fun maybeAskStorageAccess() {
        val prefs = getSharedPreferences("dsh", MODE_PRIVATE)
        if (prefs.getBoolean("storage_asked", false)) return
        if (!AssetInstaller.isReady(this)) return
        if (android.os.Environment.isExternalStorageManager()) return
        prefs.edit().putBoolean("storage_asked", true).apply()
        runCatching {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        }
    }


    private lateinit var topBar: android.widget.LinearLayout
    private lateinit var statusDot: android.widget.TextView
    private lateinit var statusText: android.widget.TextView
    private var cachedVersion: String? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val topRefresh = object : Runnable {
        override fun run() { refreshTopBar(); uiHandler.postDelayed(this, 2000) }
    }

    // ---- native action bar ----------------------------------------------------
    // Shell-owned toolbar above the official GUI: engine status + the operations
    // that must NOT depend on page DOM (检查更新/存储映射/救援动作). The user asked
    // for a top bar — it is the discoverable home for every functional entry;
    // mobile-comfort chips stay as the in-conversation convenience row.

    private fun buildTopBar(): android.widget.LinearLayout {
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        statusDot = android.widget.TextView(this).apply {
            text = "●"; setTextColor(0xFFD8A33C.toInt()); textSize = 11f
            setPadding(0, 0, dp(8), 0)
        }
        statusText = android.widget.TextView(this).apply {
            setTextColor(0xFF9FB3C8.toInt()); textSize = 12.5f; isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, dp(8), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        }
        fun tbtn(label: String, desc: String, action: (android.view.View) -> Unit) =
            android.widget.TextView(this).apply {
                text = label; setTextColor(0xFFC7D2DC.toInt()); textSize = 17f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                contentDescription = desc
                setOnClickListener { action(it) }
            }
        bar.addView(statusDot)
        bar.addView(statusText)
        bar.addView(tbtn("⟳", "检查更新") {
            startService(Intent(this, DshEngineService::class.java).setAction(DshEngineService.ACTION_CHECK_UPDATE))
            toast("已开始检查，进度见通知栏")
        })
        bar.addView(tbtn("📁", "存储映射") {
            if (android.os.Environment.isExternalStorageManager())
                toast("存储已联通：工程交换区 + 共享存储")
            else startActivity(Intent(this, StorageGateActivity::class.java))
        })
        bar.addView(tbtn("⋮", "更多") { v -> showOverflow(v) })
        return bar
    }

    private fun showOverflow(anchor: android.view.View) {
        val pm = android.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "重启引擎")
        pm.menu.add(0, 2, 0, "恢复插件")
        pm.menu.add(0, 3, 0, "导出诊断")
        pm.menu.add(0, 4, 0, "关于 / 版本")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> serviceAction(DshEngineService.ACTION_RETRY)
                2 -> serviceAction(DshEngineService.ACTION_RESTORE)
                3 -> startActivity(Intent(this, DiagShareActivity::class.java))
                4 -> toast("DSH ${engineVersion()}")
            }
            true
        }
        pm.show()
    }

    private fun serviceAction(a: String) {
        startService(Intent(this, DshEngineService::class.java).setAction(a))
    }
    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun engineVersion(): String {
        // Updater caches the effective `dsh --version` beside the rootfs.
        runCatching {
            File(AssetInstaller.runtimeDir(this), "dsh-version.txt").readText().trim()
        }.getOrNull()?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+[-\w.]*""")) }?.let { return it }
        val local = File(AssetInstaller.runtimeDir(this), "rootfs/usr/local")
        for (dir in listOf("lib/node_modules", "node_modules")) {
            runCatching {
                Regex(""""version"\s*:\s*"([^"]+)"""").find(
                    File(local, "$dir/@deepseek-ai/dsh/package.json").readText(),
                )?.groupValues?.get(1)?.let { return it }
            }
        }
        return "?"
    }

    private fun refreshTopBar() {
        val svc = DshEngineService.lastStatus
        val urlReady = runCatching { engineUrl() }.getOrNull() != null
        val bad = svc != null && (svc.contains("已停止") || svc.contains("失败") || svc.contains("异常") || svc.contains("缺失"))
        statusDot.setTextColor(when {
            bad -> 0xFFD0655F.toInt()
            !urlReady -> 0xFFD8A33C.toInt()
            else -> 0xFF3FB950.toInt()
        })
        statusText.text = when {
            !urlReady -> svc ?: "引擎启动中…"
            svc != null -> svc
            else -> "引擎运行中 · DSH ${engineVersion()}"
        }
    }
    private fun engineUrl(): String? =
        DshEngineService.lastUrl
            ?: runCatching { File(filesDir, DshEngineService.URL_FILE).readText().trim() }
                .getOrNull()?.takeIf { it.isNotBlank() }

    private fun loadEngine() {
        val url = engineUrl()
        if (!url.isNullOrBlank()) {
            waitActive = false
            // Activity-rebuild fast path: when the OEM launcher killed+rebuilt the Activity,
            // prefer the LAST RENDERED page (cookie session still valid) so the
            // user re-enters the same conversation instead of a fresh reload.
            val lastRendered = getSharedPreferences("dsh", MODE_PRIVATE)
                .getString("last_gui_url", null)
            val target = if (lastRendered != null && lastRendered.startsWith("http://127.0.0.1")
                && !CookieManager.getInstance().getCookie(lastRendered).isNullOrBlank()) lastRendered else url
            web.loadUrl(target)
        } else if (SetupActivity.needsSetup(this)) {
            // First run without assets: hand over to the guided onboarding.
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        } else {
            waitActive = true
            web.loadDataWithBaseURL(null, WAIT_HTML, "text/html", "utf-8", null)
            startWaitPoller()
        }
    }

    // ---- live wait page -------------------------------------------------------

    private fun startWaitPoller() {
        // Poller start time: URL files older than this belong to a DEAD engine
        // (stale token race, device-proven 14:14:06 — old file was read, engine
        // restarted under it, page stuck on the wait screen forever).
        val startedAt = System.currentTimeMillis()
        Thread {
            while (waitActive && !isFinishing) {
                val u = engineUrl()
                val fresh = u != null && runCatching {
                    File(filesDir, DshEngineService.URL_FILE).lastModified() > startedAt - 30_000
                }.getOrDefault(true)
                if (u != null && fresh) {
                    waitActive = false
                    runOnUiThread { web.loadUrl(u) }
                    return@Thread
                }
                pushFacts()
                Thread.sleep(1500)
            }
        }.apply { isDaemon = true }.start()
    }

    /** Collect real pipeline state; never includes the engine URL/token. */
    private fun collectFacts(): JSONObject {
        val runtime = AssetInstaller.runtimeDir(this)
        val staged = AssetInstaller.isReady(this)
        val stamp = File(runtime, "runtime.stamp").takeIf { it.isFile }?.readText()?.trim()
        val rescue = File(runtime, "rescue.json").takeIf { it.isFile }?.let { f ->
            runCatching { JSONObject(f.readText()) }.getOrNull()
        }
        val level = rescue?.optString("level", "RUN") ?: "RUN"
        val failures = rescue?.optInt("failures", 0) ?: 0
        // stage: 1 assets · 2 unpack/verify · 3 engine booting · (4 = url → leave page)
        val stage = when {
            !staged -> 2                      // assets landed; extraction/verification in flight
            level == "HALT" -> 9              // halted
            failures >= RescueLadder.LEVEL1_FAILURES -> 31 // booting with retries visible
            else -> 3
        }
        return JSONObject()
            .put("stage", stage)
            .put("failures", failures)
            .put("parked", rescue?.optJSONObject("parked")?.length() ?: 0)
            .put("storage", if (android.os.Environment.isExternalStorageManager()) "full" else "projects")
            .put("stamp", stamp?.lineSequence()?.firstOrNull()?.take(60) ?: JSONObject.NULL)
    }

    private fun pushFacts() {
        val f = runCatching { collectFacts().toString() }.getOrElse { "{}" }
        runOnUiThread { runCatching { web.evaluateJavascript("window.setFacts&&window.setFacts($f)", null) } }
    }

    override fun onBackPressed() {
        if (!web.canGoBack()) super.onBackPressed() else web.goBack()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(topRefresh)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runCatching {
            web.saveState(outState)
            android.util.Log.i("dsh-loop", "saveState done url=${web.url ?: "null"}")
        }.onFailure { android.util.Log.i("dsh-loop", "saveState fail ${it.message}") }
    }
    override fun onPause() {
        uiHandler.removeCallbacks(topRefresh)
        // 定制系统 destroys the ACTIVITY (not the process) when backgrounded;
        // persist the engine URL so the rebuilt Activity lands back on the
        // SAME page instantly instead of a full GUI reload.
        runCatching {
            // Only engine-origin URLs are worth restoring — about:blank or the
            // wait page would clobber the good value on every rebuild.
            web.url?.takeIf {
                it.startsWith("http://127.0.0.1") || it.startsWith("http://localhost")
            }?.let {
                getSharedPreferences("dsh", MODE_PRIVATE)
                    .edit().putString("last_gui_url", it).apply()
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        waitActive = false
        super.onDestroy()
    }

    private companion object {
        // Mirrors the SetupActivity visual language (dark, big touch targets).
        const val WAIT_HTML = """
<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
 body{margin:0;background:#101418;color:#c7d2dc;font-family:system-ui,sans-serif;
      padding:calc(48px + env(safe-area-inset-top)) 22px 24px}
 h1{font-size:18px;font-weight:600;margin:0 0 18px}
 .steps{display:flex;gap:10px;margin-bottom:20px}
 .st{flex:1;text-align:center;font-size:12px;color:#5d6a78}
 .st .dot{width:14px;height:14px;border-radius:50%;background:#2a3646;margin:0 auto 6px}
 .st.on{color:#c7d2dc}.st.on .dot{background:#247bbf}
 .st.act .dot{background:#c9a13d}.st.act{color:#d8c48a}
 .st.err .dot{background:#d0655f}.st.err{color:#e0928d}
 #status{font-size:13.5px;line-height:1.6;color:#9fb3c8;min-height:44px}
 #note{margin-top:14px;font-size:12.5px;color:#e0928d;min-height:16px}
 #stamp{margin-top:16px;font-size:11px;color:#4d5a68;word-break:break-all}
</style></head><body>
 <h1>DSH 引擎启动中</h1>
 <div class="steps">
  <div class="st" id="s1"><div class="dot"></div>资产就位</div>
  <div class="st" id="s2"><div class="dot"></div>校验解包</div>
  <div class="st" id="s3"><div class="dot"></div>引擎启动</div>
  <div class="st" id="s4"><div class="dot"></div>就绪</div>
 </div>
 <div id="status">正在确认引擎状态…</div>
 <div id="note"></div>
 <div id="stamp"></div>
<script>
 function el(i){ return document.getElementById(i); }
 function txt(i,t){ el(i).textContent=t; }
 function setFacts(f){
   var stage = f.stage || 0;
   ['s1','s2','s3','s4'].forEach(function(i){ el(i).className = 'st'; });
   if (stage === 2) {
     el('s1').className='st on'; el('s2').className='st act';
     txt('status','资产已收到，正在校验并解包系统镜像（首次约 1–3 分钟，进度同步在通知栏）。');
     txt('note','');
   } else if (stage === 9) {
     el('s1').className='st on'; el('s2').className='st on'; el('s3').className='st err';
     txt('status','引擎连续启动失败，已停止自动重试（救援模式）。');
     txt('note','通知栏可「导出诊断」或「恢复插件」，处理后点「重试」。被暂存的插件配置不会被删除。');
   } else if (stage === 31) {
     el('s1').className='st on'; el('s2').className='st on'; el('s3').className='st act';
     txt('status','引擎启动中（已自动重试 ' + f.failures + ' 次）。');
     txt('note', f.parked > 0 ? '疑似故障的插件配置已自动暂存，可在通知栏「恢复插件」还原。' : '');
   } else {
     el('s1').className='st on'; el('s2').className='st on'; el('s3').className='st act';
     txt('status','正在等待引擎打印就绪信号…');
     txt('note','');
   }
   el('stamp').textContent = (f.stamp && f.stamp !== 'null' ? String(f.stamp) + ' · ' : '')
     + (f.storage === 'full' ? '/projects 与 /sdcard 均已联通（共享存储直接读写）' : '/projects 可用；点通知栏「存储映射」可联通整个 /sdcard');
 }
</script></body></html>
"""
    }
}

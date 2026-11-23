package com.dsh.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * No-adb asset onboarding (UX-first first run):
 *
 *  - shows what the engine needs (rootfs tarball / optional SHA256SUMS / proot)
 *    with a live checklist instead of prose the user must parse;
 *  - each row opens the system file picker (SAF) — works from Downloads,
 *    Files, an archive app, wherever the CI artifact landed;
 *  - copies land in the AssetInstaller incoming box atomically (.part → rename),
 *    progress streams back into the page;
 *  - once staged, the page hops to MainActivity on its own — no mystery steps.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        /** True when nothing usable exists yet — MainActivity redirects here. */
        fun needsSetup(ctx: Context): Boolean =
            !AssetInstaller.isReady(ctx) && AssetInstaller.findPlan(ctx) == null
    }

    private lateinit var web: WebView
    private var pendingKind: String = "rootfs"

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { importUri(it, pendingKind) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            setBackgroundColor(0xFF101418.toInt())
            addJavascriptInterface(Bridge(), "android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { renderStatus() }
            }
        }
        setContentView(web)
        web.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
        startReadinessWatcher()
    }

    // ---- JS bridge -----------------------------------------------------------

    private inner class Bridge {
        @JavascriptInterface
        fun pick(kind: String?) {
            pendingKind = when (kind) { "sums" -> "sums"; "proot" -> "proot"; else -> "rootfs" }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_TITLE,
                    when (pendingKind) {
                        "sums" -> "选择 SHA256SUMS.txt"
                        "proot" -> "选择 proot 可执行文件"
                        else -> "选择 dsh-appliance-*-rootfs.tar.gz"
                    },
                )
            }
            runCatching { picker.launch(intent) }
                .onFailure { setStatus("无法打开系统文件选择器：${it.message}") }
        }

        @JavascriptInterface
        fun openEngine() {
            if (AssetInstaller.isReady(this@SetupActivity)) gotoEngine()
            else setStatus("资产尚未就绪，请先完成导入与解包")
        }
    }

    // ---- import ---------------------------------------------------------------

    private fun importUri(uri: Uri, kind: String) {
        val name = queryName(uri) ?: when (kind) {
            "sums" -> "SHA256SUMS.txt"
            "proot" -> "proot"
            else -> return setStatus("无法识别所选文件名，请用原始文件名下载 CI 产物")
        }
        val safe = name.replace(Regex("""[^A-Za-z0-9._\-]"""), "_")
        if (kind == "rootfs" && !safe.contains("-rootfs.tar")) {
            setStatus("所选文件不是 *-rootfs.tar.gz：$safe"); return
        }
        Thread {
            val incoming = AssetInstaller.incomingDir(this).apply { mkdirs() }
            // proot row accepts either the CI bundle tarball or a lone binary.
            val dstName = when {
                kind == "sums" -> "SHA256SUMS.txt"
                kind == "proot" && safe.endsWith(".tar.gz") -> "proot-bundle.tar.gz"
                kind == "proot" && safe.endsWith(".tar") -> "proot-bundle.tar"
                kind == "proot" -> "proot"
                else -> safe
            }
            val dst = File(incoming, dstName)
            val part = File(incoming, dst.name + ".part")
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    part.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                } ?: error("无法读取所选文件")
                dst.delete()
                if (!part.renameTo(dst)) error("落位失败（空间不足？）")
                // 让服务尽快感知新资产：staging + 引擎启动都走通知栏进度
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, DshEngineService::class.java),
                )
            }.onSuccess {
                setStatus("已导入 ${dst.name}（${dst.length() / 1024} KB），引擎会在通知栏继续校验与解包…")
            }.onFailure { setStatus("导入失败：${it.message}") }
        }.start()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    // ---- status page ----------------------------------------------------------

    private fun startReadinessWatcher() {
        Thread {
            while (!isFinishing) {
                if (AssetInstaller.isReady(this)) {
                    runOnUiThread { gotoEngine() }
                    return@Thread
                }
                Thread.sleep(2000)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun gotoEngine() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun renderStatus() {
        val incoming = AssetInstaller.incomingDir(this)
        val rootfs = incoming.listFiles { f: File -> f.isFile && f.name.contains("-rootfs.tar") }
            ?.maxByOrNull { it.lastModified() }
        val sums = File(incoming, "SHA256SUMS.txt").isFile
        val prootIn = File(incoming, "proot").isFile
        val runtime = AssetInstaller.runtimeDir(this)
        val ready = AssetInstaller.isReady(this)
        val facts = JSONObject()
            .put("rootfs", rootfs?.name ?: JSONObject.NULL)
            .put("rootfsKB", (rootfs?.length() ?: 0L) / 1024)
            .put("sums", sums)
            .put("proot", prootIn)
            .put("stamped", File(runtime, "runtime.stamp").isFile)
            .put("ready", ready)
        runOnUiThread {
            runCatching {
                web.evaluateJavascript("window.setFacts && window.setFacts(${facts})", null)
            }
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            runCatching {
                web.evaluateJavascript("window.setStatus && window.setStatus(${JSONObject.quote(text)})", null)
            }
        }
    }

}

// ---- the page (top-level constant; class closed above) -------------------------

private val PAGE = """
<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
 body{margin:0;background:#101418;color:#c7d2dc;font-family:system-ui,sans-serif;
      padding:calc(20px + env(safe-area-inset-top)) 18px calc(24px + env(safe-area-inset-bottom))}
 h1{font-size:20px;margin:0 0 4px} .sub{color:#7d8a97;font-size:13px;margin-bottom:20px}
 .card{background:#171d24;border:1px solid #263140;border-radius:14px;padding:14px 16px;margin:0 0 12px}
 .row{display:flex;align-items:center;gap:12px;min-height:48px}
 .row+.row{border-top:1px solid #1f2836}
 .dot{width:10px;height:10px;border-radius:50%;background:#3a4757;flex:none}
 .dot.ok{background:#3fb96f}
 .name{flex:1;font-size:14px}
 .name small{display:block;color:#7d8a97;font-size:11.5px}
 button{min-height:44px;padding:0 16px;border-radius:999px;border:1px solid #2f6ea8;
        background:transparent;color:#7db7e8;font-size:14px}
 button.primary{background:#247bbf;color:#fff;border-color:#247bbf}
 #status{font-size:13px;color:#9fb3c8;margin-top:10px;min-height:18px}
 #go{width:100%;margin-top:14px}
</style></head><body>
 <h1>准备 DSH 引擎</h1>
 <div class="sub">首次使用需要放入引擎资产（来自发布页 / CI 产物，约 300 MB）。全程无需命令行。</div>
 <div class="card">
   <div class="row"><span class="dot" id="d-rootfs"></span>
     <div class="name">系统镜像 <small id="t-rootfs">dsh-appliance-*-rootfs.tar.gz · 必需</small></div>
     <button onclick="android.pick('rootfs')">选择</button></div>
   <div class="row"><span class="dot" id="d-sums"></span>
     <div class="name">校验清单 <small>SHA256SUMS.txt · 强烈建议（防下载损坏）</small></div>
     <button onclick="android.pick('sums')">选择</button></div>
   <div class="row"><span class="dot" id="d-proot"></span>
     <div class="name">运行组件 <small id="t-proot">proot · 推荐 proot-bundle.tar.gz（CI 产物），或单文件</small></div>
     <button onclick="android.pick('proot')">选择</button></div>
 </div>
 <div class="card"><div id="status">等待资产…放入后会自动校验并解包，进度见通知栏。</div>
   <button class="primary" id="go" style="display:none" onclick="android.openEngine()">进入 DSH</button>
 </div>
<script>
 function setFacts(f){
   mark('d-rootfs', f.rootfs!==null);
   if(f.rootfs!==null) el('t-rootfs').textContent=f.rootfs+' · '+(f.rootfsKB/1024).toFixed(0)+' MB';
   mark('d-sums', !!f.sums); mark('d-proot', !!f.proot);
   var ready=!!f.ready; el('go').style.display=ready?'block':'none';
   if(ready) el('status').textContent='引擎已就绪 ✓';
 }
 function mark(id,ok){ var e=el(id); if(e) e.className='dot'+(ok?' ok':''); }
 function el(i){ return document.getElementById(i); }
 function setStatus(t){ el('status').textContent=t; }
</script></body></html>
"""

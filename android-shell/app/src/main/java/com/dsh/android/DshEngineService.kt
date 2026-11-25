package com.dsh.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the DSH engine: proot(guest rootfs) → node → `dsh --profile web --no-open`.
 *
 *  1. Foreground service + PARTIAL_WAKE_LOCK so OEM doze does not reap the engine.
 *  2. Asset staging through [AssetInstaller] (hash-verified, stamped, atomic swap).
 *  3. Supervision with the [RescueLadder]: early-exit streaks escalate to
 *     park-profile and then halt; a stable run resets the ladder.
 *  4. Notification actions: export diagnostics / restore parked profiles / retry.
 */
class DshEngineService : Service() {

    companion object {
        const val CHANNEL_ID = "dsh-engine"
        const val NOTIF_ID = 1
        const val URL_FILE = "engine-url.txt"
        const val ACTION_EXPORT = "com.dsh.android.EXPORT_DIAG"
        const val ACTION_RESTORE = "com.dsh.android.RESTORE_PROFILES"
        const val ACTION_RETRY = "com.dsh.android.RETRY_ENGINE"
        const val ACTION_CHECK_UPDATE = "com.dsh.android.CHECK_UPDATE"
        private const val HOST_LOG_CAP = 5L * 1024 * 1024

        @Volatile
        var lastUrl: String? = null
        /** Latest notification line — the top bar mirrors it (single source of truth). */
        @Volatile var lastStatus: String? = null
    }

    private val running = AtomicBoolean(false)
    private val actionRequested = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null
    private lateinit var ladder: RescueLadder
    private lateinit var hostLog: File

    override fun onCreate() {
        super.onCreate()
        // startForeground FIRST — the 20s service-execution ANR budget counts cold-start
        // work; anything that touches disk (ladder's rescue.json) must come after the
        // foreground state is up (Android best practice, and survival insurance on slow I/O).
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DSH 引擎", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIF_ID, buildNotification("引擎服务启动中…"))
        ladder = RescueLadder(this)
        hostLog = File(AssetInstaller.runtimeDir(this), "host-stdout.log")
        updateNotification("正在启动引擎…（${ladder.describe()}）")
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:engine").also { it.acquire() }
        running.set(true)
        worker = Thread { superviseLoop() }.apply { name = "dsh-supervisor"; isDaemon = true; start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXPORT, ACTION_RESTORE, ACTION_RETRY, ACTION_CHECK_UPDATE -> {
                handleOperatorAction(intent.action)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { proc?.destroy() }
        synchronized(actionRequested) { actionRequested.set(true); (actionRequested as Object).notifyAll() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 定制系统「全部清除」killed the FGS with the task (device-proven); onTaskRemoved
     *  is our last hook — restart our own foreground service from here. Even if
     *  the process is torn down right after, START_STICKY revives it when the
     *  system relents; this covers the common case. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.i("dsh-loop", "task removed — self-restarting FGS")
        val self = Intent(this, DshEngineService::class.java)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, self) }
        runCatching {
            val am = getSystemService(android.app.AlarmManager::class.java)
            am?.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 3000,
                android.app.PendingIntent.getForegroundService(
                    this, 77, self,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        }
    }

    // ---- supervision --------------------------------------------------------

    private data class RunOutcome(val exit: Int, val runMillis: Long)

    @Volatile private var proc: Process? = null

    /** "All files access" can flip while the engine runs (user grants mid-session).
     *  No broadcast exists for it — poll cheaply and recycle the process so the
     *  next spawn picks the new bind set (≤5 s). Same poll also serves DSH
     *  updates: the supervision cycle blocks in waitFor while the engine is
     *  HEALTHY — a file drop must be noticed here, not at the next exit. */
    private fun startStorageWatcher() {
        Thread({
            var last = android.os.Environment.isExternalStorageManager()
            while (running.get()) {
                Thread.sleep(5_000)
                val now = android.os.Environment.isExternalStorageManager()
                if (now != last) {
                    last = now
                    android.util.Log.i("dsh-loop", "storage grant flipped=$now; recycling engine for remount")
                    runCatching { proc?.destroy() }
                    continue
                }
                runCatching {
                    AssetInstaller.applyUpdateIfAny(this) { updateNotification(it) }?.let { line ->
                        updateNotification(line)
                        runCatching { proc?.destroy() }
                    }
                }.onFailure { e ->
                    android.util.Log.e("dsh-stage", "update failed", e)
                    updateNotification("DSH 更新失败：${e.message}")
                }
            }
        }, "dsh-storage-watcher").apply { isDaemon = true; start() }
    }

    private fun superviseLoop() {
        var backoffMs = 5_000L
        startStorageWatcher()
        startStorageBridge()
        // Halt is an operator-state, not an exit condition: park in 60s waits but
        // keep re-reading the ladder each turn (fixes the latch-in-sleep trap where
        // ACTION_RETRY arriving during the wait was consumed without effect).
        while (running.get()) {
            // Staging must NOT be gated by the halt latch: halt protects against
            // crash-looping a BROKEN engine; it must never block recovery via new
            // assets. Stamp mismatch (fresh rootfs/bundle) also lifts the halt —
            // new assets are exactly the operator action rescue mode asks for.
            if (!AssetInstaller.isReady(this) || !AssetInstaller.stampMatchesPublic(this)) {
                try {
                    updateNotification(AssetInstaller.stageIfNeeded(this) { updateNotification(it) })
                    ladder.liftHaltForNewAssets()
                } catch (e: AssetInstaller.StageException) {
                    android.util.Log.e("dsh-stage", "staging failed", e)
                    updateNotification("资产缺失：${e.message}")
                    awaitActionOr(30_000L)
                    continue
                }
            }
            android.util.Log.i("dsh-loop", "cycle halted=${ladder.isHalted()} ready=${AssetInstaller.isReady(this)}")
            // In-app DSH update (dsh-update-*.tar.gz in incoming): applied
            // natively BEFORE any respawn decision; destroy() makes the NEXT
            // cycle's spawn load the fresh engine modules.
            runCatching {
                AssetInstaller.applyUpdateIfAny(this) { updateNotification(it) }?.let { line ->
                    updateNotification(line)
                    runCatching { proc?.destroy() }
                }
            }.onFailure { e ->
                android.util.Log.e("dsh-stage", "update failed", e)
                updateNotification("DSH 更新失败：${e.message}")
            }
            if (ladder.isHalted()) {
                updateNotification("已停止自动重试（救援模式）。可导出诊断或恢复插件后点「重试」")
                awaitActionOr(60_000L)
                continue
            }
            if (ladder.shouldParkNow()) {
                updateNotification(ladder.parkWebProfile())
                Thread.sleep(2_000)
            }
            val outcome = runEngineOnce()
            if (!running.get()) break
            when {
                outcome.exit == 0 -> {
                    updateNotification("引擎已正常停止")
                    break // clean stop requested; START_STICKY may revive via system
                }
                outcome.runMillis < 0 -> backoffMs = 30_000L // asset/spawn problem
                else -> {
                    val note = ladder.onExit(outcome.runMillis, outcome.exit)
                    backoffMs = if (ladder.isHalted()) 60_000L else 5_000L
                    updateNotification("$note（${backoffMs / 1000}s 后重试）")
                }
            }
            awaitActionOr(backoffMs)
        }
    }

    /** Sleep up to [ms], but return early when an operator action arrives. */
    private fun awaitActionOr(ms: Long) {
        synchronized(actionRequested) {
            actionRequested.set(false)
            runCatching { (actionRequested as Object).wait(ms) }
        }
    }

    /** @return exit code and process runtime (runMillis=-1 when never spawned). */
    /** force-stop SIGKILLs proot, so --kill-on-exit never runs and a previous
     *  guest node can linger as an orphan holding DSH_PORT (observed: EADDRINUSE
     *  blocked the post-update engine). Reap our own stale guests before every
     *  spawn — cross-uid kills are EPERM'd by the kernel, so this is safe. */
    private fun reapStaleGuests() {
        runCatching {
            val p = ProcessBuilder("sh", "-c",
                "ps -A -o PID,ARGS 2>/dev/null | grep -E 'proot|node' | grep -v grep")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val self = android.os.Process.myPid()
            out.lineSequence()
                .mapNotNull { l -> l.trim().substringBefore(' ').toIntOrNull()?.takeIf { it != self } }
                .forEach { pid ->
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                }
        }
    }

    /** FUSE view readiness gate: after reboot/定制系统 storage remount the app's
     *  fuse view of /storage can exist but be EMPTY until vold finishes; an
     *  engine spawned in that window gets proot bind warnings + EACCES for the
     *  whole session (maptest deny, device-verified 2026-11-20). Wait (bounded)
     *  for a REAL listing before spawning with storage binds. */
    /** Storage sync bridge (proot fuse-bind is a dead end on A16 定制系统: the bind
     *  source open() is SELinux-denied and SILENTLY skipped — both bind targets
     *  then just show rootfs content; device-proven by inode forensics + fd
     *  peeking). Both sides are plain host-visible dirs: guest tree lives at
     *  files/runtime/rootfs/root/{projects,sdcard} (ext4, fast) and the shared
     *  side at Android/data/.../projects (app-owned) + /storage/emulated/0
     *  (FUSE, appops-gated). A 10s mtime-diff sync keeps both visible. */
    /** Storage bridge v2 (user requirement: project files survive app
     *  uninstall/reinstall/re-stage). Scope-locked redesign after the P0:
     *   - ONLY the dedicated exchange roots — /storage/emulated/0/DSH/{projects,
     *     sdcard} on the user-visible side (survives uninstall), mirrored with
     *     the guest working copies /root/{projects,sdcard} inside the rootfs;
     *   - NEVER scans the whole storage root, never touches anything outside
     *     the two dedicated dirs;
     *   - atomic writes (.part + rename) BOTH directions — no partial file can
     *     ever overwrite an original (the P0 failure mode);
     *   - newer-mtime wins, files are NEVER deleted;
     *   - the guest working copies are re-stage-protected (stash) so the sync
     *     and the wipe never race (the other P0 failure mode).
     *  Recovery: after uninstall the internal copies are gone; the first sync
     *  tick pulls everything back from the shared side. */
    private val bridgePairs: List<Pair<File, File>> by lazy {
        val shared = File("/storage/emulated/0/DSH")
        val gRoot = File(AssetInstaller.runtimeDir(this), "rootfs/root")
        listOf(
            File(shared, "projects") to File(gRoot, "projects"),
            File(shared, "sdcard") to File(gRoot, "sdcard"),
        )
    }

    private fun startStorageBridge() {
        if (bridgeThread?.isAlive == true) return
        bridgeThread = Thread {
            android.util.Log.i("dsh-loop", "bridge v2 start (dedicated dirs, atomic, no-delete)")
            while (running.get() && !Thread.currentThread().isInterrupted) {
                if (android.os.Environment.isExternalStorageManager()) {
                    for ((h, g) in bridgePairs) {
                        runCatching { syncDir(h, g) }
                    }
                }
                Thread.sleep(10_000)
            }
        }.apply { name = "dsh-storage-bridge-v2"; isDaemon = true; start() }
    }

    /** Bidirectional mirror with atomic writes and NO deletion. Both sides are
     *  host-visible plain dirs; depth-capped full recursion; newer mtime wins;
     *  dotfiles skipped. */
    private fun syncDir(host: File, guest: File) {
        if (!host.isDirectory) host.mkdirs()
        if (!guest.isDirectory) guest.mkdirs()
        copyAllSafe(host, guest, 0)
        copyAllSafe(guest, host, 0)
    }

    private fun copyAllSafe(src: File, dst: File, depth: Int) {
        if (depth > 12) return
        src.listFiles()?.forEach { f ->
            if (f.name.startsWith(".")) return@forEach
            if (f.isDirectory) {
                val d = File(dst, f.name)
                if (!d.isDirectory) d.mkdirs()
                copyAllSafe(f, d, depth + 1)
            } else {
                val t = File(dst, f.name)
                if (!t.exists() || t.lastModified() < f.lastModified() || t.length() != f.length()) {
                    // atomic: never leave a partial file as the final name
                    val part = File(dst, f.name + ".dshpart")
                    runCatching {
                        f.inputStream().use { ins -> part.outputStream().use { out -> ins.copyTo(out, 1 shl 16) } }
                        part.setLastModified(f.lastModified())
                        if (!part.renameTo(t)) {
                            t.delete()
                            part.renameTo(t)
                        }
                    }.onFailure {
                        runCatching { part.delete() }
                    }
                }
            }
        }
    }
    private var bridgeThread: Thread? = null

    private fun awaitFuseView(): Boolean {
        val root = java.io.File("/storage/emulated/0")
        var n = 0
        while (n < 30) { // ≤90s
            val ok = runCatching {
                root.isDirectory && (root.list()?.size ?: 0) > 3
            }.getOrDefault(false)
            if (ok) { android.util.Log.i("dsh-loop", "fuse-ready after ${n * 3}s"); return true }
            Thread.sleep(3000); n++
        }
        android.util.Log.i("dsh-loop", "fuse-ready TIMEOUT (spawning storageless)")
        return false
    }

    private fun runEngineOnce(): RunOutcome {
        updateNotification("引擎启动中…")
        reapStaleGuests()
        // Bind-domain A/B: run the EXACT storage-bind sanity the way my run-as
        // tests did (BIND-OK there) vs this process (maptest deny). Result goes
        // to the host log — tells whether the difference is process-domain
        // (mountns/selinux) or something else entirely.
        runCatching {
            val t = guestExec(40_000L,
                "export PATH=/usr/bin:/bin:/usr/local/bin; " +
                "ls /root/sdcard >/dev/null 2>&1 && echo INAPP-BIND-OK || echo INAPP-BIND-FAIL")
            android.util.Log.i("dsh-loop", "bind-ab ${t.second.trim()}")
        }
        val base = AssetInstaller.runtimeDir(this)
        val proot = File(base, "proot/proot")
        val rootfs = File(base, "rootfs")
        // (staging happens earlier in superviseLoop so it outranks halt; by here
        //  isReady is guaranteed unless a concurrent failure removed the tree)
        if (!AssetInstaller.isReady(this)) return RunOutcome(-1, -1)
        if (!proot.canExecute() || !rootfs.isDirectory) {
            updateNotification("资产不完整：缺运行组件（proot/镜像）— 请在设置页重新导入")
            return RunOutcome(-1, -1)
        }
        // Runtime ABI gate (the APK itself carries no native code, so this cannot
        // be expressed as an install-time filter): the delivered rootfs/proot are
        // arm64-only. x86 emulators/devs → clear message + Route A pointer.
        if (!android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) {
            updateNotification("引擎需要 arm64 设备（本机 ${android.os.Build.SUPPORTED_ABIS.joinToString("/")}）— x86 环境验证请走路线 A（Termux）")
            return RunOutcome(-1, -1)
        }
        // FILE MAPPING (工程目录 ↔ 真实环境):
        //  • NO --change-id: Android shared storage is a per-uid FUSE view; the
        //    moment the engine execs as uid 0, sdcard turns into EACCES (the old
        //    "can't sanitize binding /sdcard" warning). Rootfs files were staged
        //    by the app uid anyway — guest root was cosmetic.
        //  • L2 (always): app external files dir  → /projects   (file manager /
        //    USB-MTP reachable, zero permissions).
        //  • L3 (opt-in, Settings → 所有文件访问): whole shared storage → /sdcard.
        val binds = mutableListOf("--bind=/dev", "--bind=/proc", "--bind=/sys")
        // proot cannot follow the /storage/emulated -> ... symlink chain when it
        // opens a bind source (maptest 0/9 ever, device-verified). Candidates in
        // preference order — first app-readable & symlink-free view wins; the
        // fuse-mount entry itself usually is: /mnt/user/0/emulated. (pass_through
        // exists in mountinfo but SELinux blocks app reads — probe says false.)
        val extBases = listOf("/mnt/pass_through/0/emulated", "/mnt/user/0/emulated", "/storage/emulated/0")
        awaitFuseView() // spawn only after the FUSE view is really populated
        var extBase: String? = null
        for (c in extBases) {
            val ok = runCatching { java.io.File(c).isDirectory && java.io.File(c).listFiles() != null }.getOrDefault(false)
            android.util.Log.i("dsh-loop", "ext-cand $c=$ok")
            if (ok) { extBase = c; break }
        }
        android.util.Log.i("dsh-loop", "storage-view chosen=${extBase ?: "NONE"}")
        // App-side self-diagnostic (run-as/shell views are mountns false-negatives —
        // the ONLY trustworthy probe of the FUSE view runs here, in-app):
        val extRoot = java.io.File("/storage/emulated/0")
        android.util.Log.i("dsh-loop", "storage-view ext=${extRoot.exists()}/${extRoot.isDirectory} list=${runCatching { extRoot.list()?.size }.getOrNull()}")
        android.util.Log.i("dsh-loop", "storage-view proj=${getExternalFilesDir("projects")?.let { "${it.exists()}/${it.isDirectory}" } ?: "null"}")
        // L2 = the app's EXTERNAL files dir (file manager / USB reachable), NOT
        // the internal runtime dir (invisible outside). getExternalFilesDir can
        // be null on weird profiles — internal dir stays as a last-resort bind
        // so /projects always exists.
        val projects = (getExternalFilesDir("projects") ?: File(base, "projects"))
        if (!projects.exists()) projects.mkdirs()
        // GUEST TARGET = /root/projects & /root/sdcard (real HOME dirs): the DSH
        // workspace registry realpath-rejects symlink roots (device-verified) —
        // binding INTO home dirs keeps canonical paths inside home, and the
        // seeded sidebar workspaces (AssetInstaller) point here.
        val projSrc = extBase?.let { java.io.File("$it/Android/data/$packageName/files/projects") } ?: projects
        extBase?.let { runCatching { projSrc.mkdirs() } }
        // Bind TARGET dirs must exist in the rootfs (proot binds to existing
        // mount points; a fresh re-stage by an older APK can leave them out —
        // maptest "No such file" 2026-11-20). Cheap idempotent insurance.
        File(rootfs, "root/projects").mkdirs()
        File(rootfs, "root/sdcard").mkdirs()
        binds += "--bind=${projSrc.absolutePath}:/root/projects"
        if (android.os.Environment.isExternalStorageManager()) {
            val sd = extBase ?: "/storage/emulated/0"
            android.util.Log.i("dsh-loop", "sdcard-source=$sd proj-source=${projSrc.absolutePath}")
            binds += "--bind=$sd:/root/sdcard"
        }
        val argv = listOf(
            proot.absolutePath,
            "--rootfs=$rootfs", "--link2symlink", "--sysvipc",
            "--kill-on-exit", "-L",
            *binds.toTypedArray(),
            // (guest DNS: Android has no host /etc/resolv.conf to bind — the
            //  resolver file is created INSIDE the rootfs at staging time.)
            "/usr/local/bin/dsh-serve",
        )
        return try {
            hostLog.parentFile?.mkdirs()
            rotateHostLogIfNeeded()
            val pb = ProcessBuilder(argv)
                // cwd inside the guest rootfs → maps to "/" (kills the old
                // "can't chdir(…/files/./.)" warning; deterministic workspace anchor)
                .directory(rootfs)
                .redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("DSH_PORT", "3080")
                // proot runtime contract (proot-build-recipe-report §E): the bundled
                // loader blob extracts here and exec's via /proc/self/fd — the dir
                // MUST be executable (proot access(X_OK)-checks it).
                val ptmp = File(base, "tmp").apply { mkdirs() }
                put("PROOT_TMP_DIR", ptmp.absolutePath)
                // Termux-repack builds are unbundled: loader + libtalloc ship next
                // to the binary; env overrides any compiled-in $PREFIX paths.
                File(base, "proot/loader").takeIf { it.isFile }?.let {
                    put("PROOT_LOADER", it.absolutePath)
                }
                File(base, "proot/lib").takeIf { it.isDirectory }?.let {
                    put("LD_LIBRARY_PATH", it.absolutePath)
                }
            }
            val startedAt = System.currentTimeMillis()
            // Drop the previous run's URL BEFORE spawning: a stale token file
            // lets MainActivity load a dead port while the new engine is still
            // booting → WebView sits on ERR_CONNECTION_REFUSED forever (device-
            // observed first-run race). The wait poller only sees fresh URLs.
            File(filesDir, URL_FILE).delete()
            lastUrl = null
            val p = pb.start()
            proc = p
            var urlNotified = false
            FileWriter(hostLog, true).use { logw ->
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        logw.append(line).append('\n')
                        val m = Regex("""(http://127\.0\.0\.1:\d+/[^\s]*)""").find(line)
                        if (m != null) {
                            lastUrl = m.groupValues[1]
                            File(filesDir, URL_FILE).writeText(lastUrl!!)
                            if (!urlNotified) {
                                urlNotified = true
                                updateNotification(
                                    if (android.os.Environment.isExternalStorageManager())
                                        "DSH 引擎运行中 · 侧栏「共享存储/工程交换区」可直接开工"
                                    else
                                        "DSH 引擎运行中 · 共享存储未联通 — 点「存储映射」授权",
                                )
                            }
                        }
                        // open-in-app bridge: the patched guest service emits
                        // DSH_ANDROID_OPEN:<guest abs path> instead of spawning
                        // desktop openers (no xdg-open on Android).
                        if (line.startsWith("DSH_ANDROID_OPEN:")) {
                            val guestPath = line.removePrefix("DSH_ANDROID_OPEN:").trim()
                            if (guestPath.startsWith("/")) {
                                val f = File(AssetInstaller.runtimeDir(this), "rootfs" + guestPath)
                                val root = File(AssetInstaller.runtimeDir(this), "rootfs")
                                val inside = runCatching {
                                    f.canonicalPath.startsWith(root.canonicalPath)
                                }.getOrDefault(false)
                                if (inside && f.isFile) showFileInViewer(f, guestPath)
                                else android.util.Log.i("dsh-loop", "open request rejected/missing: $guestPath")
                            }
                        }
                    }
                }
            }
            val rc = p.waitFor()
            proc = null
            RunOutcome(rc, System.currentTimeMillis() - startedAt)
        } catch (t: Throwable) {
            android.util.Log.e("dsh-stage", "engine run failed", t)
            updateNotification("引擎进程异常：${t.message}")
            RunOutcome(-1, -1)
        }
    }

    private fun handleOperatorAction(action: String?) {
        android.util.Log.i("dsh-loop", "operator action=$action running=${running.get()}")
        Thread {
            val line = when (action) {
                ACTION_EXPORT -> runCatching {
                    "诊断包已生成：${DiagnosticsExporter.export(this).absolutePath}"
                }.getOrElse { "诊断导出失败：${it.message}" }
                ACTION_RESTORE -> {
                    val r = ladder.restoreParkedProfiles()
                    ladder.resetForManualRetry()
                    r
                }
                ACTION_RETRY -> {
                    ladder.resetForManualRetry()
                    if (worker?.isAlive != true && running.get()) {
                        worker = Thread { superviseLoop() }.apply { name = "dsh-supervisor"; isDaemon = true; start() }
                        "已重置救援计数，监督循环已重启"
                    } else "已重置救援计数，立即重试"
                }
                ACTION_CHECK_UPDATE -> checkForUpdate()
                else -> "?"
            }
            updateNotification(line)
            synchronized(actionRequested) { actionRequested.set(true); (actionRequested as Object).notifyAll() }
        }.start()
    }

    // ---- check for updates ----------------------------------------------------
    // Update base = prefs "update_base" (set via: adb shell am start -n
    // com.dsh.android/.MainActivity --es update_base https://your.host/dir) —
    // must serve SHA256SUMS-update.txt + dsh-update-<ver>.tar.gz side by side
    // (b2-build-all.ps1 emits both). Empty base = OFFLINE mode: rescan
    // incoming/, so a package dropped via file manager/USB applies on tap too.

    private fun versionCacheFile() = File(AssetInstaller.runtimeDir(this), "dsh-version.txt")

    private fun localDshVersion(): String {
        // Truth order: cached `dsh --version` (written by the updater, reflects
        // the effective bin — pnpm global uses a versioned v11 layout not
        // matchable by a static path) → npm-style global tree → baked preload.
        runCatching { versionCacheFile().readText().trim() }
            .getOrNull()?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+[-\w.]*""")) }?.let { return it }
        val local = File(AssetInstaller.runtimeDir(this), "rootfs/usr/local")
        for (dir in listOf("dsh/node_modules", "lib/node_modules", "node_modules")) {
            runCatching {
                Regex(""""version"\s*:\s*"([^"]+)"""").find(
                    File(local, "$dir/@deepseek-ai/dsh/package.json").readText(),
                )?.groupValues?.get(1)?.let { return it }
            }
        }
        return "?"
    }

    /** Run one command inside the guest (same bind/env contract as the engine
     *  spawn) capturing combined output. pnpm extracts with worker_threads —
     *  NO per-file execve — so guest updates dodge the proot ptrace fork races
     *  that made guest npm unreliable (field-notes #8; pnpm in-guest E2E proven
     *  by plugin installs). */
    private fun guestExec(timeoutMs: Long, command: String): Pair<Int, String> {
        val base = AssetInstaller.runtimeDir(this)
        val rootfs = File(base, "rootfs")
        val argv = mutableListOf(
            File(base, "proot/proot").absolutePath,
            "--rootfs=$rootfs", "--link2symlink", "--sysvipc", "--kill-on-exit", "-L",
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
        )
        // Same symlink-free bind source selection as the engine spawn.
        val extBase = listOf("/mnt/pass_through/0/emulated", "/mnt/user/0/emulated")
            .firstOrNull { runCatching { java.io.File(it).isDirectory && java.io.File(it).listFiles() != null }.getOrDefault(false) }
        val projects = extBase?.let { File("$it/Android/data/$packageName/files/projects") }
                       ?: (getExternalFilesDir("projects") ?: File(base, "projects"))
        argv += "--bind=${projects.absolutePath}:/root/projects"
        if (android.os.Environment.isExternalStorageManager()) {
            argv += "--bind=${extBase ?: "/storage/emulated/0"}:/root/sdcard"
        }
        argv += "/usr/bin/bash"
        argv += "-c"
        argv += command
        return try {
            val pb = ProcessBuilder(argv).directory(rootfs).redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", "/root")
                put("PATH", "/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("PROOT_TMP_DIR", File(base, "tmp").absolutePath)
                File(base, "proot/loader").takeIf { it.isFile }?.let { put("PROOT_LOADER", it.absolutePath) }
                File(base, "proot/lib").takeIf { it.isDirectory }?.let { put("LD_LIBRARY_PATH", it.absolutePath) }
            }
            val p = pb.start()
            // Drain stdout on a worker so a hung proot cannot block readText
            // past the timeout (device 2026-11-20: view query sat "checking…"
            // forever because readText precedes waitFor in the old order).
            val outRef = java.util.concurrent.atomic.AtomicReference<String>("")
            val drainer = Thread {
                runCatching {
                    p.inputStream.bufferedReader().use { r ->
                        outRef.set(r.readText())
                    }
                }
            }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                runCatching { p.destroy() }
                runCatching { drainer.join(3000) }
                return -1 to (outRef.get().trim() + "\n命令超时")
            }
            drainer.join(5000)
            // Forensic trail: every guest exec result lands here (定制系统 swallows
            // notification updates after reinstall; this file is the honest one).
            runCatching {
                File(AssetInstaller.runtimeDir(this), "guest-exec-last.log")
                    .writeText("${System.currentTimeMillis()} rc=${p.exitValue()} argv-tail=${command.take(60)}\n${outRef.get().take(400)}\n")
            }
            p.exitValue() to outRef.get()
        } catch (e: Exception) {
            -1 to "guest 执行失败：${e.javaClass.simpleName} ${e.message}"
        }
    }

    // ---- check for updates ----------------------------------------------------
    // DSH itself ships on npm (@deepseek-ai/dsh) — THAT is the official channel;
    // ⟳ queries the registry (guest ~/.npmrc → official CN mirror by default),
    // then installs via guest pnpm global: `pnpm add -g --prefix /usr/local`
    // keeps the npm layout (lib/node_modules + /usr/local/bin/dsh entry link).
    // The shell APK never self-updates; offline tarballs in incoming/ remain an
    // ops fallback handled by the storage watcher.

    private fun checkForUpdate(): String {
        // pnpm bootstrap: the repair/update path needs pnpm even if a re-stage
        // (older APK's rootfs tar) dropped it — self-heal the bin link first.
        val pnpmBin = File(AssetInstaller.runtimeDir(this), "rootfs/usr/local/bin/pnpm")
        if (!pnpmBin.exists()) runCatching {
            java.nio.file.Files.createSymbolicLink(
                pnpmBin.toPath(), java.nio.file.Paths.get("../node_modules/pnpm/pnpm"))
            java.io.File(pnpmBin.parentFile.parentFile, "node_modules/pnpm/pnpm")
                .takeIf { it.isFile }?.setExecutable(true)
            android.util.Log.i("dsh-loop", "pnpm link self-healed")
        }
        val local = localDshVersion()
        updateNotification("正在检查官方 npm 仓库的 DSH 版本…")
        val (rc1, view) = guestExec(45_000, "pnpm view @deepseek-ai/dsh version 2>&1")
        val remote = view.trim().lines().lastOrNull { it.matches(Regex("""\d+\.\d+\.\d+[-\w.]*""")) }
        if (rc1 != 0 || remote == null) {
            val why = view.lines().lastOrNull { it.isNotBlank() }?.take(90) ?: "rc=$rc1"
            return "无法获取官方最新版本（$why）；可将更新包放入 incoming 文件夹离线安装"
        }
        if (remote == local) return "已是最新版本 DSH $remote"
        updateNotification("发现新版本 DSH $local → $remote，正在安装…")
        // Why this shape (all device-proven the hard way):
        //  • pnpm GLOBAL mode = v11 content-addressed links + generated shims;
        //    any SIGKILL leaves .l2s residue under --link2symlink and the tree
        //    becomes unresolvable (MODULE_NOT_FOUND for every spawn). `--prefix`
        //    is an npm flag pnpm ignores; `--node-linker` is ignored for globals.
        //  • guest npm = proot ptrace fork races (field-notes #8).
        // So: a self-contained hoisted *project* at /usr/local/dsh (pnpm in-
        // process extraction, real directories, zero symlink games) + a hand-
        // written shim flipping the bin only after the tree installed cleanly.
        val cmd = "rm -rf /usr/local/dsh && mkdir -p /usr/local/dsh && cd /usr/local/dsh" +
            " && printf '%s' '{\"name\":\"dsh-root\",\"private\":true}' > package.json" +
            " && pnpm add --node-linker=hoisted @deepseek-ai/dsh@$remote"
        val (rc2, add) = guestExec(15 * 60_000L, "$cmd 2>&1")
        if (rc2 != 0) {
            val why = add.lines().lastOrNull { it.isNotBlank() }?.take(120) ?: "rc=$rc2"
            return "安装 $remote 失败：$why（可稍后重试）"
        }
        // Flip the entry point last; verify it answers.
        val (rc3, shim) = guestExec(60_000L,
            "printf '#!/bin/sh\\nexec /node/bin/node /usr/local/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js \"\$@\"\\n'" +
            " > /usr/local/bin/dsh && chmod 0755 /usr/local/bin/dsh && dsh --version 2>&1")
        if (rc3 != 0) {
            val why = shim.lines().lastOrNull { it.isNotBlank() }?.take(120) ?: "rc=$rc3"
            return "更新 $remote 已安装但入口切换失败：$why（引擎将沿用旧版）"
        }
        // Effective-bin truth for future checks + top bar.
        val effective = shim.lines().lastOrNull { it.matches(Regex("""\d+\.\d+\.\d+[-\w.]*""")) } ?: remote
        runCatching { versionCacheFile().writeText(effective) }
        // Legacy note: prefs "update_base" no longer used — npm registry IS the
        // official channel now (see checkForUpdate comment). Offline tarballs in
        // incoming/ remain an ops fallback handled by the storage watcher.
        runCatching { proc?.destroy() } // next supervision cycle spawns the new engine
        return "DSH 已更新 $local → $effective，引擎正在重启"
    }

    /** In-app viewer for "打开配置文件" (no OS file viewer on Android): read the
     *  guest file (capped) and show it in a transparent Activity dialog — the
     *  engine Service cannot show dialogs itself. */
    private fun showFileInViewer(f: File, guestPath: String) {
        val content = runCatching {
            f.inputStream().use { ins ->
                val buf = ByteArray(256 * 1024)
                val n = ins.read(buf)
                String(if (n == buf.size) buf else buf.copyOf(n), Charsets.UTF_8)
            }
        }.getOrElse { "（读取失败：${it.message}）" }
        val tail = if (f.length() > 256 * 1024) "\n\n…（文件过大，仅显示前 256KB）" else ""
        val i = Intent(this, FileViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("path", guestPath)
            .putExtra("content", content + tail)
        startActivity(i)
    }

    private fun rotateHostLogIfNeeded() {
        if (hostLog.length() > HOST_LOG_CAP) {
            runCatching {
                val old = File(hostLog.parentFile, "host-stdout.log.1")
                old.delete()
                hostLog.renameTo(old)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(requestCode: Int, label: String, act: String): Notification.Action =
            Notification.Action.Builder(
                null, label,
                PendingIntent.getService(
                    this, requestCode,
                    Intent(this, DshEngineService::class.java).setAction(act),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
        // 导出诊断走 Activity hop（service 不能弹分享界面），其余走 service。
        val exportAction = Notification.Action.Builder(
            null, "导出诊断",
            PendingIntent.getActivity(
                this, 10,
                Intent(this, DiagShareActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DSH Pocket")
            .setContentText(text)
            // 通知小图标必须是单色剪影（系统会整体着色）
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(exportAction)
            // 未授予「所有文件访问」时提供存储映射入口（授予后按钮自然消失）
            .apply {
                if (!android.os.Environment.isExternalStorageManager()) {
                    addAction(Notification.Action.Builder(
                        null, "存储映射",
                        PendingIntent.getActivity(
                            this@DshEngineService, 13,
                            Intent(this@DshEngineService, StorageGateActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    ).build())
                }
            }
            .addAction(action(11, "恢复插件", ACTION_RESTORE))
            .addAction(action(12, "重试", ACTION_RETRY))
            .addAction(action(14, "检查更新", ACTION_CHECK_UPDATE))
            .build()
    }

    @Volatile private var lastNotifyAt = 0L
    private fun updateNotification(text: String) {
        lastStatus = text
        // Time gate: progress bursts must not starve worker threads with binder
        // posts (定制系统 notification pipeline is heavy — device-verified tail stall).
        // State-transition texts bypass the gate.
        val now = System.currentTimeMillis()
        val critical = text.startsWith("资产缺失") || text.contains("异常") ||
            text.contains("已停止") || text.contains("运行中") || text.contains("救援") ||
            text.startsWith("已是最新") || text.contains("已更新") || text.contains("正在安装") ||
            text.contains("无法获取")
        if (!critical && now - lastNotifyAt < 2000) return
        lastNotifyAt = now
        // 定制系统 quirk (2026-11-20, post-reinstall): in-place notify(id) updates get
        // silently swallowed by the system server for THIS app. Re-cancel+repost
        // forces the update through the usual path.
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}

package com.dsh.android

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Stages engine assets from the app-specific external directory into private
 * runtime layout, with integrity verification and version stamps.
 *
 * Input (drop via `scripts/b2-stage-shell-assets.ps1`, i.e. `adb push`):
 *   <getExternalFilesDir(null)>/incoming/
 *     dsh-appliance-<ver>-rootfs.tar.gz     plain rootfs tarball (buildx type=tar output)
 *     SHA256SUMS.txt                        sha256sum format: "<hex64>  <filename>"
 *     proot                                 static aarch64 proot binary (required to run)
 *
 * Output:
 *   files/runtime/rootfs/                   extracted rootfs (atomic .tmp + rename)
 *   files/runtime/proot/proot               executable
 *   files/runtime/runtime.stamp             "rootfs=<file> sha256=<hex> proot=<hex|none> installedAt=<iso>"
 *
 * Discipline (learned from prior art): any asset swap MUST change the stamp;
 * a stale stamp with a new file re-stages; failures never leave a half tree.
 */
object AssetInstaller {

    data class Plan(
        val rootfs: File,
        val proot: File?,
        val expected: Map<String, String>, // filename -> lowercase hex sha256
        val update: File? = null,          // newest dsh-update-*.tar(.gz) payload
    )

    class StageException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun incomingDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "incoming")

    /** Internal staging dir: app-owned and ALWAYS writable. After an uninstall
     *  + reinstall the app FUSE view may not show shell-created files in the
     *  external incoming at all (EACCES even on create — device-proven), so
     *  the asset seed and findPlan fall back to here. */
    fun internalIncomingDir(ctx: Context) = File(ctx.filesDir, "incoming")

    /** PRODUCTION asset bundling: the APK ships the full engine set in assets/
     *  (user requirement — new users must never hunt for files). On first run,
     *  when neither incoming dir has a rootfs yet, copy the bundled trio into
     *  the INTERNAL incoming (always writable — the external one is subject to
     *  FUSE perm quirks after reinstall, device-proven). One-time; later
     *  manual/official packages in either dir win by mtime. The WHOLE seed is
     *  fail-safe: it must never crash the main thread (crashed the Activity and
     *  the supervisor during the reinstall E2E). */
    fun seedFromAssetsIfNeeded(ctx: Context) {
        runCatching {
            val external = incomingDir(ctx)
            val hasRootfsExternal = external.listFiles { f: File -> f.isFile && f.name.contains("-rootfs.tar") }?.isNotEmpty() == true
            val internal = internalIncomingDir(ctx)
            if (!hasRootfsExternal) {
                val hasRootfsInternal = internal.listFiles { f: File -> f.isFile && f.name.contains("-rootfs.tar") }?.isNotEmpty() == true
                if (hasRootfsInternal) return
                val am = ctx.assets
                val bundled = runCatching { am.list("")?.toSet().orEmpty() }.getOrDefault(emptySet())
                val needed = listOf("dsh-appliance-rootfs.tar", "proot-bundle.tar", "SHA256SUMS.txt")
                if (!needed.all { it in bundled }) return // tiny build without assets → SetupActivity flow
                internal.mkdirs()
                needed.forEach { name ->
                    val dst = File(internal, name)
                    if (dst.isFile && dst.length() > 0) return@forEach
                    val part = File(internal, "$name.part")
                    android.util.Log.i("dsh-stage", "seeding $name from APK assets…")
                    am.open(name).use { input ->
                        part.outputStream().use { out ->
                            input.copyTo(out, 1 shl 20)
                        }
                    }
                    if (!part.renameTo(dst)) {
                        // EXDEV guard (device lesson): same-volume rename should work;
                        // if the fs refuses, copy+delete instead of dropping the seed.
                        part.copyTo(dst, overwrite = true)
                        part.delete()
                    }
                    android.util.Log.i("dsh-stage", "seeded $name (${dst.length()} bytes)")
                }
            }
        }.onFailure {
            android.util.Log.e("dsh-stage", "asset seed failed (non-fatal): ${it.message}")
        }
    }

    fun runtimeDir(ctx: Context) = File(ctx.filesDir, "runtime")

    fun isReady(ctx: Context): Boolean {
        val r = runtimeDir(ctx)
        return File(r, "proot/proot").canExecute() && File(r, "rootfs/usr").isDirectory
    }

    /** Newest `*-rootfs.tar(.gz|.xz)` across BOTH incoming dirs + optional proot
     *  + parsed SHA256SUMS. The internal dir is the reliable fallback when the
     *  external FUSE view is broken after reinstall (device-proven). The proot
     *  bundle is chosen from the SAME dir as the selected rootfs (sums coherence). */
    fun findPlan(ctx: Context): Plan? {
        seedFromAssetsIfNeeded(ctx)
        var best: File? = null
        var bestDir: File? = null
        var update: File? = null
        for (dir in listOf(incomingDir(ctx), internalIncomingDir(ctx))) {
            dir.listFiles { f: File -> f.isFile }?.forEach { f ->
                if (f.name.contains("-rootfs.tar") && f.lastModified() > (best?.lastModified() ?: 0)) {
                    best = f; bestDir = dir
                }
                if (f.name.startsWith("dsh-update-") && (f.name.endsWith(".tar") || f.name.endsWith(".tar.gz"))
                    && f.lastModified() > (update?.lastModified() ?: 0)) update = f
            }
        }
        val rootfs = best ?: return null
        val dir = bestDir!!
        // plan.proot is a PRE-EXTRACTED binary (legacy incoming layout) — the
        // proot-bundle TAr is located separately by stageIfNeeded (bundleTar)
        // and extracted there; selecting the tar here made the stage copy the
        // raw archive AS the binary (shell executed tar headers, early-exit x6,
        // HALT). Only a file literally named "proot" qualifies.
        val proot = File(dir, "proot").takeIf { it.isFile }
        val sums = File(dir, "SHA256SUMS.txt").takeIf { it.isFile }?.useLines { lines ->
            lines.mapNotNull { line ->
                val m = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").find(line.trim()) ?: return@mapNotNull null
                m.groupValues[2].substringAfterLast('/') to m.groupValues[1].lowercase()
            }.toMap()
        } ?: emptyMap()
        return Plan(rootfs, proot, sums, update)
    }

    // ── in-app DSH update (no guest npm/pnpm, no ptrace races) ──────────────
    // Payload = preload-shaped tar (usr/local/node_modules/** + usr/local/bin/dsh).
    // Native streaming extract with the guarded TarExtractor; applied-once via a
    // dedicated stamp (the update package must NOT force a full rootfs re-stage).
    private fun updateStampFile(ctx: Context) = File(runtimeDir(ctx), "update.stamp")

    fun applyUpdateIfAny(ctx: Context, onProgress: (String) -> Unit = {}): String? {
        val plan = findPlan(ctx) ?: return null
        val pkg = plan.update ?: return null
        if (!isReady(ctx)) return null // full stage will carry the same preload anyway
        val sha = sha256(pkg)
        if (runCatching { updateStampFile(ctx).readText().trim() }.getOrNull() == sha) return null
        plan.expected[pkg.name]?.let { want ->
            if (want != sha) throw StageException("更新包校验失败：期望 ${want.take(12)}…，实际 ${sha.take(12)}…")
        }
        onProgress("正在更新 DSH 引擎…")
        val rootfs = File(runtimeDir(ctx), "rootfs")
        pkg.inputStream().use { raw ->
            val gz = if (pkg.name.endsWith(".gz")) java.util.zip.GZIPInputStream(raw, 1 shl 16) else raw
            val n = TarExtractor.extract(gz, rootfs) { e -> onProgress("已解出 $e 个条目…") }
            if (n == 0) throw StageException("更新包为空或不是 tar")
        }
        updateStampFile(ctx).writeText(sha)
        val ver = runCatching {
            Regex(""""version"\s*:\s*"([^"]+)"""").find(
                File(rootfs, "usr/local/node_modules/@deepseek-ai/dsh/package.json").readText(),
            )?.groupValues?.get(1)
        }.getOrNull() ?: "?"
        android.util.Log.i("dsh-stage", "update applied n=pkg=${pkg.name} dsh=$ver")
        return "DSH 已更新到 $ver，引擎即将重启"
    }

    /**
     * Stage if the stamp doesn't already match the plan. Returns a human line for
     * the notification; throws [StageException] with user-actionable messages.
     */
    fun stageIfNeeded(ctx: Context, onProgress: (String) -> Unit): String {
        if (isReady(ctx)) {
            val plan = findPlan(ctx) ?: return "已就绪（无新资产）"
            if (stampMatches(ctx, plan)) return "已就绪（版本戳一致）"
        }
        val plan = findPlan(ctx)
            ?: throw StageException("缺少系统镜像文件：请在设置页导入 *-rootfs.tar.gz")
        return stage(ctx, plan, onProgress)
    }

    private fun stampFile(ctx: Context) = File(runtimeDir(ctx), "runtime.stamp")

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(65536)
        FileInputStream(file).use { fin ->
            val din = DigestInputStream(fin, md)
            while (din.read(chunk) > 0) { /* stream only, never buffer */ }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** bundle/proot identity that must match the stamp for staging to be skipped */
    private fun prootStampValue(ctx: Context, plan: Plan): String =
        incomingDir(ctx).listFiles { f: File ->
            f.isFile && f.name.startsWith("proot-bundle") && f.name.contains(".tar")
        }?.maxByOrNull { it.lastModified() }?.let { sha256(it) }
            ?: plan.proot?.let { sha256(it) } ?: "none"

    private fun stampText(plan: Plan, hash: String, ctx: Context): String =
        "rootfs=${plan.rootfs.name} sha256=$hash" +
            " proot=" + prootStampValue(ctx, plan) +
            " installedAt=${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"

    private fun stampMatches(ctx: Context, plan: Plan): Boolean {
        val stamp = stampFile(ctx).takeIf { it.isFile }?.readText() ?: return false
        val want = sha256(plan.rootfs)
        return stamp.contains("rootfs=${plan.rootfs.name}") && stamp.contains("sha256=$want") &&
            stamp.contains("proot=${prootStampValue(ctx, plan)}")
    }

    /** Public check used by the supervisor loop (staging must outrun halt). */
    fun stampMatchesPublic(ctx: Context): Boolean = findPlan(ctx)?.let { stampMatches(ctx, it) } ?: false

    private fun verifyOrThrow(file: File, name: String, expected: Map<String, String>) {
        val want = expected[name] ?: return // no checksum declared → warn-tolerant pass
        val got = sha256(file)
        if (got != want) throw StageException("$name 校验失败：期望 ${want.take(12)}…，实际 ${got.take(12)}…")
    }

    private fun stage(ctx: Context, plan: Plan, onProgress: (String) -> Unit): String {
        android.util.Log.i("dsh-stage", "stage-enter rootfs=${plan.rootfs.length()}B")
        val runtime = runtimeDir(ctx).apply { mkdirs() }
        verifyOrThrow(plan.rootfs, plan.rootfs.name, plan.expected)
        plan.proot?.let { verifyOrThrow(it, "proot", plan.expected) }
        // Bundle integrity BEFORE touching the rootfs tree: a stale SHA256SUMS
        // (assets pushed out of sync) must never wipe a working, pre-baked tree
        // (twice destroyed 708 MB of baked node_modules on device).
        val bundleTar = listOf(incomingDir(ctx), internalIncomingDir(ctx))
            .flatMap { dir -> dir.listFiles { f: File -> f.isFile && f.name.startsWith("proot-bundle") && f.name.contains(".tar") }?.toList() ?: emptyList() }
            .maxByOrNull { it.lastModified() }
        bundleTar?.let { verifyOrThrow(it, it.name, plan.expected) }
        android.util.Log.i("dsh-stage", "verify-done")

        // Direct extraction into the final rootfs dir. NO tmp+rename: Android
        // apps see /data as MULTIPLE mounts (ext4 + FUSE-bindings created by
        // proot --bind), which turns rename(2) into EXDEV — File.renameTo then
        // silently degrades to a shallow copy that LOSES top-level symlinks
        // (bin/lib/sbin vanished; device-verified). Sweeper wipes any stale tree
        // asynchronously; here we remove only the destination itself.
        val old = File(runtime, "rootfs")
        // Persistence across re-stages (audit A2): the in-app update layer
        // (/usr/local/dsh + bin shim), DSH profiles (API keys!), and workspaces
        // live INSIDE the tree this wipes — a rootfs refresh silently downgraded
        // updated engines and (pm clear audit) would silently destroy user keys.
        // Stash them tree-side, extract, replant. Same-volume renameTo first;
        // copy+delete fallback for cross-mount (EXDEV, device lesson #7).
        val stash = File(ctx.filesDir, "stage-stash")
        runCatching { stash.deleteRecursively() }
        val preserve = listOf(
            "root/.dsh", "root/dsh-workspace", "root/.npmrc",
            "usr/local/dsh", "usr/local/bin/dsh",
            // Workspace working copies live here now (fuse-bind is dead on
            // A16 定制系统): losing them on re-stage destroys user projects.
            "root/projects", "root/sdcard",
        )
        val stashed = preserve.filter { rel ->
            val src = File(old, rel)
            src.exists() && runCatching {
                val dst = File(stash, rel)
                dst.parentFile?.mkdirs()
                src.renameTo(dst) || (src.copyRecursively(dst, overwrite = true) && src.deleteRecursively())
            }.getOrDefault(false)
        }
        if (stashed.isNotEmpty()) android.util.Log.i("dsh-stage", "stashed=${stashed.joinToString()}")
        old.deleteRecursively()
        old.mkdirs()
        android.util.Log.i("dsh-stage", "extract-next (in-place)")
        onProgress("解包系统镜像（首次约 1–3 分钟）…")
        // File.inputStream() == plain FileInputStream: EOF returns -1 (verified),
        // no content:// indirection anywhere in this path.
        plan.rootfs.inputStream().use { raw ->
            val gz = if (plan.rootfs.name.endsWith(".gz")) GZIPInputStream(raw, 1 shl 16) else raw
            val n = TarExtractor.extract(gz, old) { entries -> onProgress("已解出 $entries 个条目…") }
            android.util.Log.i("dsh-stage", "extract-done n=$n")
            if (n == 0) throw StageException("rootfs 解包结果为空（归档损坏或不是 tar）")
        }
        // Guest DNS: ubuntu-base ships an empty /etc/resolv.conf and Android has
        // no host file to bind — write public resolvers into the tree itself.
        runCatching {
            val etc = File(old, "etc")
            if (etc.isDirectory) File(etc, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        // uv_os_get_passwd: libuv looks up passwd by the REAL uid — proot does
        // not fake uid (change-id breaks FUSE), so without an entry for the app
        // uid, os.userInfo()/terminal creation die with ENOENT (device-proven).
        // Uid shifts across reinstalls → compute from myUid here.
        runCatching {
            val etc = File(old, "etc")
            if (etc.isDirectory) {
                val uid = android.os.Process.myUid()
                val pw = File(etc, "passwd")
                if (pw.isFile && pw.readText().lineSequence().none { it.split(":").getOrNull(2) == uid.toString() }) {
                    pw.appendText("dsh:x:$uid:$uid:Android app uid:/root:/bin/bash\n")
                }
                val gr = File(etc, "group")
                if (gr.isFile && gr.readText().lineSequence().none { it.split(":").getOrNull(2) == uid.toString() }) {
                    gr.appendText("dsh:x:$uid:\n")
                }
            }
        }
        // Replay the bundled @dsh/android-bridge plugin into the profile:
        // pnpm operations prune manually-placed node_modules dirs (device-proven),
        // so every stage re-copies the plugin files and re-asserts its loader
        // insert line. No-op when the profile dir has not been created yet.
        runCatching {
            val profile = File(old, "root/.dsh/profiles/web")
            if (!profile.isDirectory) return@runCatching
            val dst = File(profile, "node_modules/@dsh/android-bridge")
            dst.mkdirs()
            val pluginFiles = listOf(
                Pair("android-bridge/package.json", "package.json"),
                Pair("android-bridge/cordis.patch.yml", "cordis.patch.yml"),
                Pair("android-bridge/lib/index.js", "lib/index.js"),
            )
            pluginFiles.forEach { (assetRel, targetRel) ->
                ctx.assets.open(assetRel).use { input ->
                    val out = File(dst, targetRel)
                    out.parentFile?.mkdirs()
                    input.copyTo(out.outputStream(), 1 shl 16)
                }
            }
            val patch = File(profile, "cordis.patch.yml")
            if (patch.isFile && !patch.readText().contains("dsh-android-bridge")) {
                patch.appendText("\n- insert:\n    - id: dsh-android-bridge\n      name: '@dsh/android-bridge'\n")
            }
            android.util.Log.i("dsh-stage", "android-bridge plugin replayed")
        }
        // Replant stashed state (see stash above): user profiles (API keys!),
        // workspaces, the update layer and its bin shim.
        for (rel in stashed) {
            val src = File(stash, rel)
            val dst = File(old, rel)
            if (!src.exists()) continue
            dst.parentFile?.mkdirs()
            val ok = runCatching {
                src.renameTo(dst) || (src.copyRecursively(dst, overwrite = true) && src.deleteRecursively())
            }.getOrDefault(false)
            android.util.Log.i("dsh-stage", if (ok) "replanted=$rel" else "replant-MISS=$rel")
        }
        runCatching { stash.deleteRecursively() }
        // Home shortcuts: the official DSH directory picker opens in the HOME
        // (guest /root) and cannot climb above it — real dirs (NOT symlinks:
        // the registry realpath-rejects symlink roots, device-verified) whose
        // mount points become bind targets at spawn (see DshEngineService
        // STORAGE_HOME_LINKS): picking 主目录/sdcard = a real dir = canonical
        // path stays /root/sdcard.
        runCatching {
            val rootHome = File(old, "root")
            if (rootHome.isDirectory) {
                File(rootHome, "projects").mkdirs()
                File(rootHome, "sdcard").mkdirs()
                File(File(rootHome, "projects"), "README.txt").takeIf { !it.exists() }?.writeText(
                    "工程交换区：把工程文件夹放这里（Android 侧 Android/data/com.dsh.android/files/projects，文件管理器/USB 可达）。\n" +
                    "git 仓库建议 clone 到 /root/dsh-workspace（共享存储不支持符号链接）；本目录适合放代码包、文档、素材。")
                File(File(rootHome, "sdcard"), "README.txt").takeIf { !it.exists() }?.writeText(
                    "这是平板共享存储的入口（需先在通知栏授予「所有文件访问」）。\n" +
                    "未授权时此目录为空属正常现象。")
            }
        }
        // First-boot convenience: seed DSH's workspace registry (storages/
        // workspace.json schema v2: global.workspaceIds + tables.workspaces rows)
        // so 共享存储 / 工程交换区 appear in the sidebar immediately — touch users
        // never need to find the directory picker.
        runCatching {
            val wsFile = File(old, "root/.dsh/storages/workspace.json")
            if (!wsFile.parentFile.isDirectory) wsFile.parentFile.mkdirs()
            if (!wsFile.exists() || wsFile.length() < 300L) {
                val now = java.time.Instant.now().toString()
                fun row(id: String, path: String, title: String) =
                    """{"workspaceId":"$id","path":"$path","title":"$title","sessionIds":[],"createdAt":"$now","updatedAt":"$now"}"""
                wsFile.writeText(
                    """{"unit":{"name":"workspace","version":2},"global":{"initialized":true,"workspaceIds":["ws-sdcard","ws-projects"],"archivedSessionIds":[],"pinnedSessionIds":[]},"tables":{"workspaces":{"ws-sdcard":${row("ws-sdcard", "/root/sdcard", "共享存储")},"ws-projects":${row("ws-projects", "/root/projects", "工程交换区")}}}}""",
                )
            }
        }

        // 2) proot runtime — bundleTar already located & verified before staging.
        val prootDir = File(runtime, "proot").apply { mkdirs() }
        if (bundleTar != null) {
            val stage = File(runtime, "proot-stage.tmp")
            stage.deleteRecursively(); stage.mkdirs()
            bundleTar.inputStream().buffered().use { raw ->
                val gz = if (bundleTar.name.endsWith(".gz")) GZIPInputStream(raw, 1 shl 16) else raw
                val n = TarExtractor.extract(gz, stage) { }
                if (n == 0) throw StageException("proot bundle 解包为空")
            }
            // bundle layout: proot-bundle/{proot,loader,lib/…} → flatten one level
            val inner = stage.listFiles()?.firstOrNull { it.isDirectory } ?: stage
            inner.listFiles()?.forEach { item ->
                val dst = File(prootDir, item.name)
                dst.deleteRecursively()
                // renameTo: same-FS move (handles the lib/ directory in one syscall);
                // File.copyTo on directories silently produced an EMPTY lib/ dir on
                // device (verified) — never rely on it here.
                if (!item.renameTo(dst)) item.copyRecursively(dst, overwrite = true)
            }
            stage.deleteRecursively()
            File(prootDir, "proot").setExecutable(true, true)
            File(prootDir, "loader").setExecutable(true, true)
            // Android 15+ bionic ignores LD_LIBRARY_PATH; the b1d-patched proot
            // carries RUNPATH=/data/data/com.dsh.android/lib — land EVERY bundled
            // runtime library there (libtalloc, libandroid-shmem, future deps).
            File(prootDir, "lib").listFiles { f: File -> f.isFile && f.name.startsWith("lib") }?.forEach { lib ->
                val ld = File(ctx.applicationInfo.dataDir, "lib").apply { mkdirs() }
                lib.copyTo(File(ld, lib.name), overwrite = true)
            }
        } else {
            plan.proot?.let { src ->
                val dst = File(prootDir, "proot")
                dst.delete()
                src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                if (!dst.setExecutable(true, true)) throw StageException("proot 无法置为可执行（文件系统 noexec？）")
            } ?: onProgress("警告：缺少运行组件（proot）— 请在设置页导入 proot-bundle.tar.gz，否则引擎无法启动")
        }

        // 3) stamp. A pending update package is CONSUMED by a full stage (the
        //    fresh rootfs already carries the same preload shape) — stamp it so
        //    applyUpdateIfAny doesn't extract it a second time.
        stampFile(ctx).writeText(stampText(plan, sha256(plan.rootfs), ctx))
        plan.update?.let { runCatching { updateStampFile(ctx).writeText(sha256(it)) } }
        return "资产就绪（rootfs=${plan.rootfs.name}）"
    }

    private fun File.inputStream() = FileInputStream(this)
    private fun File.outputStream() = FileOutputStream(this)
}

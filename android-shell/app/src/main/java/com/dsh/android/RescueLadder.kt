package com.dsh.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two-level rescue ladder for a crash-looping engine.
 *
 * Failure accounting distinguishes crash-loops from stable runs: a run that
 * printed its URL and survived `STABLE_MS` resets the counter.
 *
 *  level 0 (RUN)          — plain restarts with backoff (service policy).
 *  level 1 (PARK_PROFILE) — after [LEVEL1_FAILURES] consecutive early failures:
 *            park the web profile directory (`<rootfs>/root/.dsh/profiles/web`)
 *            aside as `web.disabled-<ts>`; the engine then recreates a clean
 *            profile. A broken third-party plugin is the most common cause of
 *            boot crashes; move-aside is reversible, nothing is deleted.
 *  level 2 (HALT)         — after [LEVEL2_FAILURES] consecutive early failures:
 *            stop auto-retrying; the operator exports diagnostics and may
 *            restore parked profiles from the notification actions.
 *
 * All state lives in `files/runtime/rescue.json`, so diagnostics can read it
 * and a process death cannot silently forget an armed level.
 */
class RescueLadder(private val ctx: Context) {

    companion object {
        const val LEVEL1_FAILURES = 3
        const val LEVEL2_FAILURES = 6
        const val STABLE_MS = 5 * 60_000L
        private const val STATE_FILE = "rescue.json"

        /** Guest profile root inside the appliance rootfs (host-visible path). */
        fun profilesDir(ctx: Context): File =
            File(AssetInstaller.runtimeDir(ctx), "rootfs/root/.dsh/profiles")
    }

    enum class Level { RUN, PARK_PROFILE, HALT }

    private val stateFile = File(runtimeDir(), STATE_FILE)
    private var failures = 0
    private var armedLevel = Level.RUN
    /** parked-aside profile dir (absolute path) → its original location. */
    private val parked = LinkedHashMap<String, String>()

    init { load() }

    fun runtimeDir() = AssetInstaller.runtimeDir(ctx)

    /** Called after each engine exit with the run's lifetime. */
    fun onExit(runMillis: Long, exitCode: Int): String {
        if (runMillis >= STABLE_MS) {
            failures = 0
            // A once-stable run that now fails again should NOT fully re-escalate
            // through level 1 again before the operator looks — keep HALT sticky
            // but allow PARK_PROFILE to re-arm once.
            if (armedLevel == Level.PARK_PROFILE) armedLevel = Level.RUN
            save()
            return "上次运行稳定 ${runMillis / 60_000}+ 分钟后退出，失败计数已重置"
        }
        failures++
        armedLevel = when {
            failures >= LEVEL2_FAILURES -> Level.HALT
            failures >= LEVEL1_FAILURES -> Level.PARK_PROFILE
            else -> Level.RUN
        }
        val note = when (armedLevel) {
            Level.PARK_PROFILE -> "连续 $failures 次异常退出：将暂存可疑插件配置，用干净配置重试"
            Level.HALT -> "连续 $failures 次早退：停止自动重试，请导出诊断或恢复插件"
            Level.RUN -> "连续 $failures 次早退（code=$exitCode），常规重启"
        }
        save()
        return note
    }

    fun shouldParkNow(): Boolean = armedLevel == Level.PARK_PROFILE && failures == LEVEL1_FAILURES

    /** Execute the PARK_PROFILE move-aside. Returns a human line for the notification. */
    fun parkWebProfile(): String {
        val dir = profilesDir(ctx)
        val web = File(dir, "web")
        if (!web.isDirectory) return "没有需要暂存的插件配置（已是干净状态）"
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val parkedDir = File(dir, "web.disabled-$ts")
        if (!web.renameTo(parkedDir)) return "暂存插件配置失败（目录无法改名，可能被占用）：${web.absolutePath}"
        parked[parkedDir.absolutePath] = web.absolutePath
        save()
        return "已暂存可疑插件配置（→ ${parkedDir.name}，未删除任何数据）"
    }

    /** Restore every parked profile (skip if the original path is occupied again). */
    fun restoreParkedProfiles(): String {
        if (parked.isEmpty()) return "没有已暂存的插件配置"
        var restored = 0
        for ((parkedPath, original) in parked.toList()) {
            val p = File(parkedPath)
            val o = File(original)
            if (p.isDirectory && !o.exists() && p.renameTo(o)) {
                parked.remove(parkedPath); restored++
            }
        }
        save()
        return if (restored > 0) "已恢复 $restored 份暂存的插件配置，将重启引擎" else "恢复未完成（原目录仍存在？）"
    }

    /** Operator pressed "retry": re-arm the ladder without wiping parked backups. */
    fun resetForManualRetry() {
        failures = 0
        armedLevel = Level.RUN
        save()
    }

    fun isHalted(): Boolean = armedLevel == Level.HALT

    /** Fresh staged assets justify leaving rescue mode: the failure cause may be gone. */
    fun liftHaltForNewAssets() {
        if (armedLevel == Level.HALT) resetForManualRetry()
    }

    fun describe(): String = "failures=$failures level=$armedLevel parked=${parked.size}"

    // ---- persistence -------------------------------------------------------

    private fun save() {
        val obj = JSONObject()
            .put("failures", failures)
            .put("level", armedLevel.name)
            .put("updatedAt", isoNow())
        val p = JSONObject()
        for ((k, v) in parked) p.put(k, v)
        obj.put("parked", p)
        stateFile.parentFile?.mkdirs()
        runCatching { stateFile.writeText(obj.toString(1)) }
    }

    private fun load() {
        val f = stateFile.takeIf { it.isFile } ?: return
        val obj = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        failures = obj.optInt("failures", 0)
        armedLevel = runCatching { Level.valueOf(obj.optString("level", Level.RUN.name)) }
            .getOrDefault(Level.RUN)
        val p = obj.optJSONObject("parked") ?: return
        for (key in p.keys()) {
            val v = p.optString(key)
            if (v.isNotEmpty()) parked[key] = v
        }
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
}

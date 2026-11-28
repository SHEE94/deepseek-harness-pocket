package com.dsh.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Accessibility bridge: window-tree introspection, gesture injection and
 *  screen capture, exposed to the DSH engine through NativeBridgeServer.
 *  Lives only while the user enables the service in system settings —
 *  NativeSettingsActivity is the entry point. */
class DshAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: DshAccessibilityService? = null
        fun isEnabled(): Boolean = instance != null
        val executor = Executors.newSingleThreadExecutor()
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* on-demand dump */ }
    override fun onInterrupt() {}

    /** Walk the active window tree into bounded JSON. */
    fun dumpTree(maxDepth: Int = 14, maxNodes: Int = 900): JSONObject {
        val out = JSONObject()
        val root = rootInActiveWindow
        if (root == null) {
            out.put("error", "no active window")
            return out
        }
        var count = 0
        fun node(node: AccessibilityNodeInfo, depth: Int): JSONObject {
            count += 1
            val o = JSONObject()
            o.put("cls", node.className?.toString() ?: "")
            o.put("pkg", node.packageName?.toString() ?: "")
            o.put("text", node.text?.toString()?.take(120) ?: "")
            o.put("desc", node.contentDescription?.toString()?.take(120) ?: "")
            o.put("id", node.viewIdResourceName ?: "")
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            o.put("bounds", JSONObject().put("x", r.left).put("y", r.top).put("w", r.width()).put("h", r.height()))
            o.put("clickable", node.isClickable)
            o.put("editable", node.isEditable)
            o.put("scrollable", node.isScrollable)
            if (depth < maxDepth && count < maxNodes) {
                val kids = JSONArray()
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    kids.put(node(c, depth + 1))
                }
                o.put("kids", kids)
            }
            return o
        }
        out.put("pkg", root.packageName?.toString() ?: "")
        out.put("nodes", count)
        out.put("tree", node(root, 0))
        return out
    }

    /** Full-screen capture (Android 11+). Returns PNG bytes or null. */
    fun screenshot(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        var png: ByteArray? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                runCatching {
                    val hw = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    if (bmp != null) {
                        // hardware → software via copy(): drawing a HARDWARE bitmap
                        // onto a software canvas throws (IllegalArgumentException,
                        // device-proven); copy() is the sanctioned conversion.
                        val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                        val bos = java.io.ByteArrayOutputStream()
                        soft.compress(Bitmap.CompressFormat.PNG, 70, bos)
                        png = bos.toByteArray()
                        soft.recycle()
                    } else android.util.Log.e("dsh-native", "wrapHardwareBuffer returned null")
                    hw.close()
                }.onFailure {
                    android.util.Log.e("dsh-native", "screenshot onSuccess crashed: ${it.javaClass.simpleName}: ${it.message}")
                }
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) {
                android.util.Log.e("dsh-native", "screenshot fail code=$errorCode")
                latch.countDown()
            }
        }
        // display 0 = DEFAULT_DISPLAY; the int overload predates the API-34 request object (this SDK's platform jar lacks that class entirely)
        var dispatched = false
        // API 34+: the request-object form is the functional path on modern
        // Android (the int overload silently never calls back on Android 16).
        // The class exists at RUNTIME even though our compile jar lacks it —
        // construct via reflection, fall back to the int overload.
        runCatching {
            val reqClass = Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotRequest")
            val req = reqClass.getConstructor(android.content.Context::class.java).newInstance(this)
            val m = AccessibilityService::class.java.getMethod(
                "takeScreenshot", reqClass,
                java.util.concurrent.Executor::class.java,
                AccessibilityService.TakeScreenshotCallback::class.java,
            )
            m.invoke(this, req, executor, callback)
            dispatched = true
        }.onFailure {
            android.util.Log.i("dsh-native", "reflection path failed (${it.javaClass.simpleName}), falling back to int overload")
        }
        if (!dispatched) {
            dispatched = try {
                takeScreenshot(0, executor, callback)
                true
            } catch (t: Throwable) {
                android.util.Log.e("dsh-native", "takeScreenshot threw: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
        android.util.Log.i("dsh-native", "takeScreenshot dispatched=$dispatched (sdk=${Build.VERSION.SDK_INT})")
        if (!dispatched) latch.countDown()
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        if (png == null) android.util.Log.e("dsh-native", "screenshot null after await")
        return png
    }

    private fun gesture(builder: GestureDescription.Builder, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        return dispatchGesture(builder.addStroke(strokes.first()).build(), null, null)
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return gesture(GestureDescription.Builder(), listOf(stroke))
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 300): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        return gesture(GestureDescription.Builder(), listOf(stroke))
    }

    fun longPress(x: Int, y: Int, ms: Long = 600): Boolean {
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        return gesture(GestureDescription.Builder(), listOf(stroke))
    }

    fun key(action: String): Boolean {
        val a = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(a)
    }

    /** Type into the FOCUSED editable node via accessibility set-text. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findEditable(root) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        if (depth > 12) return null
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findEditable(c, depth + 1)
            if (found != null) return found
        }
        return null
    }
}

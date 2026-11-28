package com.dsh.android

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.json.JSONObject

/** Loopback-only HTTP bridge exposing native capabilities to the DSH engine.
 *  Binds 127.0.0.1:3081 — never reachable from other devices; token-gated so
 *  other local apps cannot drive the phone. The token is written into
 *  files/runtime/native-token.txt (engine-readable via the runtime dir). */
class NativeBridgeServer(private val filesDir: File) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(3)
    companion object { const val PORT = 3081 }
    val port = PORT

    fun start() {
        if (server != null) return
        Thread {
            runCatching {
                val s = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
                server = s
                Log.i("dsh-native", "bridge listening on 127.0.0.1:$port")
                while (!s.isClosed) {
                    val sock = s.accept()
                    pool.execute { handle(sock) }
                }
            }.onFailure { Log.e("dsh-native", "bridge failed: ${it.message}") }
        }.apply { name = "dsh-native-bridge"; isDaemon = true; start() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun token(): String {
        val f = File(filesDir, "runtime/native-token.txt")
        if (!f.isFile) {
            f.parentFile?.mkdirs()
            f.writeText(java.util.UUID.randomUUID().toString().replace("-", "").take(32))
            // mirror into the guest tree so the engine-side plugin can read it
            runCatching {
                val guest = File(filesDir, "runtime/rootfs/root/.dsh/native-token.txt")
                guest.parentFile?.mkdirs()
                guest.writeText(f.readText().trim())
            }
        }
        return f.readText().trim()
    }

    private fun handle(sock: java.net.Socket) {
        runCatching {
            sock.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            var contentLength = 0
            var headerToken = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                if (line.startsWith("X-Token:", true)) headerToken = line.substringAfter(':').trim()
                if (line.isEmpty()) break
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""
            Log.i("dsh-native", "req $method $path")
            val auth = headerToken
            if (auth != token()) {
                respond(sock, 403, """{"error":"bad token"}""")
                return
            }
            val svc = DshAccessibilityService.instance
            when {
                method == "GET" && path == "/health" -> respond(sock, 200, JSONObject()
                    .put("ok", true)
                    .put("a11y", svc != null)
                    .put("screenApi", Build.VERSION.SDK_INT >= 30).toString())

                svc == null -> respond(sock, 503, """{"error":"accessibility service not enabled"}""")

                method == "GET" && path == "/tree" -> respond(sock, 200, svc.dumpTree().toString())

                method == "GET" && path == "/screenshot" -> {
                    val png = svc.screenshot()
                    if (png == null) respond(sock, 501, """{"error":"screenshot unsupported on this API level"}""")
                    else {
                        sock.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(png)
                            flush()
                        }
                        sock.close()
                    }
                }

                method == "POST" && path == "/tap" -> respond(sock, 200, act(svc) { tap(j(body, "x"), j(body, "y")) })
                method == "POST" && path == "/longpress" -> respond(sock, 200, act(svc) { longPress(j(body, "x"), j(body, "y")) })
                method == "POST" && path == "/swipe" -> respond(sock, 200, act(svc) {
                    swipe(j(body, "x1"), j(body, "y1"), j(body, "x2"), j(body, "y2"), (JSONObject(body).optLong("ms", 300)))
                })
                method == "POST" && path == "/text" -> respond(sock, 200, act(svc) {
                    val t = runCatching { JSONObject(body).getString("text") }.getOrDefault("")
                    typeText(t)
                })
                method == "POST" && path == "/key" -> respond(sock, 200, act(svc) {
                    val k = runCatching { JSONObject(body).getString("key") }.getOrDefault("")
                    key(k)
                })

                else -> respond(sock, 404, """{"error":"unknown"}""")
            }
        }.onFailure { Log.e("dsh-native", "handle err: ${it.message}"); runCatching { sock.close() } }
    }

    private inline fun act(svc: DshAccessibilityService, block: DshAccessibilityService.() -> Boolean): String =
        JSONObject().put("ok", svc.block()).toString()

    private fun j(body: String, key: String): Int = runCatching { JSONObject(body).getInt(key) }.getOrDefault(0)

    private fun respond(sock: java.net.Socket, code: Int, json: String) {
        runCatching {
            sock.getOutputStream().apply {
                write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${json.toByteArray().size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(json.toByteArray())
                flush()
            }
        }
        runCatching { sock.close() }
    }
}

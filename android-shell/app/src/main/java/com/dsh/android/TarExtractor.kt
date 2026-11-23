package com.dsh.android

import java.io.File
import java.io.InputStream

/**
 * Minimal POSIX/GNU tar extractor for the appliance rootfs (pure JVM, no deps).
 *
 * Handles what `docker buildx --output type=tar` / ubuntu rootfs tarballs emit:
 *  - regular files ('0', '\0', '7'), directories ('5'), symlinks ('2'), hardlinks ('1')
 *  - GNU long names ('L'), pax extended headers ('x', path= override)
 *  - base-256 sizes, ustar prefix field
 *  - device nodes / FIFOs / whiteouts are SKIPPED (unprivileged Android cannot create them;
 *    proot guests do not need them)
 *
 * Safety: entries escaping the destination (`..`, absolute paths, symlink traversal on
 * write) are rejected with [StageException]. Executable bits are preserved from the mode.
 */
object TarExtractor {

    private const val BLOCK = 512

    fun extract(input: InputStream, destDir: File, onEntry: ((Int) -> Unit)? = null): Int {
        android.util.Log.i("dsh-tar", "rev=zc16k-relative") // deploy marker
        android.util.Log.i("dsh-tar", "rev=zc16k-relative2") // deploy marker
        // Give the stream up to 3s to report first bytes (never block past that:
        // a legitimately finished stream would otherwise hang this loop forever).
        val probeUntil = System.currentTimeMillis() + 3000
        while (input.available() == 0 && System.currentTimeMillis() < probeUntil) {
            try { Thread.sleep(25) } catch (e: InterruptedException) { break }
        }
        val dest = destDir.canonicalFile
        val buf = ByteArray(BLOCK)
        var count = 0
        var pendingLongName: String? = null
        var pendingPaxPath: String? = null

        // Art/libcore gzip tail quirk (device-verified livelock): at stream end
        // read(buf,..) returns 0 AND read() returns 0 — the previous zero-singles
        // counting could be reset by interleaved real bytes and never terminate,
        // AND it advanced the block cursor, fabricating zero-filled phantom
        // headers. Correct semantics: ANY single-byte read()==0 (which violates
        // the contract and can only come from the runtime quirk) means END OF
        // STREAM — stop immediately. Never fabricate bytes into buf.
        fun zeroMeanEof(): Boolean = input.read() < 0
        fun readBlock(): Boolean {
            var off = 0
            while (off < BLOCK) {
                val n = input.read(buf, off, BLOCK - off)
                // CONTRACT: false ⇒ no complete block (EOF or truncated tail).
                // The historic `return off == 0` was INVERTED — true at clean EOF
                // made every stream spin at end-of-input (device-verified tail
                // livelock on AVD AND tablet). EOF is false, always.
                if (n < 0) return false
                if (n == 0) { // contract-violating zero ⇒ runtime quirk ⇒ EOF
                    if (zeroMeanEof()) return false
                    val b = input.read()
                    if (b <= 0) return false
                    buf[off] = b.toByte(); off++
                } else off += n
            }
            return true
        }
        fun skipBytes(n: Long) {
            var left = (n + BLOCK - 1) / BLOCK * BLOCK
            val sink = ByteArray((minOf(left, 65536L)).toInt())
            while (left > 0) {
                val chunk = minOf(left, sink.size.toLong()).toInt()
                val r = input.read(sink, 0, chunk)
                if (r < 0) break
                if (r == 0) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == 0) break
                    left -= 1
                } else left -= r
            }
        }
        // Skip a byte count EXACTLY (no block round-up). The block-round-up
        // skipBytes is only correct for whole un-started payloads; after a
        // branch has already consumed `size` bytes the remainder is the pad
        // (BLOCK - size%BLOCK) — rounding THAT up reads past the next header
        // and desynchronizes the entire stream (device-verified failure:
        // first pax header size=60 ⇒ skip 512 instead of 452).
        fun skipExact(n: Long) {
            var left = n
            val sink = ByteArray(minOf(left, 65536L).toInt())
            while (left > 0) {
                val r = input.read(sink, 0, minOf(left, sink.size.toLong()).toInt())
                if (r < 0) break
                if (r == 0) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == 0) break
                    left -= 1
                } else left -= r
            }
        }
        fun padAfter(size: Long) {
            if (size % BLOCK != 0L) skipExact(BLOCK - (size % BLOCK))
        }
        fun str(off: Int, len: Int): String {
            var end = off
            while (end < off + len && buf[end].toInt() != 0) end++
            return String(buf, off, end - off, Charsets.UTF_8)
        }
        fun num(off: Int, len: Int): Long {
            if (buf[off].toInt() == 0x80.toInt()) { // GNU base-256
                var v = 0L
                for (i in 1 until len) v = (v shl 8) or (buf[off + i].toLong() and 0xff)
                return v
            }
            val s = str(off, len).trim(' ', '\u0000')
            return s.toLongOrNull(radix = 8) ?: 0L
        }

        while (true) {
            if (!readBlock()) break
            // POSIX archive concatenation: two zero blocks end an archive, but a
            // following non-zero header starts another (GNU tar / bsdtar continue
            // reading). Only a real EOF (readBlock false) terminates.
            if (buf.all { it == 0.toByte() }) {
                // Trailer/padding between concatenated archives: skip zero blocks
                // until the next header or true EOF (matches the verified Python/JS
                // simulation over the same artifact — see tar-gnu-branches-test).
                continue
            }
            var name = pendingLongName ?: str(0, 100)
            pendingLongName = null
            val paxOverride = pendingPaxPath
            pendingPaxPath = null
            if (paxOverride != null) name = paxOverride
            val prefix = str(345, 155) // ustar prefix
            if (prefix.isNotEmpty() && !name.startsWith("/")) name = "$prefix/$name"
            val mode = num(100, 8).toInt()
            val size = num(124, 12)
            val type = buf[156].toInt().toChar()
            val linkName = str(157, 100)

            fun payloadFile(): File {
                val f = File(dest, name).canonicalFile
                // Strict containment, no dest-self exception: canonicalFile resolves
                // REAL symlinks, so "f == dest" means a member walks through an
                // earlier symlink to the archive root itself — writing there is the
                // EISDIR crash at best and symlink traversal at worst. The benign
                // case ('./' directory members) is filtered by the caller as type '5'.
                if (!f.path.startsWith(dest.path + File.separator)) {
                    skipBytes(size)
                    throw AssetInstaller.StageException("拒绝越界/根写入成员：$name")
                }
                return f
            }

            if (type != 'L' && type != 'x' && type != 'g' && type != 'X') {
                // './'-style dir member resolving exactly to dest: harmless no-op.
                val canon = File(dest, name).canonicalFile
                if (canon == dest) {
                    if (type == '5') { skipBytes(size); count++; continue }
                    val rawName = str(0, 100)
                    val rawType = buf[156].toInt()
                    skipBytes(size)
                    throw AssetInstaller.StageException(
                        "成员试图写向归档根：name=" + name.toByteArray().joinToString("") { "%02x".format(it) } +
                        " type=0x" + "%02x".format(rawType) + " size=" + size + " after-members=" + count
                    )
                }
            }

            when (type) {
                'L' -> { // GNU long name follows in next entry's data
                    val sb = ByteArray(size.toInt())
                    var off = 0
                    while (off < size.toInt()) {
                        val r = input.read(sb, off, (size - off).toInt())
                        if (r < 0) break
                        if (r == 0) {
                            val b = input.read()
                            if (b < 0) break
                            if (b == 0) break
                            sb[off] = b.toByte(); off++
                        } else off += r
                    }
                    pendingLongName = String(sb, 0, off, Charsets.UTF_8).trim('\u0000', '\n', ' ')
                    padAfter(size)
                    continue
                }
                'x' -> { // pax header: parse path=, swallow rest
                    val data = ByteArray(size.toInt())
                    var off = 0
                    while (off < size.toInt()) {
                        val r = input.read(data, off, (size - off).toInt())
                        if (r < 0) break
                        if (r == 0) {
                            val b = input.read()
                            if (b < 0) break
                            if (b == 0) break
                            data[off] = b.toByte(); off++
                        } else off += r
                    }
                    Regex("""\d+ path=(.*)""").find(String(data, 0, off, Charsets.UTF_8))
                        ?.let { pendingPaxPath = it.groupValues[1].trim() }
                    padAfter(size)
                    continue
                }
                'g', 'X' -> { skipBytes(size); continue } // global pax / GNU sparse — unused
                '5' -> {
                    payloadFile().mkdirs(); skipBytes(size); count++
                }
                '0', '7', '\u0000' -> {
                    val f = payloadFile()
                    f.parentFile?.mkdirs()
                    f.outputStream().use { out ->
                        var left = size
                        val chunk = ByteArray(65536)
                        while (left > 0) {
                            val r = input.read(chunk, 0, minOf(left, chunk.size.toLong()).toInt())
                            if (r < 0) break
                            if (r == 0) { // Art zero-read quirk (see readBlock)
                                val b = input.read()
                                if (b < 0) break
                                if (b == 0) break
                                out.write(b)
                                left -= 1
                            } else {
                                                                out.write(chunk, 0, r); left -= r
                            }
                            // Progress: per-MB callbacks turned into a notification
                            // storm (thousands of binder posts → the extract thread
                            // starves; device-verified tail stall on 定制系统). Report by
                            // ENTRY COUNT, throttled to every 256 entries.
                            if (count and 255 == 0) {
                onEntry?.invoke(count)
                if (count and 8191 == 0) android.util.Log.i("dsh-tar", "m=$count")
            }
                        }
                    }
                    if (mode and 0x40 != 0) f.setExecutable(true, true) // owner exec
                    if (mode and 0x100 != 0) f.setReadable(true, true)
                    padAfter(size)
                    count++
                }
                '2' -> { // symlink kept as java File symlink best-effort; else placeholder skipped
                    val f = payloadFile()
                    f.parentFile?.mkdirs()
                    // Store the tar's link target VERBATIM (relative stays relative).
                    // Resolving to an absolute path here would bake rootfs.tmp into
                    // every link — breaking the whole tree on the tmp→rootfs rename
                    // (observed: corepack/npm absolute symlinks into rootfs.tmp).
                    runCatching { java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(linkName)) }
                        .onFailure { e -> if (name == "bin" || name == "sbin" || name == "lib") android.util.Log.w("dsh-tar", "symlink $name -> $linkName FAILED: $e") }
                        .onSuccess { if (name == "bin") android.util.Log.i("dsh-tar", "symlink bin OK") }
                    skipBytes(size); count++
                }
                '1' -> { // hardlink → copy of the earlier member addressed by linkName (self-contained rootfs)
                    val f = payloadFile()
                    val target = File(dest, linkName).canonicalFile
                    // linkName is attacker-shaped data: the same strict containment
                    // applies (root or escaping target ⇒ no copy, mirrors replica).
                    val src = if (target.path.startsWith(dest.path + File.separator)) target else null
                    f.parentFile?.mkdirs()
                    if (src != null && src.isFile) src.copyTo(f, overwrite = true)
                    if (mode and 0x40 != 0) f.setExecutable(true, true)
                    skipBytes(size); count++
                }
                else -> skipBytes(size) // '3','4','6','F','D' — devices/FIFOs/whiteouts: skip
            }
        }
        input.close()
        return count
    }

    private fun File.outputStream() = java.io.FileOutputStream(this)
}

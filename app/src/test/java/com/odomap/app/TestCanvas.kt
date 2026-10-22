package com.odomap.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Tiny RGB raster + PNG writer for JVM tests (android.jar has no java.awt). Thick lines are coverage-blended capsules. */
class TestCanvas(val w: Int, val h: Int, background: Int) {
    private val px = IntArray(w * h) { background }

    fun line(x0: Double, y0: Double, x1: Double, y1: Double, radius: Double, rgb: Int, alpha: Double) {
        val minX = max(0, floor(min(x0, x1) - radius - 1).toInt())
        val maxX = min(w - 1, ceil(max(x0, x1) + radius + 1).toInt())
        val minY = max(0, floor(min(y0, y1) - radius - 1).toInt())
        val maxY = min(h - 1, ceil(max(y0, y1) + radius + 1).toInt())
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        for (y in minY..maxY) for (x in minX..maxX) {
            val px0 = x + 0.5 - x0
            val py0 = y + 0.5 - y0
            val t = if (len2 == 0.0) 0.0 else ((px0 * dx + py0 * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(px0 - t * dx, py0 - t * dy)
            val cover = (radius + 0.5 - d).coerceIn(0.0, 1.0) * alpha
            if (cover <= 0) continue
            val i = y * w + x
            px[i] = blend(px[i], rgb, cover)
        }
    }

    private fun blend(a: Int, b: Int, f: Double): Int {
        fun ch(shift: Int) = ((a shr shift and 255) * (1 - f) + (b shr shift and 255) * f).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun writePng(file: File) {
        file.parentFile?.mkdirs()
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            for (y in 0 until h) {
                z.write(0)
                for (x in 0 until w) {
                    val c = px[y * w + x]
                    z.write(c shr 16 and 255)
                    z.write(c shr 8 and 255)
                    z.write(c and 255)
                }
            }
        }
        DataOutputStream(file.outputStream()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                out.writeInt(data.size)
                val t = type.toByteArray(Charsets.US_ASCII)
                out.write(t)
                out.write(data)
                val crc = CRC32()
                crc.update(t)
                crc.update(data)
                out.writeInt(crc.value.toInt())
            }
            val ihdr = ByteArrayOutputStream()
            DataOutputStream(ihdr).apply {
                writeInt(w)
                writeInt(h)
                write(byteArrayOf(8, 2, 0, 0, 0))
            }
            chunk("IHDR", ihdr.toByteArray())
            chunk("IDAT", raw.toByteArray())
            chunk("IEND", ByteArray(0))
        }
    }
}

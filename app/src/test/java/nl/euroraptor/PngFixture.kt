package nl.euroraptor

import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/**
 * Small JVM-test PNG reader. Uses java.util.zip instead of javax.imageio
 * (java.desktop is not available on the Android unit-test compile classpath).
 * Supports non-interlaced, 8-bit grayscale, RGB, indexed and RGBA PNGs.
 */
internal class PngFixture private constructor(
    val width: Int, val height: Int, private val pixels: IntArray
) {
    fun getRGB(x: Int, y: Int): Int = pixels[y * width + x]

    companion object {
        fun read(file: File): PngFixture {
            val bytes = file.readBytes()
            require(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(137.toByte(),80,78,71,13,10,26,10))) { "Not PNG: ${file.name}" }
            fun intAt(i: Int): Int =
                ((bytes[i].toInt() and 255) shl 24) or
                ((bytes[i+1].toInt() and 255) shl 16) or
                ((bytes[i+2].toInt() and 255) shl 8) or (bytes[i+3].toInt() and 255)
            var w = 0; var h = 0; var type = -1; var depth = -1
            var interlace = -1
            var palette = byteArrayOf()
            val compressed = ByteArrayOutputStream()
            var pos = 8
            while (pos + 12 <= bytes.size) {
                val length = intAt(pos)
                require(length >= 0 && length <= bytes.size - pos - 12) { "Invalid PNG chunk" }
                val name = String(bytes, pos + 4, 4, Charsets.US_ASCII)
                val start = pos + 8
                when (name) {
                    "IHDR" -> {
                        w = intAt(start); h = intAt(start + 4)
                        depth = bytes[start + 8].toInt() and 255
                        type = bytes[start + 9].toInt() and 255
                        interlace = bytes[start + 12].toInt() and 255
                    }
                    "PLTE" -> palette = bytes.copyOfRange(start, start + length)
                    "IDAT" -> compressed.write(bytes, start, length)
                    "IEND" -> break
                }
                pos += 12 + length
            }
            require(w in 1..10000 && h in 1..10000 && w.toLong()*h <= 40_000_000L)
            require(depth == 8 && interlace == 0 && type in setOf(0,2,3,4,6)) {
                "Unsupported PNG format: depth=$depth type=$type interlace=$interlace"
            }
            val channels = when(type) { 0,3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }
            val stride = w * channels
            val pixels = IntArray(w*h)
            InflaterInputStream(compressed.toByteArray().inputStream()).use { stream ->
                var previous = ByteArray(stride)
                for (y in 0 until h) {
                    val filter = stream.read()
                    require(filter in 0..4) { "Invalid PNG scanline filter" }
                    val row = ByteArray(stride)
                    var n = 0
                    while (n < stride) {
                        val got = stream.read(row, n, stride - n)
                        require(got > 0) { "Truncated PNG scanline" }
                        n += got
                    }
                    for (i in row.indices) {
                        val a = if (i >= channels) row[i-channels].toInt() and 255 else 0
                        val b = previous[i].toInt() and 255
                        val c = if (i >= channels) previous[i-channels].toInt() and 255 else 0
                        val predict = when(filter) {
                            0 -> 0; 1 -> a; 2 -> b; 3 -> (a+b)/2
                            else -> {
                                val p = a+b-c
                                val da = kotlin.math.abs(p-a); val db = kotlin.math.abs(p-b)
                                val dc = kotlin.math.abs(p-c)
                                if (da<=db && da<=dc) a else if(db<=dc) b else c
                            }
                        }
                        row[i] = ((row[i].toInt() and 255) + predict).toByte()
                    }
                    for (x in 0 until w) {
                        val i = x*channels
                        fun u(k: Int) = row[i+k].toInt() and 255
                        val rgb = when(type) {
                            0,4 -> { val g=u(0); (g shl 16) or (g shl 8) or g }
                            2,6 -> (u(0) shl 16) or (u(1) shl 8) or u(2)
                            else -> {
                                val index=u(0)*3
                                require(index+2 < palette.size) { "Invalid PNG palette index" }
                                ((palette[index].toInt() and 255) shl 16) or
                                ((palette[index+1].toInt() and 255) shl 8) or
                                (palette[index+2].toInt() and 255)
                            }
                        }
                        pixels[y*w+x] = -0x1000000 or rgb
                    }
                    previous = row
                }
            }
            return PngFixture(w,h,pixels)
        }
    }
}

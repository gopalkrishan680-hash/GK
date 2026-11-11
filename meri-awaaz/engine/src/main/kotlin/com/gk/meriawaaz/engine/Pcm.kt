package com.gk.meriawaaz.engine

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sample rate every model in the app works at. */
const val MODEL_RATE = 22050

/** Random-access mono audio, samples in -1..1 at [MODEL_RATE]. */
interface PcmSource {
    val length: Long
    fun read(pos: Long, dst: FloatArray, off: Int, n: Int)
}

class ArrayPcm(private val data: FloatArray) : PcmSource {
    override val length: Long get() = data.size.toLong()
    override fun read(pos: Long, dst: FloatArray, off: Int, n: Int) {
        System.arraycopy(data, pos.toInt(), dst, off, n)
    }
}

private fun toPcm16(v: Float): Int {
    val s = Math.round(v * 32767f)
    return if (s > 32767) 32767 else if (s < -32768) -32768 else s
}

/** Headerless 16-bit little-endian mono file, used as an on-disk buffer for long audio. */
class RawPcmFile(file: File) : PcmSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length() / 2
    private var bytes = ByteArray(0)

    override fun read(pos: Long, dst: FloatArray, off: Int, n: Int) {
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        raf.seek(pos * 2)
        raf.readFully(bytes, 0, n * 2)
        val sb = ByteBuffer.wrap(bytes, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until n) dst[off + i] = sb.get(i) / 32768f
    }

    override fun close() = raf.close()
}

/** Writes 16-bit mono PCM, either headerless ([wavRate] = 0) or as a WAV file. */
class PcmWriter(private val file: File, private val wavRate: Int = 0) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var bytes = ByteArray(0)
    var samples = 0L
        private set

    init {
        if (wavRate > 0) out.write(ByteArray(44))
    }

    fun write(buf: FloatArray, off: Int, n: Int) {
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = toPcm16(buf[off + i])
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        out.write(bytes, 0, n * 2)
        samples += n
    }

    override fun close() {
        out.close()
        if (wavRate <= 0) return
        val data = samples * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(wavRate).putInt(wavRate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.toInt())
        RandomAccessFile(file, "rw").use { it.write(h.array()) }
    }
}

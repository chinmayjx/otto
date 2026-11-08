package io.github.otto.device

import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The wire format of the device link (otto/box/relay/src/device.ts), both ways: u32 big-endian length, that many
 * bytes of a JSON header, then header.size raw bytes if the header has "size". Requests carry {id, cmd, args};
 * replies {id, ok, result} or {id, ok:false, error}.
 */
class Frames(input: InputStream, private val out: OutputStream) {
    class Frame(val head: JSONObject, val body: ByteArray?)

    private val input = DataInputStream(input)

    /** The next frame, or null at the end of the stream. */
    fun read(): Frame? {
        val n = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (n < 0 || n > MAX_HEADER) throw IOException("bad frame length $n")
        val h = ByteArray(n)
        input.readFully(h)
        val head = try {
            JSONObject(String(h, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IOException(e)
        }
        val size = head.optLong("size", 0)
        if (size < 0 || size > Int.MAX_VALUE - 8) throw IOException("bad body size $size")
        val body = if (size > 0) ByteArray(size.toInt()).also { input.readFully(it) } else null
        return Frame(head, body)
    }

    @Synchronized
    fun write(head: JSONObject, body: ByteArray? = null) {
        if (body != null) head.put("size", body.size) else head.remove("size")
        val h = head.toString().toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf((h.size ushr 24).toByte(), (h.size ushr 16).toByte(), (h.size ushr 8).toByte(), h.size.toByte()))
        out.write(h)
        if (body != null) out.write(body)
        out.flush()
    }

    companion object {
        const val MAX_HEADER = 1 shl 20
    }
}

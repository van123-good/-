package com.castlink.app

import java.io.DataInputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.nio.ByteBuffer

/**
 * 极简传输协议（TCP）。每个包： type(1) | pts(8, 微秒) | length(4) | payload
 *  HELLO : payload = magic(4) width(4) height(4)
 *  CONFIG: payload = H.264 SPS/PPS (Annex-B)
 *  FRAME : payload = H.264 帧；KEY 为关键帧
 */
object Protocol {
    const val PORT = 53515
    const val SERVICE_TYPE = "_castlink._tcp."
    const val MAGIC = 0x434C4E4B
    const val T_HELLO = 0
    const val T_CONFIG = 1
    const val T_FRAME = 2
    const val T_KEY = 3
    const val MAX_PAYLOAD = 16 * 1024 * 1024

    class Packet(val type: Int, val pts: Long, val data: ByteArray)

    fun encode(type: Int, pts: Long, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(13 + data.size)
        b.put(type.toByte()).putLong(pts).putInt(data.size).put(data)
        return b.array()
    }

    fun hello(width: Int, height: Int): ByteArray {
        val b = ByteBuffer.allocate(12)
        b.putInt(MAGIC).putInt(width).putInt(height)
        return encode(T_HELLO, 0, b.array())
    }

    fun read(input: DataInputStream): Packet {
        val type = input.readUnsignedByte()
        val pts = input.readLong()
        val len = input.readInt()
        if (len < 0 || len > MAX_PAYLOAD) throw java.io.IOException("bad length $len")
        val data = ByteArray(len)
        input.readFully(data)
        return Packet(type, pts, data)
    }

    fun write(out: OutputStream, bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }

    fun localAddresses(): List<String> {
        val result = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    val h = addr.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && !h.contains(':')) result.add(h)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}

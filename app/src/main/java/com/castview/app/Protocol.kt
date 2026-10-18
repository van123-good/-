package com.castview.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.NetworkInterface

/**
 * Wire format (video only, H.264 over TCP):
 *   header : int MAGIC, int width, int height
 *   frame  : int length, byte flags, long ptsUs, byte[length]
 */
object Protocol {
    const val PORT = 53317
    const val MAGIC = 0x43564931
    const val FLAG_CONFIG = 1
    const val FLAG_KEY = 2
    const val SERVICE_TYPE = "_castview._tcp."
    const val MAX_FRAME = 8 * 1024 * 1024

    fun writeHeader(o: DataOutputStream, w: Int, h: Int) {
        o.writeInt(MAGIC); o.writeInt(w); o.writeInt(h); o.flush()
    }

    /** Returns (width, height). */
    fun readHeader(i: DataInputStream): Pair<Int, Int> {
        require(i.readInt() == MAGIC) { "bad magic" }
        return Pair(i.readInt(), i.readInt())
    }

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }?.hostAddress
    } catch (e: Exception) { null }
}

package info.skyblond.nojre

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.DatagramPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

class NojrePeer(
    nickname: String = "",
    muted: Boolean = false,
    lastSeen: Long = 0L
) {
    var nickname by mutableStateOf(nickname)
    var muted by mutableStateOf(muted)
    var lastSeen by mutableStateOf(lastSeen)
        private set

    val queue = ConcurrentLinkedQueue<Short>()

//    fun onPacket(packet: DatagramPacket) {
//        lastSeen = System.currentTimeMillis()
//        val buffer = ByteBuffer.wrap(packet.data, packet.offset, packet.length).order(ByteOrder.LITTLE_ENDIAN)
//        if (buffer.getShort(0))
//    }
}
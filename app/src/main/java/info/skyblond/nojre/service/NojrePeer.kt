package info.skyblond.nojre.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

class NojrePeer(
    nickname: String = "",
    lastSeen: Long = 0L
) {
    var nickname by mutableStateOf(nickname)
    var volume by mutableStateOf(1.0)
    var lastSeen by mutableStateOf(lastSeen)
        private set

    val queue = ConcurrentLinkedQueue<Short>()

    private fun handleAdvertisePacket(packet: ByteArray) {
        val nicknameBytes = packet.copyOfRange(1, packet.size)
        kotlin.runCatching { nickname = nicknameBytes.decodeToString() }
    }

    private fun handleAudioPacket(packet: ByteArray) {
        // if there are more than 500ms of data waiting for processing, we drop them
        if (queue.size > NojreForegroundService.SAMPLE_RATE / 2) queue.clear()
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until packet.size / Short.SIZE_BYTES) {
            queue.offer(buffer.getShort(1 + 2 * i))
        }
    }

    fun handlePacket(packet: ByteArray) {
        lastSeen = System.currentTimeMillis()
        when (packet[0].toInt()) {
            0x01 -> handleAdvertisePacket(packet)
            0x02 -> handleAudioPacket(packet)
        }
    }
}
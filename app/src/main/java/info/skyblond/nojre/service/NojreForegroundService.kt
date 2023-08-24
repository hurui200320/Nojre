package info.skyblond.nojre.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import info.skyblond.nojre.R
import info.skyblond.nojre.decryptMessage
import info.skyblond.nojre.encryptMessage
import info.skyblond.nojre.sha256ToKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import kotlin.concurrent.thread
import kotlin.math.absoluteValue

class NojreForegroundService : Service() {
    companion object {
        private val TAG = NojreForegroundService::class.simpleName!!

        /**
         * Sample rate is fixed at 16KHz, this should be good enough
         * until we humans developed new organs to make sounds.
         * */
        const val SAMPLE_RATE = 16000

        /**
         * Replay rate is slightly faster to catch up any delay.
         * */
        private const val REPLAY_RATE = 16016

        /**
         * Delete a peer if not active for 10 second
         * */
        private const val PEER_TIMEOUT = 10 * 1000L
    }

    internal val serviceRunning = AtomicBoolean(false)

    @Volatile
    internal var nickname = ""
        private set

    @Volatile
    internal var password = ""
        private set

    @Volatile
    internal var groupAddress = ""
        private set

    @Volatile
    internal var groupPort = 0
        private set

    @Volatile
    internal var useVoiceCall = false
        private set

    private lateinit var key: SecretKey

    private lateinit var wifiManager: WifiManager
    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private var minBufferSize: Int = 0

    private lateinit var multicastGroup: InetAddress
    private lateinit var socket: MulticastSocket

    internal var ourVolume by mutableStateOf(1.0)

    internal val peerMap = mutableStateMapOf<String, NojrePeer>()

    private fun createForegroundNotificationChannel() {
        val id = applicationContext.getString(R.string.foreground_notification_channel_id)
        val name = applicationContext.getString(R.string.foreground_notification_channel_name)
        val descriptionText =
            applicationContext.getString(R.string.foreground_notification_channel_description)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(id, name, importance)
        channel.description = descriptionText
        // Register the channel with the system. You can't change the importance
        // or other notification behaviors after this.
        val notificationManager =
            applicationContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createForegroundNotification(): Notification {
        val id = applicationContext.getString(R.string.foreground_notification_channel_id)
        val title = applicationContext.getString(R.string.foreground_notification_channel_title)

        // Create a Notification
        createForegroundNotificationChannel()
        return NotificationCompat.Builder(applicationContext, id)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText("Nojre is running...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        val notification = createForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            startForeground(
                UUID.randomUUID().hashCode(), notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        else startForeground(UUID.randomUUID().hashCode(), notification)

        // get a wifi manager for multicast lock
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("nojre")
        // calculate the minBufferSize
        minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) * 2
        // UDP MAX is 65535, we left 5KB for protocol overhead
        check(minBufferSize < 60000) { "Minimal buffer size is bigger than max UDP packet size" }
    }

    inner class LocalBinder(val service: NojreForegroundService) : Binder()

    override fun onBind(intent: Intent?): IBinder = LocalBinder(this)

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // skip if we're already started
        if (serviceRunning.get()) return START_STICKY
        serviceRunning.set(true)

        nickname = intent.getStringExtra("nickname") ?: ""
        password = intent.getStringExtra("password") ?: ""
        groupAddress = intent.getStringExtra("group_address") ?: ""
        groupPort = intent.getIntExtra("group_port", -1)
        useVoiceCall = intent.getBooleanExtra("use_voice", false)
        key = sha256ToKey(password.encodeToByteArray())

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(REPLAY_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(
                        if (useVoiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .build()

        // use 239.255.0.0/16 for ipv4 local scope
        multicastLock.acquire()
        multicastGroup = InetAddress.getByName(groupAddress)
        socket = MulticastSocket(groupPort)
        socket.joinGroup(multicastGroup)
        audioRecord.startRecording()
        audioTrack.play()

        val txThread = thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Thread.sleep(500)
            }
            var lastAdvertise = 0L
            val audioBuffer =
                ByteBuffer.allocateDirect(minBufferSize).order(ByteOrder.LITTLE_ENDIAN)
            while (serviceRunning.get()) {
                val readCount = audioRecord.read(audioBuffer, minBufferSize)
                if (System.currentTimeMillis() - lastAdvertise > PEER_TIMEOUT / 2) {
                    try {
                        socket.send(generateAdvertisePacket())
                    } catch (e: SocketException) {
                        break
                    }
                    lastAdvertise = System.currentTimeMillis()
                }
                if (readCount == 0) continue
                val audioPacket = generateAudioPacket(audioBuffer, readCount)
                try {
                    socket.send(audioPacket)
                } catch (e: SocketException) {
                    break
                }
            }
        }

        val rxThread = thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            val byteBuffer = ByteArray(65536)
            while (serviceRunning.get()) {
                val packet = DatagramPacket(byteBuffer, 0, byteBuffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketException) {
                    break
                }
                val sourceAddress = packet.socketAddress as InetSocketAddress
                // skip different port
                if (sourceAddress.port != groupPort) continue
                val ourIPs = NetworkInterface.getNetworkInterfaces().asSequence()
                    .flatMap { it.inetAddresses.asSequence() }
                // skip out own packet
                if (ourIPs.any { it.address.contentEquals(sourceAddress.address.address) }) continue

                val decrypted = decryptPacket(packet) ?: continue

                val key = sourceAddress.address.hostAddress!!
                val peer = peerMap.getOrPut(key) { NojrePeer() }
                peer.handlePacket(decrypted)
            }
        }

        val cleanUpThread = thread {
            runBlocking(Dispatchers.Default) {
                launch {
                    while (serviceRunning.get()) {
                        delay(PEER_TIMEOUT)
                        val now = System.currentTimeMillis()
                        peerMap.keys.forEach { k ->
                            peerMap.compute(k) { _, peer ->
                                val lastSeen = peer?.lastSeen ?: 0
                                if (now - lastSeen >= PEER_TIMEOUT) null
                                else peer
                            }
                        }
                    }
                }
            }
        }

        val mixerThread = thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (serviceRunning.get()) {
                val samples = DoubleArray(256) {
                    peerMap.mapNotNull { (_, peer) ->
                        val v = peer.queue.poll()?.let { it / 32767.0 } ?: 0.0
                        v * peer.volume
                    }.sum()
                }
                // make sure we don't amplify the sound
                val max = samples.maxOf { it.absoluteValue }.coerceAtLeast(1.0)
                for (i in samples.indices) {
                    samples[i] /= max
                }
                val buffer = ByteArray(samples.size * Short.SIZE_BYTES)
                val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                for (i in samples.indices) {
                    byteBuffer.putShort(
                        2 * i,
                        (samples[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                    )
                }
                audioTrack.write(buffer, 0, buffer.size)
            }
        }

        return START_STICKY
    }

    /**
     * Generate encrypted packet.
     *
     * [1B:version]: fixed, 0x01.
     * [12B:iv]: AES/GCM/NoPadding IV.
     * [??]: AES/GCM encrypted data.
     * */
    private fun generateEncryptedPacket(data: ByteArray): DatagramPacket {
        val (cipher, iv) = encryptMessage(key, data)
        val packetData = ByteArray(1 + 12 + cipher.size)
        packetData[0] = 0x01 // header: 0x01 -> AES GCM NoPadding, 12B iv
        System.arraycopy(iv, 0, packetData, 1, iv.size)
        System.arraycopy(cipher, 0, packetData, 1 + iv.size, cipher.size)
        val packet = DatagramPacket(packetData, 0, packetData.size)
        packet.address = multicastGroup
        packet.port = groupPort
        return packet
    }

    private fun decryptPacket(packet: DatagramPacket): ByteArray? {
        // unknown packet
        if (packet.data[packet.offset].toInt() != 0x01) return null
        val iv = packet.data.copyOfRange(packet.offset + 1, packet.offset + 1 + 12)
        val cipher = packet.data.copyOfRange(packet.offset + 1 + 12, packet.offset + packet.length)
        return try {
            decryptMessage(key, iv, cipher)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Generate advertise packet.
     *
     * [1B:header]: fixed, 0x01
     * [??]: UTF-8 encoded nickname
     * */
    private fun generateAdvertisePacket(): DatagramPacket {
        val nicknameUTF8 = nickname.encodeToByteArray()
        val byteArray = ByteArray(nicknameUTF8.size + 1)
        byteArray[0] = 0x01 // header -> Nickname advertise packet
        System.arraycopy(nicknameUTF8, 0, byteArray, 1, nicknameUTF8.size)
        return generateEncryptedPacket(byteArray)
    }

    /**
     * Generate audio packet.
     * MONO, 16000KHz, 16bit signed, little endian.
     *
     * [1B:header]: fixed, 0x02
     * [??]: PCM data
     * */
    private fun generateAudioPacket(audioBuffer: ByteBuffer, readCount: Int): DatagramPacket {
        val byteArray = ByteArray(readCount + 1)
        byteArray[0] = 0x02 // header -> MONO 16KHz signed 16bit little endian PCM
        val buffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until readCount / Short.SIZE_BYTES) {
            val s = audioBuffer.getShort(2 * i) / 32767.0
            // s in [-1, 1]
            val k = (s * ourVolume * 32767).toInt().coerceIn(-32768, 32767)
            buffer.putShort(1 + 2 * i, k.toShort())
        }
        return generateEncryptedPacket(byteArray)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceRunning.set(false)
        kotlin.runCatching { socket.leaveGroup(multicastGroup) }
        kotlin.runCatching { socket.close() }
        kotlin.runCatching { multicastLock.release() }
        kotlin.runCatching { audioTrack.stop() }
        kotlin.runCatching { audioTrack.release() }
        kotlin.runCatching { audioRecord.stop() }
        kotlin.runCatching { audioRecord.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
package info.skyblond.nojre

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

class NojreForegroundService : Service() {
    companion object {
        private val TAG = NojreForegroundService::class.simpleName!!
        private const val AUDIO_PACKET_SIZE = 768

        // 20B from ip and 8B from UDP, assuming MTU = 1500
        private const val UDP_PACKET_SIZE = 1472

        /**
         * Sample rate is fixed at 16KHz, this should be good enough
         * until we humans developed new organs to make sounds.
         * */
        private const val SAMPLE_RATE = 16000

        /**
         * Replay rate is slightly faster to catch up any delay.
         * */
        private const val REPLAY_RATE = 16016
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

    private lateinit var wifiManager: WifiManager
    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private var minBufferSize: Int = 0

    private lateinit var multicastGroup: InetAddress
    private lateinit var socket: MulticastSocket

    internal var ourVolume by mutableStateOf(1.0)

    // TODO enclose the queue into a peer info obj
    internal val peerMap = mutableStateMapOf<String, ConcurrentLinkedQueue<Short>>()

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
        ).let { ((it / AUDIO_PACKET_SIZE) + 1) * AUDIO_PACKET_SIZE }
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
            val audioBuffer =
                ByteBuffer.allocateDirect(minBufferSize).order(ByteOrder.LITTLE_ENDIAN)
            while (serviceRunning.get()) {
                val readCount = audioRecord.read(audioBuffer, minBufferSize)
                if (readCount == 0) continue
                for (offset in 0 until readCount step AUDIO_PACKET_SIZE) {
                    val len = min(AUDIO_PACKET_SIZE, readCount - offset)
                    val byteBuffer = ByteArray(len)
                    val buffer = ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until len / Short.SIZE_BYTES) {
                        val s = (audioBuffer.getShort(offset + 2 * i) + 32768) / 65535.0
                        // s in [0, 1]
                        val k = ((s * ourVolume).coerceIn(0.0, 1.0) * 65535).toInt() - 32768
                        buffer.putShort(2 * i, k.toShort())
                    }
                    // val key = sha256ToKey("1234".encodeToByteArray())
                    // val (cipher, iv) = encryptMessage(
                    //     key,
                    //     "something".encodeToByteArray()
                    // )
                    // val text = decryptMessage(key, iv, cipher)
                    // TODO protocol?
                    val packet = DatagramPacket(byteBuffer, 0, len)
                    packet.address = multicastGroup
                    packet.port = groupPort
                    try {
                        socket.send(packet)
                    } catch (e: SocketException) {
                        break
                    }
                }
            }
        }

        val rxThread = thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            val byteBuffer = ByteArray(1500) // MAX MTU
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

                val key = sourceAddress.address.hostAddress!!
                val queue = peerMap.getOrPut(key) { ConcurrentLinkedQueue<Short>() }
                // if there are more than 500ms of data waiting for processing, we drop them
                if (queue.size > SAMPLE_RATE / 2) queue.clear()
                // TODO: Protocol?
                val buffer = ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until packet.length / Short.SIZE_BYTES) {
                    queue.offer(buffer.getShort(2 * i))
                }
            }
        }

        val mixerThread = thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (serviceRunning.get()) {
                Thread.sleep(5)
                val samples = DoubleArray(512) {
                    peerMap.mapNotNull { (_, queue) ->
                        queue.poll()?.let { (it + 32768) / 65535.0 } ?: 0.0
                    }.sum()
                }
                val max = samples.max().coerceAtLeast(1.0)
                for (i in samples.indices) {
                    samples[i] /= max
                }
                val buffer = ByteArray(samples.size * Short.SIZE_BYTES)
                val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                for (i in samples.indices) {
                    byteBuffer.putShort(2 * i, ((samples[i] * 65535).toInt() - 32768).toShort())
                }
                audioTrack.write(buffer, 0, buffer.size)
            }
        }

        return START_STICKY
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
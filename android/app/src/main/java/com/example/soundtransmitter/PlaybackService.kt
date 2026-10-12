package com.example.soundtransmitter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var track: AudioTrack? = null
    private var decoder: MediaCodec? = null
    private var ptsUs = 0L

    // keeps the Wi-Fi radio awake while streaming with the screen off
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(mode, "sound_transmitter").apply { acquire() }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // reconnect state
    private val handler = Handler(Looper.getMainLooper())
    private var wantConnected = false
    private var host = ""
    private var port = ""
    private var attempt = 0

    // the activity sets this to receive status text; the service never touches the UI
    var onStatus: ((String) -> Unit)? = null

    // last status, so a screen that opens later can show the current state
    var lastStatus: String = "Not connected"
        private set

    // true from the user's Connect until their Disconnect, including retry waits
    val isConnected: Boolean
        get() = wantConnected

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            disconnect()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sound Transmitter")
            .setContentText("Streaming from PC")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun connect(host: String, port: String) {
        if (webSocket != null) return
        this.host = host
        this.port = port
        wantConnected = true
        attempt = 0
        acquireWifiLock()
        open()
    }

    private fun open() {
        if (webSocket != null) return
        report("Connecting ...")
        val request = Request.Builder().url("ws://$host:$port").build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    report("Connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val config = JSONObject(text)
                    val rate = config.getInt("rate")
                    val channels = config.getInt("channels")
                    val codec = config.optString("codec", "pcm")
                    report("Config: $rate Hz, $channels channels, $codec")
                    startTrack(rate, channels)
                    if (codec == "opus") startDecoder(rate, channels)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (decoder != null) {
                        decode(data)
                    } else {
                        track?.write(data, 0, data.size)
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    lost("Failed: ${t.message}")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // the server is closing: answer, so onClosed fires and we can retry
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    lost("Disconnected")
                }
            }
        )
    }

    fun disconnect() {
        wantConnected = false
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, null)
        finish("Disconnected")
    }

    private fun startTrack(rate: Int, channels: Int) {
        val mask = if (channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(mask)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            rate, mask, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    private fun startDecoder(rate: Int, channels: Int) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS, rate, channels
        )
        // csd-0: the standard "OpusHead" identification header (19 bytes)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)                        // version
            put(channels.toByte())
            putShort(0)                   // pre-skip
            putInt(rate)                  // original input sample rate
            putShort(0)                   // output gain
            put(0)                        // channel mapping family
            flip()
        }
        // csd-1: pre-skip in nanoseconds, csd-2: seek pre-roll in nanoseconds
        val preSkip = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .apply { putLong(0); flip() }
        val preRoll = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .apply { putLong(80_000_000L); flip() }
        format.setByteBuffer("csd-0", head)
        format.setByteBuffer("csd-1", preSkip)
        format.setByteBuffer("csd-2", preRoll)

        ptsUs = 0
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun decode(packet: ByteArray) {
        val codec = decoder ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val input = codec.getInputBuffer(inIndex) ?: return
                input.clear()
                input.put(packet)
                codec.queueInputBuffer(inIndex, 0, packet.size, ptsUs, 0)
                ptsUs += 20_000
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                if (outIndex >= 0) {
                    val output = codec.getOutputBuffer(outIndex)
                    if (output != null) {
                        track?.write(output, info.size, AudioTrack.WRITE_BLOCKING)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                } else if (outIndex != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    break
                }
            }
        } catch (e: IllegalStateException) {
            // the decoder was released while a packet was in flight (disconnect)
        }
    }

    private fun report(text: String) {
        lastStatus = text
        onStatus?.invoke(text)
    }

    // the connection ended without the user asking: retry with backoff if wanted
    private fun lost(text: String) {
        if (!wantConnected) {
            finish(text)
            return
        }
        if (attempt >= MAX_ATTEMPTS) {
            wantConnected = false
            finish("$text, gave up after $MAX_ATTEMPTS attempts")
            return
        }
        webSocket = null
        releaseTrack()
        val delay = minOf(1000L shl minOf(attempt, 4), 15000L)
        attempt++
        report("$text, retrying in ${delay / 1000} s")
        handler.postDelayed({ open() }, delay)
    }

    // common end of a connection: free the audio, report, remove the notification
    private fun finish(text: String) {
        webSocket = null
        releaseWifiLock()
        releaseTrack()
        report(text)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseTrack() {
        decoder?.let {
            decoder = null
            it.stop()
            it.release()
        }
        track?.stop()
        track?.release()
        track = null
    }

    override fun onDestroy() {
        super.onDestroy()
        wantConnected = false
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, null)
        webSocket = null
        releaseWifiLock()
        releaseTrack()
    }

    companion object {
        private const val ACTION_STOP = "com.example.soundtransmitter.STOP"
        private const val MAX_ATTEMPTS = 10
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
    }
}

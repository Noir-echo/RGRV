package fr.rgrv.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue

/**
 * Moteur audio interne : un thread haute priorité calcule le son des racks et l'envoie à AudioTrack.
 * Le même flux peut être enregistré (bouton REC) dans un fichier WAV.
 */
object AudioEngine {
    lateinit var core: SynthCore
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    private var sampleRate = 48000

    val ready get() = ::core.isInitialized

    fun init(ctx: Context) {
        if (ready) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        core = SynthCore(sampleRate.toFloat())
    }

    fun start() {
        if (running || !ready) return
        running = true
        thread = Thread({ loop() }, "RGRV-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = 256
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minBuf, block * 8 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) { running = false; return }
        val l = FloatArray(block); val r = FloatArray(block)
        val inter = FloatArray(block * 2)
        try {
            track.play()
            while (running) {
                core.render(l, r, block)
                for (i in 0 until block) { inter[2 * i] = l[i]; inter[2 * i + 1] = r[i] }
                Recorder.feed(inter, block * 2)
                track.write(inter, 0, block * 2, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    val rate get() = sampleRate
}

/**
 * Enregistreur WAV (16 bits stéréo). Le thread audio dépose des blocs dans une file,
 * un second thread les écrit sur le disque : l'audio n'attend jamais le stockage.
 */
object Recorder {
    @Volatile var recording = false
        private set
    var startedAt = 0L
        private set
    private var queue = ArrayBlockingQueue<ShortArray>(512)
    private var writer: Thread? = null
    var file: File? = null
        private set

    fun start(ctx: Context): Boolean {
        if (recording) return false
        val f = File(ctx.cacheDir, "rgrv-rec.wav")
        file = f
        queue = ArrayBlockingQueue(512)
        val q = queue
        val raf = try { RandomAccessFile(f, "rw").apply { setLength(0); write(ByteArray(44)) } } catch (e: Exception) { return false }
        recording = true
        startedAt = System.currentTimeMillis()
        writer = Thread({
            var bytes = 0L
            val bb = ByteArray(4096)
            try {
                while (recording || q.isNotEmpty()) {
                    val s = q.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    var k = 0
                    for (v in s) { bb[k++] = (v.toInt() and 0xFF).toByte(); bb[k++] = (v.toInt() shr 8 and 0xFF).toByte() }
                    raf.write(bb, 0, k); bytes += k
                }
                writeHeader(raf, bytes, AudioEngine.rate)
            } catch (_: Exception) {
            } finally { try { raf.close() } catch (_: Exception) {} }
        }, "RGRV-rec").also { it.start() }
        return true
    }

    /** Arrête l'enregistrement ; renvoie le fichier terminé (ou null). */
    fun stop(): File? {
        if (!recording) return null
        recording = false
        writer?.join(3000)
        writer = null
        return file
    }

    fun feed(inter: FloatArray, n: Int) {
        if (!recording) return
        val s = ShortArray(n)
        for (i in 0 until n) s[i] = (inter[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        queue.offer(s)
    }

    private fun writeHeader(raf: RandomAccessFile, data: Long, sr: Int) {
        fun i32(v: Long) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())
        fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
        raf.seek(0)
        raf.write("RIFF".toByteArray()); raf.write(i32(36 + data)); raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray()); raf.write(i32(16)); raf.write(i16(1)); raf.write(i16(2))
        raf.write(i32(sr.toLong())); raf.write(i32(sr * 4L)); raf.write(i16(4)); raf.write(i16(16))
        raf.write("data".toByteArray()); raf.write(i32(data))
    }
}

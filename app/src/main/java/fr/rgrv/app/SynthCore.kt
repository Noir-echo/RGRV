package fr.rgrv.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val RACKS = 6

/**
 * Un emplacement du rack : un instrument + ses réglages + effets d'insertion.
 * Les valeurs « cibles » sont écrites par l'interface / le MIDI, puis lissées dans le moteur (pas de clics).
 */
class Rack(val index: Int) {
    @Volatile var type = Instruments.NONE
    @Volatile var channel = index + 1
    val macro = FloatArray(4) { 0.5f }
    @Volatile var drive = 0f        // saturation
    @Volatile var crush = 0f        // réduction de bits / d'échantillonnage
    @Volatile var delay = 0f        // envoi vers le délai
    @Volatile var reverb = 0f       // envoi vers la réverbération
    @Volatile var level = 0.8f
    @Volatile var pan = 0.5f
    @Volatile var mute = false

    // --- état interne du moteur
    internal var inst: Instrument? = null
    internal var builtType = -1
    internal var crushHold = 0f
    internal var crushCount = 0f
    internal var peak = 0f          // niveau affiché (vumètre)

    fun choose(t: Int) {
        type = t
        val d = Instruments.defaults(t)
        for (i in 0..3) macro[i] = d[i]
    }
}

/**
 * Cœur du moteur audio (sans Android : testable sur ordinateur).
 * render() mélange tous les racks, applique délai et réverbération partagés, puis un limiteur doux.
 */
class SynthCore(val sr: Float) {
    val racks = Array(RACKS) { Rack(it) }
    @Volatile var bpm = 120
    /** Tempo suivi (celui du séquenceur) pour caler le délai. */
    @Volatile var bpmSource: (() -> Int)? = null
    @Volatile var master = 0.85f

    private val buf = FloatArray(4096)
    private val dlyIn = FloatArray(4096)
    private val revIn = FloatArray(4096)
    private val reverbFx = Reverb(sr)
    private val delayFx = PingPong(sr)

    // file d'événements MIDI (écrite par n'importe quel thread, lue au début de chaque bloc)
    private val evLock = Any()
    private val ev = IntArray(4096)
    private var evHead = 0
    private var evTail = 0

    init {
        // configuration par défaut : une batterie et 4 synthés, sur les canaux 1 à 5 (dans l'ordre des racks)
        racks[0].choose(Instruments.KIT); racks[0].channel = 1
        racks[1].choose(Instruments.ACID); racks[1].channel = 2
        racks[2].choose(Instruments.MONO); racks[2].channel = 3
        racks[3].choose(Instruments.SUPER); racks[3].channel = 4; racks[3].reverb = 0.35f
        racks[4].choose(Instruments.FM); racks[4].channel = 5; racks[4].delay = 0.25f
        racks[5].channel = 6
    }

    /** Premier canal (1, 2, 3…) qu'aucun autre rack actif n'utilise. */
    fun freeChannel(except: Rack): Int =
        (1..16).firstOrNull { ch -> racks.none { it !== except && it.type != Instruments.NONE && it.channel == ch } } ?: except.channel

    /** Ce canal est-il joué par un rack interne ? */
    fun owns(ch: Int): Boolean = racks.any { it.type != Instruments.NONE && it.channel == ch }

    /** Message MIDI de canal (status complet, data1, data2). */
    fun midi(status: Int, d1: Int, d2: Int) {
        synchronized(evLock) {
            val next = (evHead + 1) % ev.size
            if (next == evTail) return            // file pleine : on ignore
            ev[evHead] = (status and 0xFF shl 16) or (d1 and 0x7F shl 8) or (d2 and 0x7F)
            evHead = next
        }
    }

    private fun drainEvents() {
        while (true) {
            val e: Int
            synchronized(evLock) {
                if (evTail == evHead) return
                e = ev[evTail]
                evTail = (evTail + 1) % ev.size
            }
            val st = (e shr 16) and 0xFF
            val d1 = (e shr 8) and 0x7F
            val d2 = e and 0x7F
            val ch = (st and 0x0F) + 1
            val type = st and 0xF0
            for (r in racks) {
                if (r.type == Instruments.NONE || r.channel != ch) continue
                val inst = r.inst ?: continue
                when (type) {
                    0x90 -> if (d2 > 0) inst.noteOn(d1, d2) else inst.noteOff(d1)
                    0x80 -> inst.noteOff(d1)
                    0xE0 -> inst.bend = (((d2 shl 7) or d1) - 8192) / 8192f * 2f
                    0xB0 -> cc(r, d1, d2)
                }
            }
        }
    }

    /** Correspondance des CC (les mêmes que les boutons du Controller par défaut). */
    private fun cc(r: Rack, cc: Int, v: Int) {
        val f = v / 127f
        when (cc) {
            1 -> r.inst?.mod = f
            7 -> r.level = f
            10 -> r.pan = f
            74 -> r.macro[0] = f
            71 -> r.macro[1] = f
            73 -> r.macro[2] = f
            72 -> r.macro[3] = f
            12 -> r.drive = f
            13 -> r.crush = f
            93 -> r.delay = f
            91 -> r.reverb = f
            120, 123 -> r.inst?.allOff()
        }
    }

    fun panic() { racks.forEach { it.inst?.allOff() } }

    /** Calcule `n` images stéréo dans outL / outR. */
    fun render(outL: FloatArray, outR: FloatArray, n: Int) {
        drainEvents()
        for (i in 0 until n) { outL[i] = 0f; outR[i] = 0f; dlyIn[i] = 0f; revIn[i] = 0f }
        for (r in racks) {
            // (re)création de l'instrument si le type a changé
            if (r.builtType != r.type) {
                r.inst = Instruments.create(r.type, sr)
                r.builtType = r.type
            }
            val inst = r.inst ?: continue
            // lissage des macros vers les valeurs cibles
            for (k in 0..3) inst.m[k] += (r.macro[k] - inst.m[k]) * 0.25f
            for (i in 0 until n) buf[i] = 0f
            inst.render(buf, n)
            if (r.mute) continue
            val drive = r.drive; val crush = r.crush
            val step = 1f + crush * crush * 24f                      // réduction d'échantillonnage
            val levels = if (crush > 0.01f) (2f + (1f - crush) * 254f) else 0f   // réduction de bits
            val ang = (r.pan * PI / 2).toFloat()
            val gl = cos(ang) * r.level * 1.4f; val gr = sin(ang) * r.level * 1.4f
            var pk = 0f
            for (i in 0 until n) {
                var x = buf[i]
                if (drive > 0.01f) x = softClip(x * (1f + drive * 14f)) * (1f - drive * 0.35f)
                if (crush > 0.01f) {
                    r.crushCount += 1f
                    if (r.crushCount >= step) { r.crushCount -= step; r.crushHold = x }
                    x = Math.round(r.crushHold * levels) / levels
                }
                outL[i] += x * gl; outR[i] += x * gr
                dlyIn[i] += x * r.delay * r.level
                revIn[i] += x * r.reverb * r.level
                val a = if (x < 0) -x else x
                if (a > pk) pk = a
            }
            r.peak = maxOf(pk * r.level, r.peak * 0.85f)
        }
        // effets partagés : délai (3/16e, ping-pong) et réverbération
        val dsec = 60f / (bpmSource?.invoke() ?: bpm).coerceIn(30, 300) * 0.75f
        for (i in 0 until n) {
            delayFx.process(dlyIn[i], dsec, 0.48f)
            reverbFx.process(revIn[i] + (delayFx.outL + delayFx.outR) * 0.15f)
            val l = outL[i] + delayFx.outL + reverbFx.outL * 2.2f
            val r = outR[i] + delayFx.outR + reverbFx.outR * 2.2f
            outL[i] = softClip(l * master)
            outR[i] = softClip(r * master)
        }
    }
}

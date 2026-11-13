package fr.rgrv.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/*
 * Briques de synthèse, écrites pour RGRV (aucun code ni échantillon repris d'un autre produit).
 * Algorithmes classiques du domaine public : PolyBLEP, filtre à variable d'état « TPT »,
 * filtre en échelle 4 pôles, réverbération de type Freeverb (algorithme publié en domaine public).
 */

const val TAU = (2 * PI).toFloat()

fun midiHz(n: Float): Float = 440f * 2f.pow((n - 69f) / 12f)

/** Tangente hyperbolique rapide (saturation douce). */
fun softClip(x: Float): Float {
    if (x > 3f) return 1f
    if (x < -3f) return -1f
    val x2 = x * x
    return x * (27f + x2) / (27f + 9f * x2)
}

/** Bruit blanc rapide (xorshift), sans allocation. */
class Noise(seed: Int = 0x1234567) {
    private var s = seed
    fun next(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return (s and 0xFFFFFF) / 8388608f - 1f
    }
}

/** Correction PolyBLEP pour des oscillateurs dent de scie / carré sans repliement audible. */
fun polyBlep(t: Float, dt: Float): Float {
    if (t < dt) { val x = t / dt; return x + x - x * x - 1f }
    if (t > 1f - dt) { val x = (t - 1f) / dt; return x * x + x + x + 1f }
    return 0f
}

class SawOsc {
    var phase = 0f
    fun next(freq: Float, sr: Float): Float {
        val dt = (freq / sr).coerceIn(0f, 0.45f)
        var v = 2f * phase - 1f
        v -= polyBlep(phase, dt)
        phase += dt
        if (phase >= 1f) phase -= 1f
        return v
    }
}

class SquareOsc {
    var phase = 0f
    fun next(freq: Float, sr: Float, pw: Float = 0.5f): Float {
        val dt = (freq / sr).coerceIn(0f, 0.45f)
        var v = if (phase < pw) 1f else -1f
        v += polyBlep(phase, dt)
        var t2 = phase - pw; if (t2 < 0f) t2 += 1f
        v -= polyBlep(t2, dt)
        phase += dt
        if (phase >= 1f) phase -= 1f
        return v
    }
}

/** Enveloppe ADSR exponentielle (temps en secondes). */
class Env {
    var a = 0.005f; var d = 0.2f; var s = 0.7f; var r = 0.2f
    var value = 0f
    private var stage = 0          // 0 repos, 1 attaque, 2 décroissance, 3 maintien, 4 relâche
    val active get() = stage != 0
    fun gate(on: Boolean) { stage = if (on) 1 else if (stage != 0) 4 else 0 }
    fun kill() { stage = 0; value = 0f }
    fun next(sr: Float): Float {
        when (stage) {
            1 -> { value += (1.15f - value) * coef(a, sr); if (value >= 1f) { value = 1f; stage = 2 } }
            2 -> { value += (s - value) * coef(d, sr); if (abs(value - s) < 0.0005f) stage = 3 }
            3 -> value = s
            4 -> { value += (0f - value) * coef(r, sr); if (value < 0.0002f) { value = 0f; stage = 0 } }
        }
        return value
    }
    private fun coef(t: Float, sr: Float) = 1f - exp(-1f / (t.coerceAtLeast(0.0005f) * sr / 4.6f))
}

/** Filtre à variable d'état (topologie « TPT ») : passe-bas / passe-bande / passe-haut. */
class Svf {
    private var ic1 = 0f; private var ic2 = 0f
    var lp = 0f; var bp = 0f; var hp = 0f
    fun process(x: Float, cutoff: Float, res: Float, sr: Float) {
        val g = tan(PI.toFloat() * (cutoff.coerceIn(20f, sr * 0.45f) / sr))
        val k = 2f - 2f * res.coerceIn(0f, 0.98f)
        val a1 = 1f / (1f + g * (g + k))
        val v3 = x - ic2
        val v1 = a1 * ic1 + g * a1 * v3
        val v2 = ic2 + g * v1
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        lp = v2; bp = v1; hp = x - k * v1 - v2
    }
    fun reset() { ic1 = 0f; ic2 = 0f }
}

/** Filtre en échelle 4 pôles (24 dB/oct) avec saturation, résonance jusqu'à l'auto-oscillation. */
class Ladder {
    private val s = FloatArray(4)
    private var fb = 0f
    fun process(x: Float, cutoff: Float, res: Float, sr: Float): Float {
        val f = (cutoff.coerceIn(20f, sr * 0.42f) / sr)
        val g = 1f - exp(-TAU * f)
        val r = res.coerceIn(0f, 1.05f) * 4f
        var inp = softClip(x - r * fb)
        for (i in 0 until 4) {
            s[i] += g * (softClip(inp) - softClip(s[i]))
            inp = s[i]
        }
        fb = s[3]
        return s[3]
    }
    fun reset() { s.fill(0f); fb = 0f }
}

/** Réverbération (structure Freeverb : 8 filtres en peigne + 4 passe-tout par côté). */
class Reverb(sr: Float) {
    private val scale = sr / 44100f
    private val combL = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb((it * scale).toInt()) }
    private val combR = intArrayOf(1139, 1211, 1300, 1379, 1445, 1514, 1580, 1640).map { Comb((it * scale).toInt()) }
    private val apL = intArrayOf(556, 441, 341, 225).map { AllPass((it * scale).toInt()) }
    private val apR = intArrayOf(579, 464, 364, 248).map { AllPass((it * scale).toInt()) }
    var room = 0.84f
    var damp = 0.3f
    var outL = 0f; var outR = 0f
    fun process(x: Float) {
        val inp = x * 0.015f
        var l = 0f; var r = 0f
        for (c in combL) l += c.process(inp, room, damp)
        for (c in combR) r += c.process(inp, room, damp)
        for (a in apL) l = a.process(l)
        for (a in apR) r = a.process(r)
        outL = l; outR = r
    }
    private class Comb(n: Int) {
        private val buf = FloatArray(n.coerceAtLeast(1)); private var i = 0; private var store = 0f
        fun process(x: Float, fb: Float, damp: Float): Float {
            val out = buf[i]
            store = out * (1f - damp) + store * damp
            buf[i] = x + store * fb
            if (++i >= buf.size) i = 0
            return out
        }
    }
    private class AllPass(n: Int) {
        private val buf = FloatArray(n.coerceAtLeast(1)); private var i = 0
        fun process(x: Float): Float {
            val b = buf[i]
            buf[i] = x + b * 0.5f
            if (++i >= buf.size) i = 0
            return b - x
        }
    }
}

/** Délai stéréo ping-pong synchronisé au tempo, retour filtré. */
class PingPong(private val sr: Float) {
    private val n = (sr * 2.1f).toInt()
    private val l = FloatArray(n); private val r = FloatArray(n)
    private var w = 0
    private var lpL = 0f; private var lpR = 0f
    var outL = 0f; var outR = 0f
    fun process(x: Float, seconds: Float, feedback: Float) {
        val d = (seconds * sr).toInt().coerceIn(1, n - 1)
        var ri = w - d; if (ri < 0) ri += n
        val dl = l[ri]; val dr = r[ri]
        lpL += (dl - lpL) * 0.35f; lpR += (dr - lpR) * 0.35f
        l[w] = x + lpR * feedback        // gauche ← droite : effet ping-pong
        r[w] = lpL * feedback
        if (++w >= n) w = 0
        outL = dl; outR = dr
    }
}

fun sinF(x: Float) = sin(x)
fun tanhF(x: Float) = tanh(x)

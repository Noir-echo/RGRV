package fr.rgrv.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/*
 * Moteurs de synthèse supplémentaires de RGRV (code original, aucun échantillon).
 *
 *  WAVE   polyphonique à table d'ondes : l'onde se transforme en continu (sinus → triangle → scie → carré → impulsion)
 *  PLUCK  cordes pincées par modèle physique (guitare, harpe, koto, basse pincée)
 *  ORGAN  orgue à roues phoniques : 7 tirettes, percussion, effet rotatif, clic de touche
 *  SUB    basse « boom » des boîtes à rythmes : sinus grave, attaque qui claque, glissé entre notes liées
 *  VOX    chœur à formants : voyelles A-E-I-O-U, souffle, ensemble
 */

/** Sinus par table (rapide) : x en tours (0–1). */
object SineTable {
    private const val N = 4096
    private val t = FloatArray(N + 1) { sin(it * TAU / N) }
    fun at(x: Float): Float {
        var p = x - x.toInt(); if (p < 0f) p += 1f
        val f = p * N
        val i = f.toInt()
        val fr = f - i
        return t[i] + (t[i + 1] - t[i]) * fr
    }
}

/** Gestion de voix commune aux moteurs polyphoniques. */
private fun <V> pickVoice(voices: Array<V>, active: (V) -> Boolean, age: (V) -> Long): V =
    voices.firstOrNull { !active(it) } ?: voices.minByOrNull(age)!!

// --------------------------------------------------------------------------------------------- WAVE
class Wave(sr: Float) : Instrument(sr) {
    private inner class Voice {
        var ph = 0f; var ph2 = 0.37f; var lfo = 0f
        val env = Env(); val svf = Svf()
        var note = -1; var vel = 1f; var age = 0L
    }
    private val voices = Array(8) { Voice() }
    private var clock = 0L

    private fun shape(v: Voice) {
        // ENV : de très percussif (0) à nappe lente (1)
        val s = m[3]
        v.env.a = 0.002f + s * s * 1.5f
        v.env.d = 0.15f + s * 1.2f
        v.env.s = 0.2f + s * 0.7f
        v.env.r = 0.06f + s * 2f
    }

    /** Onde à la position pos (0–4) : on fond deux formes voisines. */
    private fun osc(ph: Float, dt: Float, pos: Float): Float {
        val i = pos.toInt().coerceIn(0, 3)
        val fr = (pos - i).coerceIn(0f, 1f)
        return form(i, ph, dt) * (1f - fr) + form(i + 1, ph, dt) * fr
    }

    private fun form(k: Int, ph: Float, dt: Float): Float = when (k) {
        0 -> SineTable.at(ph)
        1 -> 1f - 4f * abs(ph - 0.5f)
        2 -> 2f * ph - 1f - polyBlep(ph, dt)
        3 -> {
            var v = if (ph < 0.5f) 1f else -1f
            v += polyBlep(ph, dt)
            var t2 = ph - 0.5f; if (t2 < 0f) t2 += 1f
            v - polyBlep(t2, dt)
        }
        else -> {
            val pw = 0.12f
            var v = if (ph < pw) 1f else -0.15f
            v += polyBlep(ph, dt) * 0.575f
            var t2 = ph - pw; if (t2 < 0f) t2 += 1f
            v - polyBlep(t2, dt) * 0.575f
        }
    }

    override fun noteOn(n: Int, vel: Int) {
        val v = pickVoice(voices, { it.env.active }, { it.age })
        v.note = n; v.vel = vel / 127f; v.age = clock++
        shape(v); v.env.gate(true)
    }
    override fun noteOff(n: Int) { voices.forEach { if (it.note == n) it.env.gate(false) } }
    override fun allOff() { voices.forEach { it.env.gate(false) } }

    override fun render(out: FloatArray, count: Int) {
        val vib = vibrato()
        val cutoff = 150f * 2f.pow(m[2] * 6.8f)
        for (v in voices) {
            if (!v.env.active) continue
            shape(v)
            val f = midiHz(v.note + bend + vib)
            val dt = (f / sr).coerceAtMost(0.45f)
            val dt2 = (f * 1.004f / sr).coerceAtMost(0.45f)
            val lfoInc = (0.15f + m[1] * 3f) / sr
            for (i in 0 until count) {
                v.lfo += lfoInc; if (v.lfo >= 1f) v.lfo -= 1f
                // MOVE : l'onde balaye la table toute seule, autour de la position WAVE
                val pos = (m[0] * 4f + SineTable.at(v.lfo) * m[1] * 1.6f).coerceIn(0f, 4f)
                val s = osc(v.ph, dt, pos) + osc(v.ph2, dt2, pos) * 0.6f
                v.ph += dt; if (v.ph >= 1f) v.ph -= 1f
                v.ph2 += dt2; if (v.ph2 >= 1f) v.ph2 -= 1f
                val e = v.env.next(sr)
                v.svf.process(s * 0.35f, cutoff * (0.5f + 0.5f * e), 0.25f, sr)
                out[i] += v.svf.lp * e * v.vel * 1.2f
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- PLUCK
class Pluck(sr: Float) : Instrument(sr) {
    private val noise = Noise(0x5EED)
    private inner class Voice {
        val buf = FloatArray((sr / 25f).toInt() + 4)
        var w = 0; var lp = 0f; var exc = 0
        var excLp = 0f
        var note = -1; var vel = 1f; var age = 0L
        var held = false; var level = 0f; var active = false
        val body = Svf()
    }
    private val voices = Array(8) { Voice() }
    private var clock = 0L

    override fun noteOn(n: Int, vel: Int) {
        val v = pickVoice(voices, { it.active }, { it.age })
        v.note = n; v.vel = vel / 127f; v.age = clock++
        v.held = true; v.active = true; v.level = 1f
        v.exc = (sr / midiHz(n.toFloat()).coerceAtLeast(30f)).toInt().coerceIn(2, v.buf.size - 2)
        v.excLp = 0f
    }
    override fun noteOff(n: Int) { voices.forEach { if (it.note == n) it.held = false } }
    override fun allOff() { voices.forEach { it.held = false; it.active = false; it.buf.fill(0f); it.lp = 0f } }

    override fun render(out: FloatArray, count: Int) {
        val bright = 0.15f + m[0] * 0.85f                     // TONE : attaque douce → métallique
        val sustain = 0.985f + m[1] * 0.0145f                 // DECAY
        val damp = 0.05f + m[2] * 0.6f                        // DAMP : corde claire → étouffée
        val bodyAmt = m[3]                                    // BODY : caisse de résonance
        for (v in voices) {
            if (!v.active) continue
            val f = midiHz(v.note + bend + vibrato())
            val delay = (sr / f - damp / (1f - damp)).coerceIn(2f, v.buf.size - 2f)
            val fb = if (v.held) sustain else 0.9f
            val n = v.buf.size
            var energy = 0f
            for (i in 0 until count) {
                var rp = v.w - delay
                while (rp < 0f) rp += n
                val i0 = rp.toInt(); val fr = rp - i0
                val a = v.buf[i0 % n]; val b = v.buf[(i0 + 1) % n]
                val y = a + (b - a) * fr
                v.lp = y * (1f - damp) + v.lp * damp
                var x = v.lp * fb
                if (v.exc > 0) {
                    // excitation : bruit filtré (le « coup de médiator »)
                    v.excLp += (noise.next() - v.excLp) * bright
                    x += v.excLp * v.vel
                    v.exc--
                }
                v.buf[v.w] = x
                v.w++; if (v.w >= n) v.w = 0
                v.body.process(y, 210f, 0.6f, sr)
                val o = y * (1f - bodyAmt * 0.5f) + v.body.bp * bodyAmt * 1.2f
                out[i] += o * 1.0f
                val ay = if (y < 0) -y else y
                if (ay > energy) energy = ay
            }
            v.level = maxOf(energy, v.level * 0.97f)
            if (v.exc == 0 && v.level < 0.0004f) { v.active = false; v.buf.fill(0f); v.lp = 0f }
        }
    }
}

// --------------------------------------------------------------------------------------------- ORGAN
class Organ(sr: Float) : Instrument(sr) {
    private val noise = Noise(0x0A6A)
    // tirettes : 16', 8', 5 1/3', 4', 2 2/3', 2', 1'
    private val ratios = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, 4f, 8f)
    // registrations parcourues par DRAWBAR (douce → jazz → gospel → plein jeu)
    private val regs = arrayOf(
        floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f),
        floatArrayOf(0.9f, 1f, 0.9f, 0f, 0f, 0f, 0f),
        floatArrayOf(0.9f, 1f, 0.9f, 0.6f, 0.4f, 0.6f, 0.3f),
        floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f))
    private val gains = FloatArray(7)
    private inner class Voice {
        val ph = FloatArray(7)
        var note = -1; var age = 0L
        var amp = 0f; var gate = false
        var perc = 0f; var click = 0f
    }
    private val voices = Array(8) { Voice() }
    private var clock = 0L
    private var rot = 0f
    private var rotSpeed = 0.8f

    override fun noteOn(n: Int, vel: Int) {
        val v = pickVoice(voices, { it.gate || it.amp > 0.001f }, { it.age })
        v.note = n; v.age = clock++; v.gate = true
        // la percussion ne se déclenche que si aucune autre touche n'est tenue (comme sur les vrais orgues)
        if (voices.none { it !== v && it.gate }) v.perc = 1f
        v.click = 1f
    }
    override fun noteOff(n: Int) { voices.forEach { if (it.note == n) it.gate = false } }
    override fun allOff() { voices.forEach { it.gate = false } }

    override fun render(out: FloatArray, count: Int) {
        // registration interpolée
        val rp = m[0] * 3f
        val ri = rp.toInt().coerceIn(0, 2); val rf = rp - ri
        for (k in 0 until 7) gains[k] = regs[ri][k] * (1f - rf) + regs[ri + 1][k] * rf
        val percAmt = m[1]
        val percDec = exp(-1f / (0.18f * sr))
        // ROTARY : de lent (choral) à rapide (trémolo) — la vitesse glisse doucement, comme un vrai haut-parleur tournant
        val targetSpeed = 0.7f + m[2] * 6f
        rotSpeed += (targetSpeed - rotSpeed) * 0.02f
        val rotDepth = 0.15f + m[2] * 0.35f
        val drive = m[3]
        val atk = 1f - exp(-1f / (0.004f * sr))
        val rel = 1f - exp(-1f / (0.03f * sr))
        for (v in voices) {
            if (!v.gate && v.amp < 0.0005f) continue
            val f = midiHz(v.note + bend)
            for (i in 0 until count) {
                v.amp += ((if (v.gate) 1f else 0f) - v.amp) * (if (v.gate) atk else rel)
                var s = 0f
                for (k in 0 until 7) {
                    if (gains[k] <= 0.001f) { continue }
                    val inc = f * ratios[k] / sr
                    if (inc > 0.45f) continue
                    v.ph[k] += inc; if (v.ph[k] >= 1f) v.ph[k] -= 1f
                    s += SineTable.at(v.ph[k]) * gains[k]
                }
                s *= 0.18f
                if (v.perc > 0.0005f) {
                    // percussion : 3e harmonique qui s'éteint vite
                    s += SineTable.at(v.ph[1] * 3f) * v.perc * percAmt * 0.5f
                    v.perc *= percDec
                }
                if (v.click > 0.001f) { s += noise.next() * v.click * 0.08f * (0.3f + drive); v.click *= 0.985f }
                out[i] += s * v.amp
            }
        }
        // effet rotatif (sur l'ensemble) puis saturation de l'ampli
        for (i in 0 until count) {
            rot += rotSpeed / sr; if (rot >= 1f) rot -= 1f
            var x = out[i] * (1f - rotDepth + rotDepth * (0.5f + 0.5f * SineTable.at(rot)))
            if (drive > 0.01f) x = softClip(x * (1f + drive * 5f)) * (1f - drive * 0.3f)
            out[i] = x
        }
    }
}

// --------------------------------------------------------------------------------------------- SUB
class Sub(sr: Float) : Instrument(sr) {
    private val held = ArrayList<Int>()
    private var ph = 0f
    private var note = 36f; private var target = 36f
    private var amp = 0f; private var t = 10f
    private var gate = false; private var vel = 1f
    private var lp = 0f

    override fun noteOn(n: Int, v: Int) {
        val legato = held.isNotEmpty()
        held.remove(n); held.add(n)
        target = n.toFloat()
        if (!legato) { note = target; t = 0f; amp = 1f; vel = 0.6f + v / 127f * 0.4f }
        gate = true
    }
    override fun noteOff(n: Int) {
        held.remove(n)
        if (held.isEmpty()) gate = false else target = held.last().toFloat()
    }
    override fun allOff() { held.clear(); gate = false; amp = 0f }

    override fun render(out: FloatArray, count: Int) {
        val decay = 0.15f + m[0] * m[0] * 3.5f                    // DECAY
        val punch = m[1] * 24f                                    // PUNCH : demi-tons au départ
        val drive = m[2]                                          // DRIVE
        val glideT = 0.005f + m[3] * m[3] * 0.45f                 // GLIDE
        val glide = 1f - exp(-1f / (glideT * sr))
        val dec = exp(-1f / (decay * sr))
        val relC = exp(-1f / (0.04f * sr))
        if (amp < 0.00005f) return
        for (i in 0 until count) {
            t += 1f / sr
            note += (target - note) * glide
            val p = note + bend + vibrato() + punch * exp(-t * 40f)
            ph += midiHz(p) / sr; if (ph >= 1f) ph -= 1f
            var s = SineTable.at(ph)
            if (drive > 0.01f) s = softClip(s * (1f + drive * 9f)) * (1f - drive * 0.25f)
            // le clic d'attaque qui fait « claquer » la basse
            s += SineTable.at(ph * 2f) * exp(-t * 90f) * m[1] * 0.4f
            amp *= if (gate) dec else relC
            lp += (s - lp) * 0.5f
            out[i] += lp * amp * vel * 0.9f
        }
    }
}

// --------------------------------------------------------------------------------------------- VOX
class Vox(sr: Float) : Instrument(sr) {
    private val noise = Noise(0xF0E7)
    // formants (Hz) : A, E, I, O, U
    private val f1 = floatArrayOf(800f, 420f, 290f, 480f, 330f)
    private val f2 = floatArrayOf(1180f, 1900f, 2200f, 820f, 720f)
    private val f3 = floatArrayOf(2800f, 2600f, 2950f, 2700f, 2500f)
    private inner class Voice {
        val saw1 = SawOsc(); val saw2 = SawOsc()
        val a = Svf(); val b = Svf(); val c = Svf()
        val env = Env().apply { a = 0.08f; d = 0.3f; s = 0.85f; r = 0.5f }
        var note = -1; var vel = 1f; var age = 0L
    }
    private val voices = Array(6) { Voice() }
    private var clock = 0L

    override fun noteOn(n: Int, vel: Int) {
        val v = pickVoice(voices, { it.env.active }, { it.age })
        v.note = n; v.vel = vel / 127f; v.age = clock++
        v.env.a = 0.01f + m[3] * m[3] * 1.2f
        v.env.r = 0.15f + m[3] * 1.5f
        v.env.gate(true)
    }
    override fun noteOff(n: Int) { voices.forEach { if (it.note == n) it.env.gate(false) } }
    override fun allOff() { voices.forEach { it.env.gate(false) } }

    override fun render(out: FloatArray, count: Int) {
        // VOWEL : A → E → I → O → U, en fondu
        val vp = m[0] * 4f
        val vi = vp.toInt().coerceIn(0, 3); val vf = vp - vi
        val F1 = f1[vi] + (f1[vi + 1] - f1[vi]) * vf
        val F2 = f2[vi] + (f2[vi + 1] - f2[vi]) * vf
        val F3 = f3[vi] + (f3[vi + 1] - f3[vi]) * vf
        val breath = m[1]
        val ens = 0.03f + m[2] * 0.25f                          // ENSEMBLE : désaccord des deux voix
        val vib = vibrato()
        for (v in voices) {
            if (!v.env.active) continue
            val f = midiHz(v.note + bend + vib)
            val fa = midiHz(v.note + bend + vib + ens)
            for (i in 0 until count) {
                val src = (v.saw1.next(f, sr) + v.saw2.next(fa, sr)) * 0.5f * (1f - breath * 0.6f) + noise.next() * breath * 0.5f
                v.a.process(src, F1, 0.9f, sr)
                v.b.process(src, F2, 0.92f, sr)
                v.c.process(src, F3, 0.93f, sr)
                val s = v.a.bp + v.b.bp * 0.55f + v.c.bp * 0.3f
                out[i] += s * v.env.next(sr) * v.vel * 0.55f
            }
        }
    }
}

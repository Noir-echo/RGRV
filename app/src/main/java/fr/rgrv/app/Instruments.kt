package fr.rgrv.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Instruments de RGRV — synthèses originales, libres de droits (codées pour l'appli).
 * Chacun a 4 « macros » (0–1) choisies pour changer fortement le son.
 *
 *  KIT   boîte à rythmes synthétique, 8 sons (notes 36–43, puis en boucle), avec CHAOS (variations à chaque coup)
 *  ACID  basse monophonique esprit « 303 » : filtre résonant, accent (vélocité ≥ 110), slide (notes liées)
 *  MONO  monophonique 3 oscillateurs + filtre 24 dB, esprit gros synthé analogique des années 70
 *  SUPER polyphonique « supersaw » (7 dents de scie désaccordées), esprit virtual-analog des années 2000
 *  FM    polyphonique FM 2 opérateurs : cloches, pianos électriques, basses métalliques, esprit synthé FM des années 80
 */
object Instruments {
    const val NONE = 0; const val KIT = 1; const val ACID = 2; const val MONO = 3; const val SUPER = 4; const val FM = 5
    const val WAVE = 6; const val PLUCK = 7; const val ORGAN = 8; const val SUB = 9; const val VOX = 10
    const val BOOM = 11; const val PUNCH = 12; const val DUST = 13; const val PERC = 14; const val INDUS = 15
    val names = arrayOf("—", "KIT", "ACID", "MONO", "SUPER", "FM", "WAVE", "PLUCK", "ORGAN", "SUB", "VOX",
        "BOOM", "PUNCH", "DUST", "PERC", "INDUS")
    /** Ordre d'affichage dans le choix d'instrument. */
    val drums = intArrayOf(KIT, BOOM, PUNCH, DUST, PERC, INDUS)
    val synths = intArrayOf(ACID, MONO, SUB, SUPER, WAVE, FM, PLUCK, ORGAN, VOX)
    fun isDrum(type: Int) = type in drums
    fun macroLabels(type: Int): Array<String> = when (type) {
        KIT, BOOM, PUNCH, DUST, PERC, INDUS -> arrayOf("TUNE", "DECAY", "TONE", "CHAOS")
        ACID -> arrayOf("CUTOFF", "RESO", "ENV", "DECAY")
        MONO -> arrayOf("CUTOFF", "RESO", "CONTOUR", "GLIDE")
        SUPER -> arrayOf("CUTOFF", "SPREAD", "RESO", "SHAPE")
        FM -> arrayOf("RATIO", "DEPTH", "DECAY", "BRIGHT")
        WAVE -> arrayOf("WAVE", "MOVE", "CUTOFF", "ENV")
        PLUCK -> arrayOf("TONE", "DECAY", "DAMP", "BODY")
        ORGAN -> arrayOf("DRAWBAR", "PERC", "ROTARY", "DRIVE")
        SUB -> arrayOf("DECAY", "PUNCH", "DRIVE", "GLIDE")
        VOX -> arrayOf("VOWEL", "BREATH", "ENSEMB", "ATTACK")
        else -> arrayOf("—", "—", "—", "—")
    }
    fun defaults(type: Int): FloatArray = when (type) {
        KIT -> floatArrayOf(0.5f, 0.5f, 0.55f, 0.15f)
        BOOM, PUNCH, DUST, INDUS -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.1f)
        PERC -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.25f)
        ACID -> floatArrayOf(0.35f, 0.7f, 0.6f, 0.35f)
        MONO -> floatArrayOf(0.45f, 0.3f, 0.5f, 0.1f)
        SUPER -> floatArrayOf(0.65f, 0.45f, 0.2f, 0.35f)
        FM -> floatArrayOf(0.3f, 0.45f, 0.5f, 0.3f)
        WAVE -> floatArrayOf(0.4f, 0.3f, 0.6f, 0.35f)
        PLUCK -> floatArrayOf(0.6f, 0.6f, 0.3f, 0.4f)
        ORGAN -> floatArrayOf(0.45f, 0.4f, 0.2f, 0.2f)
        SUB -> floatArrayOf(0.5f, 0.35f, 0.2f, 0.2f)
        VOX -> floatArrayOf(0.0f, 0.2f, 0.4f, 0.4f)
        else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
    }
    fun create(type: Int, sr: Float): Instrument? = when (type) {
        KIT -> Kit(sr); ACID -> Acid(sr); MONO -> Mono(sr); SUPER -> Super(sr); FM -> Fm(sr)
        WAVE -> Wave(sr); PLUCK -> Pluck(sr); ORGAN -> Organ(sr); SUB -> Sub(sr); VOX -> Vox(sr)
        BOOM -> DrumKit(sr, KitStyles.BOOM); PUNCH -> DrumKit(sr, KitStyles.PUNCH); DUST -> DrumKit(sr, KitStyles.DUST)
        PERC -> DrumKit(sr, KitStyles.PERC); INDUS -> DrumKit(sr, KitStyles.INDUS)
        else -> null
    }
    fun describe(type: Int): String = when (type) {
        KIT -> tr("Boîte à rythmes synthétique : grosse caisse, caisse claire, clap, charleys, tom, rim, cloche (notes 36 à 43). CHAOS fait varier chaque coup.",
            "Synth drum machine: kick, snare, clap, hats, tom, rim, bell (notes 36–43). CHAOS varies every hit.")
        BOOM -> tr("Batterie analogique ronde : grosse caisse longue et profonde, conga, cloche (notes 36 à 43).",
            "Round analogue drums: long deep kick, conga, cowbell (notes 36–43).")
        PUNCH -> tr("Batterie techno / house : attaque sèche, caisse claire brillante, ride (notes 36 à 43).",
            "Techno / house drums: tight attack, bright snare, ride (notes 36–43).")
        DUST -> tr("Batterie lo-fi au grain de vieux sampler 12 bits, idéale pour le hip-hop (notes 36 à 43).",
            "Lo-fi drums with the grain of an old 12-bit sampler, great for hip-hop (notes 36–43).")
        PERC -> tr("Percussions : conga grave, conga aiguë, bongo, shaker, tambourin, claves, wood-block, cloche (notes 36 à 43).",
            "Percussion: low conga, high conga, bongo, shaker, tambourine, claves, wood block, bell (notes 36–43).")
        INDUS -> tr("Batterie industrielle saturée : choc, souffle, clap, charley, tôle, laser, tom, métal (notes 36 à 43).",
            "Distorted industrial drums: kick, blast, clap, hat, sheet metal, laser, tom, metal (notes 36–43).")
        ACID -> tr("Basse acid monophonique. Vélocité ≥ 110 = accent ; notes qui se chevauchent = slide.",
            "Monophonic acid bass. Velocity ≥ 110 = accent; overlapping notes = slide.")
        MONO -> tr("Gros mono 3 oscillateurs, filtre 24 dB et glissé : basses et leads.", "Fat 3-oscillator mono, 24 dB filter and glide: basses and leads.")
        SUPER -> tr("Polyphonique supersaw : nappes, accords trance, leads larges. SHAPE va du pluck à la nappe.",
            "Supersaw polysynth: pads, trance chords, wide leads. SHAPE goes from pluck to pad.")
        FM -> tr("Polyphonique FM : cloches, piano électrique, basses métalliques. RATIO change complètement le timbre.",
            "FM polysynth: bells, electric piano, metallic basses. RATIO changes the timbre completely.")
        WAVE -> tr("Polyphonique à table d'ondes : WAVE choisit l'onde (de douce à agressive), MOVE la fait bouger toute seule. ENV va du pluck à la nappe.",
            "Wavetable polysynth: WAVE picks the waveform (soft to harsh), MOVE makes it evolve on its own. ENV goes from pluck to pad.")
        PLUCK -> tr("Cordes pincées par modèle physique : guitare, harpe, koto, basse. DAMP étouffe la corde, BODY ajoute la caisse.",
            "Physically modelled plucked strings: guitar, harp, koto, bass. DAMP mutes the string, BODY adds the body.")
        ORGAN -> tr("Orgue à roues phoniques : DRAWBAR va du son doux au plein jeu, PERC ajoute l'attaque, ROTARY le haut-parleur tournant, DRIVE la saturation.",
            "Tonewheel organ: DRAWBAR goes from soft to full, PERC adds the attack, ROTARY the rotating speaker, DRIVE the overdrive.")
        SUB -> tr("Basse « boom » de boîte à rythmes : sinus très grave et long. PUNCH fait claquer l'attaque, notes liées = glissé.",
            "Drum-machine « boom » bass: deep, long sine. PUNCH adds a snappy attack; overlapping notes = glide.")
        VOX -> tr("Chœur synthétique : VOWEL passe de A à E, I, O, U ; BREATH ajoute du souffle, ENSEMB épaissit les voix.",
            "Synth choir: VOWEL morphs A, E, I, O, U; BREATH adds air, ENSEMB thickens the voices.")
        else -> tr("Emplacement libre.", "Empty slot.")
    }
}

abstract class Instrument(val sr: Float) {
    /** Macros 0–1 (lissées par le rack). */
    val m = FloatArray(4)
    /** Pitch bend en demi-tons, modulation 0–1. */
    var bend = 0f
    var mod = 0f
    protected var lfo = 0f
    abstract fun noteOn(n: Int, vel: Int)
    abstract fun noteOff(n: Int)
    abstract fun allOff()
    /** Ajoute `count` échantillons mono dans out. */
    abstract fun render(out: FloatArray, count: Int)
    protected fun vibrato(): Float {
        lfo += TAU * 5.5f / sr
        if (lfo > TAU) lfo -= TAU
        return sin(lfo) * mod * 0.5f
    }
}

// --------------------------------------------------------------------------------------------- KIT
class Kit(sr: Float) : Instrument(sr) {
    private val noise = Noise(0x2468ACE)
    private inner class Drum(val kind: Int) {
        var t = 0f; var on = false; var vel = 1f
        var phase = 0f; var phase2 = 0f
        val svf = Svf()
        var vTune = 1f; var vDecay = 1f; var vTone = 1f
        fun trigger(v: Float) {
            on = true; t = 0f; vel = v; phase = 0f; phase2 = 0f
            val chaos = m[3]
            vTune = 1f + (noise.next() * 0.5f) * chaos
            vDecay = 1f + (noise.next() * 0.8f) * chaos
            vTone = 1f + (noise.next() * 0.8f) * chaos
        }
        fun next(): Float {
            if (!on) return 0f
            val tune = (0.5f + m[0]) * vTune * 2f.pow(bend / 12f)
            val decay = (0.25f + m[1] * 1.5f) * vDecay
            val tone = (m[2] * vTone).coerceIn(0f, 1.2f)
            val dt = 1f / sr
            t += dt
            var out: Float
            when (kind) {
                0 -> { // grosse caisse : sinus à hauteur décroissante + clic
                    val f = 45f * tune + 140f * tune * exp(-t * 28f)
                    phase += TAU * f * dt
                    val amp = exp(-t * 6f / decay)
                    out = softClip(sin(phase) * amp * (1.6f + tone * 2.5f)) + noise.next() * exp(-t * 400f) * 0.3f * tone
                    if (amp < 0.0005f) on = false
                }
                1 -> { // caisse claire : corps + bruit filtré
                    val f = 185f * tune * (1f + exp(-t * 40f) * 0.5f)
                    phase += TAU * f * dt
                    svf.process(noise.next(), 1500f + 6000f * tone, 0.2f, sr)
                    val body = sin(phase) * exp(-t * 22f / decay)
                    val snap = svf.hp * exp(-t * 14f / decay)
                    out = body * 0.7f + snap * 1.1f
                    if (t > 1.5f * decay + 0.1f) on = false
                }
                2 -> { // clap : 3 salves de bruit puis queue
                    val burst = if (t < 0.03f) (if ((t * 100f).toInt() % 1 == 0 && (t % 0.01f) < 0.004f) 1f else 0.25f) else exp(-(t - 0.03f) * 9f / decay)
                    svf.process(noise.next(), (900f + 1800f * tone) * tune, 0.45f, sr)
                    out = svf.bp * burst * 2.2f
                    if (t > 1.2f * decay + 0.1f) on = false
                }
                3, 4 -> { // charley fermé / ouvert : bruit + carrés métalliques, passe-haut
                    phase += TAU * 540f * tune * dt; phase2 += TAU * 845f * tune * dt
                    val metal = (if (sin(phase) > 0) 1f else -1f) + (if (sin(phase2) > 0) 1f else -1f)
                    svf.process(noise.next() * 0.8f + metal * 0.25f, 6500f + 5000f * tone, 0.15f, sr)
                    val d = if (kind == 3) 0.05f else 0.35f
                    out = svf.hp * exp(-t / (d * decay)) * 0.9f
                    if (t > d * decay * 8f) on = false
                }
                5 -> { // tom
                    val f = 110f * tune * (1f + exp(-t * 18f) * 0.6f)
                    phase += TAU * f * dt
                    out = sin(phase) * exp(-t * 7f / decay) * (1f + tone)
                    if (t > 1.5f * decay) on = false
                }
                6 -> { // rim
                    phase += TAU * 820f * tune * dt
                    out = (sin(phase) * 0.8f + noise.next() * 0.3f * tone) * exp(-t * 90f / decay)
                    if (t > 0.2f * decay) on = false
                }
                else -> { // cloche / perc
                    phase += TAU * 560f * tune * dt; phase2 += TAU * 845f * tune * dt
                    val sq = (if (sin(phase) > 0) 1f else -1f) + (if (sin(phase2) > 0) 1f else -1f)
                    svf.process(sq, 900f + 2500f * tone, 0.5f, sr)
                    out = svf.bp * exp(-t * 9f / decay) * 0.8f
                    if (t > 1.2f * decay) on = false
                }
            }
            return out * vel
        }
    }
    private val drums = Array(8) { Drum(it) }
    override fun noteOn(n: Int, vel: Int) {
        val k = Math.floorMod(n - 36, 8)
        if (k == 3) drums[4].on = false          // le charley fermé coupe l'ouvert
        drums[k].trigger(vel / 127f)
    }
    override fun noteOff(n: Int) {}
    override fun allOff() { drums.forEach { it.on = false } }
    override fun render(out: FloatArray, count: Int) {
        for (i in 0 until count) {
            var s = 0f
            for (d in drums) if (d.on) s += d.next()
            out[i] += s * 0.55f
        }
    }
}

// --------------------------------------------------------------------------------------------- ACID
class Acid(sr: Float) : Instrument(sr) {
    private val saw = SawOsc()
    private val filter = Ladder()
    private val held = ArrayList<Int>()
    private var note = 48f; private var target = 48f
    private var gate = false
    private var amp = 0f; private var fenv = 0f
    private var accent = false
    override fun noteOn(n: Int, vel: Int) {
        val legato = held.isNotEmpty()
        held.remove(n); held.add(n)
        target = n.toFloat()
        accent = vel >= 110
        if (!legato) { note = target; fenv = 1f }   // nouvelle note : enveloppe relancée
        gate = true
    }
    override fun noteOff(n: Int) {
        held.remove(n)
        if (held.isEmpty()) gate = false else target = held.last().toFloat()
    }
    override fun allOff() { held.clear(); gate = false }
    override fun render(out: FloatArray, count: Int) {
        val decay = 0.08f + m[3] * 1.2f
        val fdec = exp(-1f / (decay * sr / (if (accent) 2.2f else 4f)))
        val glide = 1f - exp(-1f / (0.055f * sr))
        for (i in 0 until count) {
            note += (target - note) * glide
            val f = midiHz(note + bend + vibrato())
            val o = saw.next(f, sr)
            fenv *= fdec
            val cutBase = 60f * 2f.pow(m[0] * 7.5f)
            val envAmt = m[2] * (if (accent) 1.6f else 1f)
            val cut = cutBase * 2f.pow(envAmt * 5f * fenv)
            val y = filter.process(o * 0.9f, cut, 0.2f + m[1] * 0.85f, sr)
            val tgt = if (gate) (if (accent) 1.35f else 1f) else 0f
            amp += (tgt - amp) * (if (gate) 0.01f else 0.004f)
            out[i] += y * amp * (1f + m[1] * 1.2f) * 0.9f
        }
    }
}

// --------------------------------------------------------------------------------------------- MONO
class Mono(sr: Float) : Instrument(sr) {
    private val o1 = SawOsc(); private val o2 = SawOsc(); private val o3 = SquareOsc()
    private val filter = Ladder()
    private val ampEnv = Env().apply { a = 0.004f; d = 0.4f; s = 0.75f; r = 0.25f }
    private val fEnv = Env().apply { a = 0.003f; d = 0.35f; s = 0.25f; r = 0.3f }
    private val held = ArrayList<Int>()
    private var note = 48f; private var target = 48f
    private var vel = 1f
    override fun noteOn(n: Int, v: Int) {
        val legato = held.isNotEmpty()
        held.remove(n); held.add(n)
        target = n.toFloat(); vel = 0.6f + v / 127f * 0.4f
        if (!legato || m[3] < 0.02f) { if (!legato) note = target; ampEnv.gate(true); fEnv.gate(true) }
    }
    override fun noteOff(n: Int) {
        held.remove(n)
        if (held.isEmpty()) { ampEnv.gate(false); fEnv.gate(false) } else target = held.last().toFloat()
    }
    override fun allOff() { held.clear(); ampEnv.gate(false); fEnv.gate(false) }
    override fun render(out: FloatArray, count: Int) {
        val glideT = 0.002f + m[3] * m[3] * 0.6f
        val glide = 1f - exp(-1f / (glideT * sr))
        for (i in 0 until count) {
            note += (target - note) * glide
            val n = note + bend + vibrato()
            val f = midiHz(n)
            val osc = o1.next(f, sr) + o2.next(f * 1.0065f, sr) * 0.8f + o3.next(f * 0.5f, sr) * 0.6f
            val fe = fEnv.next(sr)
            val cut = 50f * 2f.pow(m[0] * 8f + m[2] * 4.5f * fe)
            val y = filter.process(osc * 0.45f, cut, m[1] * 0.95f, sr)
            out[i] += y * ampEnv.next(sr) * vel * (1f + m[1])
        }
    }
}

// --------------------------------------------------------------------------------------------- SUPER
class Super(sr: Float) : Instrument(sr) {
    private val detunes = floatArrayOf(-1f, -0.66f, -0.33f, 0f, 0.33f, 0.66f, 1f)
    private inner class Voice {
        val saws = Array(7) { SawOsc().also { s -> s.phase = Math.random().toFloat() } }
        val env = Env(); val svf = Svf()
        var note = -1; var vel = 1f; var age = 0L
    }
    private val voices = Array(8) { Voice() }
    private val freqs = FloatArray(7)
    private var clock = 0L
    private fun shape(v: Voice) {
        val s = m[3]
        v.env.a = 0.002f + s * s * 1.8f
        v.env.d = 0.12f + s * 1.5f
        v.env.s = 0.15f + s * 0.8f
        v.env.r = 0.08f + s * 2.2f
    }
    override fun noteOn(n: Int, vel: Int) {
        val v = voices.firstOrNull { !it.env.active } ?: voices.minByOrNull { it.age }!!
        v.note = n; v.vel = vel / 127f; v.age = clock++
        shape(v); v.env.gate(true)
    }
    override fun noteOff(n: Int) { voices.forEach { if (it.note == n) it.env.gate(false) } }
    override fun allOff() { voices.forEach { it.env.gate(false) } }
    override fun render(out: FloatArray, count: Int) {
        val spread = 0.04f + m[1] * m[1] * 0.75f            // demi-tons
        val cutoff = 120f * 2f.pow(m[0] * 7f)
        val vib = vibrato()
        for (v in voices) {
            if (!v.env.active) continue
            shape(v)
            // fréquences calculées une fois par bloc (économie de calcul)
            val base = v.note + bend + vib
            for (k in 0 until 7) freqs[k] = midiHz(base + detunes[k] * spread)
            for (i in 0 until count) {
                var s = 0f
                for (k in 0 until 7) s += v.saws[k].next(freqs[k], sr) * (if (k == 3) 1f else 0.75f)
                val e = v.env.next(sr)
                v.svf.process(s * 0.22f, cutoff * (0.6f + 0.4f * e), m[2] * 0.9f, sr)
                out[i] += v.svf.lp * e * v.vel * 0.7f
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- FM
class Fm(sr: Float) : Instrument(sr) {
    private val ratios = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, 3.5f, 4f, 5f, 7f, 9f, 11f, 14f)
    private inner class Voice {
        var pc = 0f; var pm = 0f; var fbPrev = 0f
        var note = -1; var vel = 1f; var age = 0L
        val ampEnv = Env(); val modEnv = Env()
    }
    private val voices = Array(8) { Voice() }
    private var clock = 0L
    override fun noteOn(n: Int, vel: Int) {
        val v = voices.firstOrNull { !it.ampEnv.active } ?: voices.minByOrNull { it.age }!!
        v.note = n; v.vel = vel / 127f; v.age = clock++
        val d = 0.08f + m[2] * m[2] * 3.5f
        v.ampEnv.a = 0.002f; v.ampEnv.d = d; v.ampEnv.s = m[2] * 0.4f; v.ampEnv.r = 0.05f + d * 0.4f
        v.modEnv.a = 0.001f; v.modEnv.d = d * 0.5f; v.modEnv.s = m[2] * 0.25f; v.modEnv.r = d * 0.3f
        v.ampEnv.gate(true); v.modEnv.gate(true)
    }
    override fun noteOff(n: Int) { voices.forEach { if (it.note == n) { it.ampEnv.gate(false); it.modEnv.gate(false) } } }
    override fun allOff() { voices.forEach { it.ampEnv.gate(false); it.modEnv.gate(false) } }
    override fun render(out: FloatArray, count: Int) {
        val ratio = ratios[(m[0] * (ratios.size - 0.01f)).toInt().coerceIn(0, ratios.size - 1)]
        val depth = m[1] * m[1] * 9f
        val fb = m[3] * 0.9f
        for (v in voices) {
            if (!v.ampEnv.active) continue
            val f = midiHz(v.note + bend + vibrato())
            val dc = TAU * f / sr; val dm = TAU * f * ratio / sr
            for (i in 0 until count) {
                val me = v.modEnv.next(sr)
                val modOut = sin(v.pm + v.fbPrev * fb) * depth * (0.25f + 0.75f * me) * (0.5f + 0.5f * v.vel)
                v.fbPrev = sin(v.pm)
                val o = sin(v.pc + modOut)
                v.pc += dc; if (v.pc > TAU) v.pc -= TAU
                v.pm += dm; if (v.pm > TAU) v.pm -= TAU
                out[i] += o * v.ampEnv.next(sr) * v.vel * 0.45f
            }
        }
    }
}

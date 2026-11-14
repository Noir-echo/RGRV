package fr.rgrv.app

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Kits de batterie supplémentaires — sons entièrement synthétisés (aucun échantillon).
 * Même principe que KIT : 8 sons sur les notes 36 à 43 (puis en boucle), mêmes réglages
 * TUNE / DECAY / TONE / CHAOS. Chaque kit a son caractère :
 *
 *  BOOM   grosses caisses longues et profondes, cloche, conga : esprit boîte à rythmes analogique du début des années 80
 *  PUNCH  attaque sèche, caisse claire brillante, ride : esprit techno / house
 *  DUST   son granuleux et assourdi, esprit vieux sampler 12 bits (hip-hop, lo-fi)
 *  PERC   percussions : congas, bongo, shaker, tambourin, claves, wood-block, cloche
 *  INDUS  saturé et métallique : chocs, tôles, laser (techno industrielle, EBM)
 */
object KitStyles {
    const val BOOM = 0; const val PUNCH = 1; const val DUST = 2; const val PERC = 3; const val INDUS = 4

    // types de sons
    const val KICK = 0; const val SNARE = 1; const val CLAP = 2; const val HAT = 3; const val TOM = 4
    const val RIM = 5; const val BELL = 6; const val SHAKER = 7; const val TAMB = 8; const val WOOD = 9
    const val ZAP = 10; const val METAL = 11; const val BLAST = 12; const val RIDE = 13; const val CONGA = 14

    /**
     * Description d'un son : type, fréquence principale, fréquence secondaire (balayage, bruit…),
     * durée de base (s), saturation (0–1), « choke » (le son coupe ce numéro, -1 = rien).
     */
    class Spec(val kind: Int, val f: Float, val f2: Float, val dec: Float, val drive: Float = 0f, val choke: Int = -1)

    fun specs(style: Int): Array<Spec> = when (style) {
        BOOM -> arrayOf(
            Spec(KICK, 42f, 55f, 1.1f, 0.15f),
            Spec(SNARE, 190f, 3200f, 0.22f),
            Spec(CLAP, 1100f, 0f, 0.35f),
            Spec(HAT, 400f, 7500f, 0.05f, choke = 4),
            Spec(HAT, 400f, 7000f, 0.45f),
            Spec(CONGA, 160f, 0.25f, 0.35f),
            Spec(RIM, 1700f, 0f, 0.03f),
            Spec(BELL, 540f, 800f, 0.3f))
        PUNCH -> arrayOf(
            Spec(KICK, 50f, 210f, 0.45f, 0.45f),
            Spec(SNARE, 175f, 7000f, 0.2f, 0.2f),
            Spec(CLAP, 1300f, 0f, 0.25f),
            Spec(HAT, 520f, 9500f, 0.035f, choke = 4),
            Spec(HAT, 520f, 8800f, 0.3f),
            Spec(TOM, 120f, 0.7f, 0.35f),
            Spec(RIM, 900f, 0f, 0.04f),
            Spec(RIDE, 610f, 6000f, 1.4f))
        DUST -> arrayOf(
            Spec(KICK, 55f, 90f, 0.45f, 0.35f),
            Spec(SNARE, 215f, 2400f, 0.25f, 0.25f),
            Spec(CLAP, 900f, 0f, 0.3f),
            Spec(HAT, 350f, 5200f, 0.05f, choke = 4),
            Spec(HAT, 350f, 5000f, 0.3f),
            Spec(TOM, 95f, 0.5f, 0.4f),
            Spec(RIM, 1200f, 0f, 0.035f),
            Spec(WOOD, 620f, 0f, 0.08f))
        PERC -> arrayOf(
            Spec(CONGA, 220f, 0.08f, 0.32f),            // conga grave
            Spec(CONGA, 330f, 0.08f, 0.22f),            // conga aiguë
            Spec(CONGA, 470f, 0.06f, 0.14f),            // bongo
            Spec(SHAKER, 0f, 7000f, 0.09f),
            Spec(TAMB, 0f, 9000f, 0.2f),
            Spec(RIM, 2500f, 0f, 0.05f),                // claves
            Spec(WOOD, 880f, 0f, 0.07f),
            Spec(BELL, 800f, 1180f, 0.18f))             // cloche aiguë
        else -> arrayOf(                                // INDUS
            Spec(KICK, 46f, 320f, 0.55f, 1f),
            Spec(BLAST, 1800f, 0f, 0.25f, 0.8f),
            Spec(CLAP, 1500f, 0f, 0.3f, 0.7f),
            Spec(HAT, 700f, 9000f, 0.03f, 0.4f, choke = 4),
            Spec(METAL, 330f, 1.41f, 0.6f, 0.3f),
            Spec(ZAP, 2400f, 60f, 0.18f, 0.3f),
            Spec(TOM, 70f, 1.2f, 0.4f, 0.9f),
            Spec(METAL, 900f, 2.76f, 0.15f, 0.5f))
    }
}

class DrumKit(sr: Float, private val style: Int) : Instrument(sr) {
    private val noise = Noise(0x13579B + style * 7919)
    private val specs = KitStyles.specs(style)

    // DUST : réduction d'échantillonnage / de bits + passe-bas, intégrés au kit
    private var holdCount = 0f
    private var hold = 0f
    private var dustLp = 0f

    private inner class Hit(val s: KitStyles.Spec) {
        var on = false; var t = 0f; var vel = 1f
        var ph = 0f; var ph2 = 0f
        val ph6 = FloatArray(6)
        val svf = Svf(); val svf2 = Svf()
        var vTune = 1f; var vDecay = 1f; var vTone = 1f
        fun trigger(v: Float) {
            on = true; t = 0f; vel = v; ph = 0f; ph2 = 0f
            for (k in 0 until 6) ph6[k] = noise.next() * 0.5f + 0.5f
            val chaos = m[3]
            vTune = 1f + noise.next() * 0.5f * chaos
            vDecay = 1f + noise.next() * 0.8f * chaos
            vTone = 1f + noise.next() * 0.8f * chaos
        }

        /** 6 carrés à rapports inharmoniques : le timbre métallique des charleys analogiques. */
        private fun metal6(base: Float, dt: Float): Float {
            var s = 0f
            for (k in 0 until 6) {
                ph6[k] += base * RATIOS[k] * dt
                if (ph6[k] >= 1f) ph6[k] -= 1f
                s += if (ph6[k] < 0.5f) 1f else -1f
            }
            return s / 6f
        }

        fun next(): Float {
            if (!on) return 0f
            val tune = (0.5f + m[0]) * vTune * 2f.pow(bend / 12f)
            val decay = (0.25f + m[1] * 1.5f) * vDecay
            val tone = (m[2] * vTone).coerceIn(0f, 1.2f)
            val dt = 1f / sr
            t += dt
            val d = s.dec * decay
            var out: Float
            when (s.kind) {
                KitStyles.KICK -> {
                    val f = s.f * tune + s.f2 * tune * exp(-t * 30f)
                    ph += TAU * f * dt; if (ph > TAU) ph -= TAU
                    val amp = exp(-t / d)
                    out = sin(ph) * amp * (1.4f + tone * 1.8f) + noise.next() * exp(-t * 500f) * 0.35f * (0.3f + tone)
                    if (amp < 0.0005f) on = false
                }
                KitStyles.SNARE -> {
                    val f = s.f * tune * (1f + exp(-t * 45f) * 0.4f)
                    ph += TAU * f * dt; if (ph > TAU) ph -= TAU
                    svf.process(noise.next(), s.f2 * (0.5f + tone), 0.25f, sr)
                    out = sin(ph) * exp(-t / (d * 0.6f)) * 0.75f + svf.hp * exp(-t / d) * (0.7f + tone * 0.7f)
                    if (t > d * 7f) on = false
                }
                KitStyles.CLAP -> {
                    val pulse = when {
                        t < 0.011f -> exp(-t * 300f)
                        t < 0.022f -> exp(-(t - 0.011f) * 300f)
                        t < 0.033f -> exp(-(t - 0.022f) * 300f)
                        else -> exp(-(t - 0.033f) / d)
                    }
                    svf.process(noise.next(), s.f * tune * (0.7f + tone * 0.8f), 0.5f, sr)
                    out = svf.bp * pulse * 2.4f
                    if (t > d * 7f + 0.04f) on = false
                }
                KitStyles.HAT, KitStyles.RIDE -> {
                    val mt = metal6(s.f * tune, dt)
                    val src = if (s.kind == KitStyles.RIDE) mt * 0.8f + noise.next() * 0.2f else mt * 0.5f + noise.next() * 0.5f
                    svf.process(src, s.f2 * (0.6f + tone * 0.6f), 0.2f, sr)
                    out = svf.hp * exp(-t / d) * (if (s.kind == KitStyles.RIDE) 0.6f else 0.9f)
                    if (t > d * 8f) on = false
                }
                KitStyles.TOM -> {
                    val f = s.f * tune * (1f + exp(-t * 16f) * s.f2)
                    ph += TAU * f * dt; if (ph > TAU) ph -= TAU
                    out = sin(ph) * exp(-t / d) * (1.1f + tone)
                    if (t > d * 8f) on = false
                }
                KitStyles.CONGA -> {
                    // peau : sinus qui retombe un peu + claquement de bruit au tout début
                    val f = s.f * tune * (1f + exp(-t * 60f) * s.f2)
                    ph += TAU * f * dt; if (ph > TAU) ph -= TAU
                    svf.process(noise.next(), 2500f + 3000f * tone, 0.3f, sr)
                    out = sin(ph) * exp(-t / d) * 1.1f + svf.bp * exp(-t * 180f) * (0.3f + tone * 0.6f)
                    if (t > d * 8f) on = false
                }
                KitStyles.RIM -> {
                    ph += TAU * s.f * tune * dt; if (ph > TAU) ph -= TAU
                    out = (sin(ph) * 0.9f + noise.next() * 0.25f * tone) * exp(-t / d)
                    if (t > d * 8f) on = false
                }
                KitStyles.WOOD -> {
                    ph += TAU * s.f * tune * dt; if (ph > TAU) ph -= TAU
                    svf.process(noise.next(), s.f * tune * 2.2f, 0.7f, sr)
                    out = (sin(ph) * 0.8f + svf.bp * exp(-t * 250f) * 0.8f) * exp(-t / d)
                    if (t > d * 8f) on = false
                }
                KitStyles.BELL -> {
                    ph += s.f * tune * dt; if (ph >= 1f) ph -= 1f
                    ph2 += s.f2 * tune * dt; if (ph2 >= 1f) ph2 -= 1f
                    val sq = (if (ph < 0.5f) 1f else -1f) + (if (ph2 < 0.5f) 1f else -1f)
                    svf.process(sq, s.f * tune * 1.6f * (0.7f + tone * 0.6f), 0.55f, sr)
                    val env = if (t < 0.01f) 1f else exp(-(t - 0.01f) / d)
                    out = svf.bp * env * 0.9f
                    if (t > d * 8f + 0.01f) on = false
                }
                KitStyles.SHAKER, KitStyles.TAMB -> {
                    val att = (t / 0.008f).coerceAtMost(1f)
                    svf.process(noise.next(), s.f2 * (0.6f + tone * 0.5f), 0.3f, sr)
                    out = svf.hp * att * exp(-t / d) * 0.8f
                    if (s.kind == KitStyles.TAMB) out += metal6(2600f * tune, dt) * exp(-t / (d * 0.8f)) * 0.35f
                    if (t > d * 8f) on = false
                }
                KitStyles.ZAP -> {
                    // laser : sinus qui plonge très vite
                    val f = s.f2 * tune + s.f * tune * exp(-t * 35f)
                    ph += TAU * f * dt; if (ph > TAU) ph -= TAU
                    out = sin(ph) * exp(-t / d) * (0.9f + tone * 0.5f)
                    if (t > d * 8f) on = false
                }
                KitStyles.METAL -> {
                    // choc métallique FM : rapport inharmonique, indice qui retombe
                    ph2 += TAU * s.f * s.f2 * tune * dt; if (ph2 > TAU) ph2 -= TAU
                    val idx = (2f + tone * 6f) * exp(-t * 6f / decay)
                    ph += TAU * s.f * tune * dt; if (ph > TAU) ph -= TAU
                    out = sin(ph + sin(ph2) * idx) * exp(-t / d) * 0.8f
                    if (t > d * 8f) on = false
                }
                else -> { // BLAST : bruit saturé, coup sec industriel
                    svf.process(noise.next(), s.f * tune * (0.5f + tone), 0.35f, sr)
                    svf2.process(svf.lp, 120f, 0f, sr)
                    out = (svf.lp - svf2.lp) * exp(-t / d) * 2f
                    if (t > d * 8f) on = false
                }
            }
            if (s.drive > 0f) out = softClip(out * (1f + s.drive * 6f)) * (1f - s.drive * 0.3f)
            return out * vel
        }
    }

    private val hits = Array(8) { Hit(specs[it]) }

    override fun noteOn(n: Int, vel: Int) {
        val k = Math.floorMod(n - 36, 8)
        val ch = specs[k].choke
        if (ch >= 0) hits[ch].on = false
        hits[k].trigger(vel / 127f)
    }
    override fun noteOff(n: Int) {}
    override fun allOff() { hits.forEach { it.on = false } }

    override fun render(out: FloatArray, count: Int) {
        val dust = style == KitStyles.DUST
        for (i in 0 until count) {
            var s = 0f
            for (h in hits) if (h.on) s += h.next()
            s *= 0.55f
            if (dust) {
                // ~ 12 kHz, 12 bits, puis passe-bas doux : le grain des vieux samplers
                holdCount += 1f
                if (holdCount >= sr / 12000f) { holdCount -= sr / 12000f; hold = Math.round(s * 2048f) / 2048f }
                dustLp += (hold - dustLp) * 0.45f
                s = dustLp
            }
            out[i] += s
        }
    }

    companion object {
        private val RATIOS = floatArrayOf(1f, 1.48f, 1.65f, 1.93f, 2.5f, 2.66f)
    }
}

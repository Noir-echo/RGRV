package fr.rgrv.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.min

/**
 * Une rangée du rack : identité (instrument + canal), 4 macros, 4 effets, volume, TEST et MUTE.
 * Tout se règle directement en glissant le doigt sur un bouton (haut / droite = plus) ;
 * double-toucher un bouton le remet à sa valeur de départ.
 */
@SuppressLint("ViewConstructor")
class RackView(ctx: Context, val rack: Rack) : View(ctx) {

    var picked = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var onIdentity: (() -> Unit)? = null
    var onTouchRack: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val arc = RectF()

    private val fxLabels = arrayOf("DRIVE", "CRUSH", "DELAY", "REVERB", "LEVEL")

    // géométrie (calculée au dessin)
    private var idR = 0f
    private var knobL = 0f
    private var slotW = 0f
    private var btnL = 0f

    // geste en cours
    private var dragKnob = -1
    private var zone = 0           // 1 identité, 2 bouton rotatif, 3 TEST, 4 MUTE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastTapKnob = -1
    private var lastTapTime = 0L
    private var testNote = -1

    private fun get(k: Int): Float = when (k) {
        in 0..3 -> rack.macro[k]
        4 -> rack.drive; 5 -> rack.crush; 6 -> rack.delay; 7 -> rack.reverb
        else -> rack.level
    }

    private fun set(k: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        when (k) {
            in 0..3 -> rack.macro[k] = c
            4 -> rack.drive = c; 5 -> rack.crush = c; 6 -> rack.delay = c; 7 -> rack.reverb = c
            else -> rack.level = c
        }
    }

    private fun defaultOf(k: Int): Float = when (k) {
        in 0..3 -> Instruments.defaults(rack.type)[k]
        8 -> 0.8f
        else -> 0f
    }

    /** Index du bouton rotatif sous x (0–8), -1 sinon. Un petit vide sépare macros et effets. */
    private fun knobAt(x: Float): Int {
        if (x < knobL || x >= btnL) return -1
        var rel = x - knobL
        if (rel < slotW * 4) return (rel / slotW).toInt().coerceIn(0, 3)
        rel -= slotW * 4 + dp(10)
        if (rel < 0) return 3
        return (4 + (rel / slotW).toInt()).coerceIn(4, 8)
    }

    private fun knobCx(k: Int): Float =
        if (k < 4) knobL + slotW * (k + 0.5f) else knobL + slotW * 4 + dp(10) + slotW * (k - 4 + 0.5f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val empty = rack.type == Instruments.NONE
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                onTouchRack?.invoke()
                zone = when {
                    e.x < idR -> 1
                    e.x >= btnL -> if (e.y < height / 2f) 3 else 4
                    else -> 2
                }
                if (zone == 2) {
                    dragKnob = if (empty) -1 else knobAt(e.x)
                    if (dragKnob >= 0) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        val now = System.currentTimeMillis()
                        if (dragKnob == lastTapKnob && now - lastTapTime < 300) {
                            set(dragKnob, defaultOf(dragKnob)); onChanged?.invoke(); lastTapKnob = -1
                        } else { lastTapKnob = dragKnob; lastTapTime = now }
                    }
                }
                if (zone == 3 && !empty) {
                    testNote = if (Instruments.isDrum(rack.type)) 36 else 48
                    AudioEngine.core.midi(0x90 or (rack.channel - 1), testNote, 100)
                }
                isPressed = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (zone == 2 && dragKnob >= 0) {
                    val d = ((e.x - lastX) - (e.y - lastY)) / dp(170)
                    set(dragKnob, get(dragKnob) + d)
                    lastX = e.x; lastY = e.y
                    if (abs(e.x - downX) + abs(e.y - downY) > dp(6)) lastTapKnob = -1
                    onChanged?.invoke()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tap = e.actionMasked == MotionEvent.ACTION_UP && abs(e.x - downX) + abs(e.y - downY) < dp(12)
                if (zone == 1 && tap) onIdentity?.invoke()
                if (zone == 4 && tap && !empty) { rack.mute = !rack.mute; onChanged?.invoke() }
                if (testNote >= 0) {
                    AudioEngine.core.midi(0x80 or (rack.channel - 1), testNote, 0)
                    testNote = -1
                }
                dragKnob = -1; zone = 0
                isPressed = false
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val empty = rack.type == Instruments.NONE
        val sw = dp(Tdr.strokeDp)
        idR = (w * 0.2f).coerceIn(dp(120), dp(180))
        btnL = w - dp(62)
        knobL = idR + dp(6)
        slotW = (btnL - dp(6) - knobL - dp(10)) / 9f

        // ---- bloc identité
        val top = dp(4); val bot = h - dp(4)
        Tdr.cutPath(path, sw, top, idR - dp(2), bot, dp(10))
        p.style = Paint.Style.FILL
        p.color = if (picked) Tdr.ACCENT else Tdr.PANEL
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw
        p.color = Tdr.WHITE
        canvas.drawPath(path, p)
        val ink = Tdr.WHITE
        p.style = Paint.Style.FILL
        p.typeface = Tdr.mono; p.textAlign = Paint.Align.LEFT
        p.textSize = dp(8.5f)
        p.color = if (picked) ink else Tdr.DIM
        canvas.drawText("R${rack.index + 1}  ▾", dp(12), top + dp(14), p)
        p.typeface = Tdr.logo
        p.textSize = min(h * 0.34f, dp(26))
        p.color = ink
        val name = if (empty) tr("VIDE", "EMPTY") else Instruments.names[rack.type]
        canvas.drawText(name, dp(12), h * 0.62f, p)
        p.typeface = Tdr.mono
        p.textSize = dp(8.5f)
        p.color = if (picked) ink else Tdr.DIM
        val chName = Settings.channelNames[rack.channel - 1].ifBlank { "" }
        val chText = ("CH %02d".format(rack.channel) + if (chName.isNotEmpty()) " · $chName" else "").take(22)
        canvas.drawText(chText, dp(12), bot - dp(8), p)
        // vumètre
        val mx = idR - dp(12)
        val mh = (bot - top - dp(16))
        p.color = Tdr.GRID
        canvas.drawRect(mx, top + dp(8), mx + dp(4), top + dp(8) + mh, p)
        val lv = (rack.peak * 1.2f).coerceIn(0f, 1f)
        p.color = if (picked) ink else Tdr.ACCENT
        if (lv > 0.01f) canvas.drawRect(mx, top + dp(8) + mh * (1 - lv), mx + dp(4), top + dp(8) + mh, p)

        // ---- boutons rotatifs
        val labels = Instruments.macroLabels(rack.type)
        val r = min(slotW * 0.42f, (h - dp(26)) / 2f)
        val cy = dp(6) + r + dp(2)
        for (k in 0..8) {
            val cx = knobCx(k)
            val v = get(k)
            val ar = r - dp(1)
            arc.set(cx - ar, cy - ar, cx + ar, cy + ar)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.BUTT
            p.strokeWidth = dp(1.5f)
            p.color = Tdr.GRID
            canvas.drawArc(arc, 135f, 270f, false, p)
            val active = dragKnob == k
            p.strokeWidth = maxOf(dp(3), r * 0.13f)
            p.color = if (empty) Tdr.GRID else if (active || (k < 4 && picked)) Tdr.ACCENT else Tdr.WHITE
            if (v > 0.003f) canvas.drawArc(arc, 135f, 270f * v, false, p)
            val dr = ar * 0.7f
            p.style = Paint.Style.FILL
            p.color = if (active) Tdr.ACCENT else Tdr.BG
            canvas.drawCircle(cx, cy, dr, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f)
            p.color = if (empty) Tdr.GRID else Tdr.WHITE
            canvas.drawCircle(cx, cy, dr, p)
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.typeface = Tdr.font
            p.textSize = dr * 0.62f
            p.color = if (empty) Tdr.GRID else Tdr.WHITE
            canvas.drawText("%03d".format((v * 127).toInt()), cx, cy - (p.descent() + p.ascent()) / 2, p)
            p.typeface = Tdr.mono
            p.textSize = dp(8f)
            p.color = if (empty) Tdr.GRID else if (k < 4) Tdr.WHITE else Tdr.DIM
            val lab = if (k < 4) labels[k] else fxLabels[k - 4]
            canvas.drawText(lab, cx, h - dp(7), p)
        }
        // trait séparateur macros | effets
        p.color = Tdr.GRID
        val sx = knobL + slotW * 4 + dp(5)
        canvas.drawRect(sx - dp(0.75f), dp(10), sx + dp(0.75f), h - dp(10), p)

        // ---- TEST / MUTE
        fun btn(t: Float, b: Float, label: String, lit: Boolean, hazard: Boolean) {
            Tdr.cutPath(path, btnL, t, w - dp(4), b, dp(7))
            p.style = Paint.Style.FILL
            p.color = if (lit) Tdr.ACCENT else Tdr.PANEL
            canvas.drawPath(path, p)
            if (hazard && lit) {
                // rayures : piste coupée
                canvas.save(); canvas.clipPath(path)
                p.color = Tdr.BG
                p.strokeWidth = dp(4); p.style = Paint.Style.STROKE
                var x = btnL - (b - t)
                while (x < w) { canvas.drawLine(x, b, x + (b - t), t, p); x += dp(11) }
                canvas.restore()
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = sw
            p.color = if (empty) Tdr.GRID else Tdr.WHITE
            canvas.drawPath(path, p)
            p.style = Paint.Style.FILL
            p.typeface = Tdr.font; p.textAlign = Paint.Align.CENTER
            p.textSize = dp(10)
            p.color = if (empty) Tdr.GRID else Tdr.WHITE
            val tx = (btnL + w - dp(4)) / 2
            val ty = (t + b) / 2 - (p.descent() + p.ascent()) / 2
            if (hazard && lit) {
                val tw = p.measureText(label) + dp(6)
                p.color = Tdr.ACCENT
                canvas.drawRect(tx - tw / 2, ty + p.ascent() - dp(1), tx + tw / 2, ty + p.descent() + dp(1), p)
                p.color = Tdr.WHITE
            }
            canvas.drawText(label, tx, ty, p)
        }
        btn(top, h / 2 - dp(2), "TEST", testNote >= 0, false)
        btn(h / 2 + dp(2), bot, "MUTE", rack.mute, true)
    }
}

/** Petit clavier une octave (multi-touch) pour jouer le rack sélectionné. */
@SuppressLint("ViewConstructor")
class MiniKeys(ctx: Context) : View(ctx) {
    var base = 48
    var onNote: ((note: Int, on: Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val held = HashMap<Int, Int>()        // pointeur → note
    private val whites = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
    private val blacks = intArrayOf(1, 3, -1, 6, 8, 10, -1)

    private fun noteAt(x: Float, y: Float): Int {
        val ww = width / whites.size.toFloat()
        if (y < height * 0.58f) {
            for (i in blacks.indices) {
                if (blacks[i] < 0) continue
                val cx = (i + 1) * ww
                if (abs(x - cx) < ww * 0.32f) return base + blacks[i]
            }
        }
        val i = (x / ww).toInt().coerceIn(0, whites.size - 1)
        return base + whites[i]
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val n = noteAt(e.getX(i), e.getY(i))
                held[e.getPointerId(i)] = n
                onNote?.invoke(n, true)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val n = noteAt(e.getX(i), e.getY(i))
                    val old = held[id] ?: continue
                    if (n != old) { onNote?.invoke(old, false); onNote?.invoke(n, true); held[id] = n }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                held.remove(id)?.let { onNote?.invoke(it, false) }
            }
            MotionEvent.ACTION_CANCEL -> { held.values.forEach { onNote?.invoke(it, false) }; held.clear() }
        }
        invalidate()
        return true
    }

    fun releaseAll() { held.values.forEach { onNote?.invoke(it, false) }; held.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val ww = w / whites.size
        val down = held.values.toSet()
        val sw = dp(Tdr.strokeDp)
        for (i in whites.indices) {
            val n = base + whites[i]
            p.style = Paint.Style.FILL
            p.color = if (n in down) Tdr.ACCENT else Tdr.PANEL
            canvas.drawRect(i * ww + sw, sw, (i + 1) * ww - sw, h - sw, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = Tdr.WHITE
            canvas.drawRect(i * ww + sw, sw, (i + 1) * ww - sw, h - sw, p)
            if (whites[i] == 0) {
                p.style = Paint.Style.FILL; p.typeface = Tdr.mono; p.textSize = dp(8); p.textAlign = Paint.Align.LEFT
                p.color = Tdr.DIM
                canvas.drawText(Tdr.noteName(n), i * ww + dp(4), h - dp(5), p)
            }
        }
        for (i in blacks.indices) {
            if (blacks[i] < 0) continue
            val n = base + blacks[i]
            val cx = (i + 1) * ww
            p.style = Paint.Style.FILL
            p.color = if (n in down) Tdr.ACCENT else Tdr.WHITE
            canvas.drawRect(cx - ww * 0.3f, sw, cx + ww * 0.3f, h * 0.58f, p)
        }
    }
}

/** Page 04 : les synthés internes, en rack. */
class SynthPage(private val act: Activity, private val seq: Sequencer) {

    var onLogo: (() -> Unit)? = null
    var onLink: (() -> Unit)? = null
    var onSave: (() -> Unit)? = null
    /** PANIC : coupe toutes les notes, dans l'appli et sur l'appareil externe. */
    var onPanic: (() -> Unit)? = null

    lateinit var header: HeaderBar
        private set
    private val rows = ArrayList<RackView>()
    private lateinit var keys: MiniKeys
    private lateinit var btnPlay: TdrButton
    private var sel = 1
    private val ui = Handler(Looper.getMainLooper())
    private var visible = false

    val view: View = build()

    private val core get() = AudioEngine.core

    private fun build(): View {
        val c: Context = act
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Tdr.BG)

        header = HeaderBar(c, "04", "SYNTH")
        header.onLogo = { onLogo?.invoke() }
        header.onLink = { onLink?.invoke() }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(HeaderBar.HEIGHT_DP)))

        val scroll = ScrollView(c)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(c.dpi(6), c.dpi(2), c.dpi(8), c.dpi(4))
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        for (r in core.racks) {
            val v = RackView(c, r)
            v.picked = r.index == sel
            v.onTouchRack = { select(r.index) }
            v.onIdentity = { pickInstrument(r) }
            v.onChanged = { onSave?.invoke() }
            rows.add(v)
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(78)))
        }
        val hint = Tdr.label(c, tr("Glisse sur un bouton pour le tourner, double-touche pour le remettre à zéro. Touche le nom pour changer d'instrument ou de canal MIDI. Le séquenceur, les pads et le contrôleur jouent les racks par leur canal, exactement comme une machine externe.",
            "Drag a knob to turn it, double-tap to reset it. Tap the name to change instrument or MIDI channel. The sequencer, pads and controller play the racks through their channel, exactly like an external machine."), 9f, Tdr.DIM)
        hint.setPadding(c.dpi(4), c.dpi(6), 0, c.dpi(4))
        col.addView(hint)

        // ---- barre du bas : clavier du rack sélectionné + transport
        val bar = LinearLayout(c)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(c.dpi(6), c.dpi(4), c.dpi(8), c.dpi(6))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(56)))
        val oDown = TdrButton(c, "OCT −", Tdr.WHITE).onClick { octave(-12) }
        val oUp = TdrButton(c, "OCT +", Tdr.WHITE).onClick { octave(12) }
        keys = MiniKeys(c)
        keys.onNote = { n, on ->
            val r = core.racks[sel]
            if (r.type != Instruments.NONE) {
                if (on) core.midi(0x90 or (r.channel - 1), n, 105) else core.midi(0x80 or (r.channel - 1), n, 0)
            }
        }
        fun lp(wt: Float, right: Int = 4) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, wt).also { it.rightMargin = c.dpi(right) }
        bar.addView(oDown, lp(0.8f))
        bar.addView(keys, lp(4.2f))
        bar.addView(oUp, lp(0.8f, 10))
        btnPlay = TdrButton(c, "▶ PLAY", Tdr.ACCENT).onClick { seq.toggle(); refreshTransport() }
        bar.addView(btnPlay, lp(1.1f))
        val panic = TdrButton(c, "PANIC", Tdr.RED)
        panic.hazard = true
        panic.onClick { keys.releaseAll(); core.panic(); onPanic?.invoke() }
        bar.addView(panic, lp(1f, 0))
        refreshOct()
        refreshTransport()
        return root
    }

    private fun select(i: Int) {
        if (sel == i) return
        keys.releaseAll()
        sel = i
        rows.forEachIndexed { k, v -> v.picked = k == i }
    }

    private fun octave(d: Int) {
        keys.releaseAll()
        keys.base = (keys.base + d).coerceIn(12, 96)
        refreshOct()
    }

    private fun refreshOct() { keys.invalidate() }

    fun refreshTransport() {
        btnPlay.text = if (seq.running) "■ STOP" else "▶ PLAY"
        header.running = seq.running
    }

    /** Rafraîchit vumètres et boutons (les CC du contrôleur ou du séquenceur bougent aussi les racks). */
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            rows.forEach { it.invalidate() }
            ui.postDelayed(this, 60)
        }
    }

    fun onShow() { visible = true; ui.removeCallbacks(tick); ui.post(tick) }
    fun onHide() { visible = false; ui.removeCallbacks(tick); if (::keys.isInitialized) keys.releaseAll() }

    private fun pickInstrument(r: Rack) {
        val c: Context = act
        val d = TdrDialog(c, "RACK ${r.index + 1}", Tdr.ACCENT)
        val desc = d.note(Instruments.describe(r.type), Tdr.WHITE)
        var chosen = r.type
        // deux familles : batteries puis synthés, + « vide »
        val all = ArrayList<Pair<Int, TdrButton>>()
        fun group(title: String, types: IntArray, cols: Int) {
            d.section(title)
            types.toList().chunked(cols).forEach { line ->
                val row = LinearLayout(c)
                row.orientation = LinearLayout.HORIZONTAL
                for (t in line) {
                    val b = TdrButton(c, if (t == Instruments.NONE) tr("VIDE", "EMPTY") else Instruments.names[t], Tdr.ACCENT)
                    b.active = t == chosen
                    b.onClick {
                        chosen = t
                        all.forEach { (k, bb) -> bb.active = k == t }
                        desc.text = Instruments.describe(t)
                    }
                    all.add(t to b)
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    lp.rightMargin = c.dpi(4)
                    row.addView(b, lp)
                }
                repeat(cols - line.size) {
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    lp.rightMargin = c.dpi(4)
                    row.addView(View(c), lp)
                }
                d.row(row, 36)
            }
        }
        group(tr("BATTERIES", "DRUM KITS"), Instruments.drums, 6)
        group(tr("SYNTHÉS", "SYNTHS"), Instruments.synths + Instruments.NONE, 5)
        // la description reste sous les choix
        d.body.removeView(desc); d.body.addView(desc)
        d.section(tr("CANAL MIDI", "MIDI CHANNEL"))
        val chHint = Tdr.label(c, Settings.channelLabel(r.channel), 10f, Tdr.DIM)
        // un rack qu'on crée prend le premier canal libre : 1, puis 2, 3…
        val startCh = if (r.type == Instruments.NONE) core.freeChannel(r) else r.channel
        chHint.text = Settings.channelLabel(startCh)
        val ch = Stepper(c, tr("CANAL", "CHANNEL"), 1, 16, startCh, editable = true,
            onChange = { chHint.text = Settings.channelLabel(it) })
        d.row(ch)
        d.body.addView(chHint)
        d.note(tr("Tout ce qui est envoyé sur ce canal (séquenceur, pads, contrôleur, Destroy Mode) joue ce rack et ne part plus vers l'appareil externe.",
            "Everything sent on this channel (sequencer, pads, controller, Destroy Mode) plays this rack and is no longer sent to the external device."))
        d.action(tr("ANNULER", "CANCEL"), Tdr.DIM) { d.dismiss() }
        d.action(tr("VALIDER", "OK"), primary = true) {
            if (chosen != r.type) r.inst?.allOff()
            r.channel = ch.value
            if (chosen != r.type) { r.choose(chosen); r.mute = false }
            rows[r.index].invalidate()
            onSave?.invoke()
            d.dismiss()
        }
        d.show()
    }
}

package com.niraj.drivetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

object C {
    val BG = 0xFF0B1220.toInt()
    val CARD = 0xFF131C2E.toInt()
    val CARD2 = 0xFF1B2741.toInt()
    val LINE = 0xFF26334F.toInt()
    val TEXT = 0xFFE6EDF7.toInt()
    val MUTED = 0xFF8A9BB5.toInt()
    val ACCENT = 0xFF22D3EE.toInt()
    val GREEN = 0xFF22C55E.toInt()
    val LIME = 0xFF84CC16.toInt()
    val YELLOW = 0xFFEAB308.toInt()
    val ORANGE = 0xFFF97316.toInt()
    val RED = 0xFFEF4444.toInt()
    val BLUE = 0xFF3B82F6.toInt()
    val NONE = 0xFF64748B.toInt()

    fun rsrp(v: Int?): Int = when {
        v == null -> NONE
        v >= -80 -> GREEN
        v >= -90 -> LIME
        v >= -100 -> YELLOW
        v >= -110 -> ORANGE
        else -> RED
    }

    fun rsrq(v: Int?): Int = when {
        v == null -> NONE
        v >= -10 -> GREEN
        v >= -15 -> YELLOW
        else -> RED
    }

    fun sinr(v: Int?): Int = when {
        v == null -> NONE
        v >= 20 -> GREEN
        v >= 13 -> LIME
        v >= 0 -> YELLOW
        else -> RED
    }

    fun rat(r: String?): Int = when (r) {
        "NR" -> 0xFFA855F7.toInt()
        "LTE" -> BLUE
        "WCDMA" -> 0xFF14B8A6.toInt()
        "GSM" -> ORANGE
        else -> NONE
    }
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float, strokeW: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, strokeColor)
}

fun Context.label(s: String = "", sp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false, mono: Boolean = false) =
    TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(C.CARD, dp(14).toFloat())
    setPadding(dp(14), dp(12), dp(14), dp(12))
}

fun Context.pill(text: String, bg: Int, fg: Int = 0xFFFFFFFF.toInt(), sp: Float = 12f) = label(text, sp, fg, true).apply {
    background = rounded(bg, dp(20).toFloat())
    setPadding(dp(10), dp(4), dp(10), dp(4))
    gravity = Gravity.CENTER
}

fun Context.button(text: String, bg: Int, onClick: () -> Unit) = label(text, 13f, 0xFFFFFFFF.toInt(), true).apply {
    background = rounded(bg, dp(10).toFloat())
    gravity = Gravity.CENTER
    setPadding(dp(14), dp(9), dp(14), dp(9))
    isClickable = true
    setOnClickListener { onClick() }
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun View.withMargins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    val p = layoutParams as? ViewGroup.MarginLayoutParams ?: LinearLayout.LayoutParams(MATCH, WRAP)
    p.setMargins(l, t, r, b)
    layoutParams = p
    return this
}

fun fmt(v: Int?): String = v?.toString() ?: "—"
fun fmt(v: Long?): String = v?.toString() ?: "—"

/** Simple scrolling line chart with coloured quality bands, like Net Monitor's graphs. */
class ChartView(
    ctx: Context,
    private val title: String,
    private val unit: String,
    private val minV: Float,
    private val maxV: Float,
    private val step: Float,
    private val colorOf: (Int?) -> Int
) : View(ctx) {

    private var values: IntArray = IntArray(0)
    private var marks: BooleanArray = BooleanArray(0)
    private var missing: BooleanArray = BooleanArray(0)
    var subtitle: String = ""

    private val d = ctx.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.CARD }
    private val gridPaint = Paint().apply { color = C.LINE; strokeWidth = 1f * d }
    private val bandPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.MUTED; textSize = 10f * d }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.TEXT; textSize = 13f * d; typeface = Typeface.DEFAULT_BOLD
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f * d; typeface = Typeface.DEFAULT_BOLD }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val markPaint = Paint().apply {
        color = 0x99FFFFFF.toInt(); strokeWidth = 1f * d
        pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
    }
    private val rect = RectF()

    fun setData(v: IntArray, miss: BooleanArray, m: BooleanArray) {
        values = v; missing = miss; marks = m
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (190 * d).toInt())
    }

    private fun yOf(v: Float, top: Float, bottom: Float): Float {
        val c = v.coerceIn(minV, maxV)
        return bottom - (c - minV) / (maxV - minV) * (bottom - top)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 14 * d, 14 * d, bgPaint)

        val left = 40 * d
        val right = w - 10 * d
        val top = 36 * d
        val bottom = h - 12 * d

        canvas.drawText(title, 14 * d, 22 * d, titlePaint)
        if (subtitle.isNotEmpty()) {
            canvas.drawText(subtitle, 14 * d + titlePaint.measureText(title) + 8 * d, 22 * d, textPaint)
        }

        // quality bands
        var v = minV
        while (v < maxV) {
            val next = (v + step).coerceAtMost(maxV)
            val mid = ((v + next) / 2).toInt()
            bandPaint.color = (colorOf(mid) and 0x00FFFFFF) or 0x1F000000
            canvas.drawRect(left, yOf(next, top, bottom), right, yOf(v, top, bottom), bandPaint)
            v = next
        }
        // grid + labels
        v = minV
        while (v <= maxV + 0.01f) {
            val y = yOf(v, top, bottom)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(v.toInt().toString(), 8 * d, y + 3.5f * d, textPaint)
            v += step
        }

        val n = values.size
        if (n == 0) {
            canvas.drawText("No data yet", left + 10 * d, (top + bottom) / 2, textPaint)
            return
        }
        val cap = LiveMonitor.HISTORY
        val dx = (right - left) / (cap - 1)
        val x0 = right - (n - 1) * dx

        for (i in 0 until n) {
            if (i < marks.size && marks[i]) {
                val x = x0 + i * dx
                canvas.drawLine(x, top, x, bottom, markPaint)
            }
        }
        for (i in 1 until n) {
            if (missing[i] || missing[i - 1]) continue
            linePaint.color = colorOf(values[i])
            canvas.drawLine(
                x0 + (i - 1) * dx, yOf(values[i - 1].toFloat(), top, bottom),
                x0 + i * dx, yOf(values[i].toFloat(), top, bottom), linePaint
            )
        }
        val last = if (missing[n - 1]) null else values[n - 1]
        val txt = (last?.toString() ?: "—") + " " + unit
        valuePaint.color = colorOf(last)
        canvas.drawText(txt, w - 14 * d - valuePaint.measureText(txt), 24 * d, valuePaint)
    }
}

/** Draws the drive route as dots coloured by RSRP, plus the current position. */
class TrackOverlay(private val density: Float) : Overlay() {
    var points: List<TrackPoint> = emptyList()
    var current: GeoPoint? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 2.5f * density
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553B82F6 }
    private val pt = Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val r = 4.5f * density
        for (tp in points) {
            proj.toPixels(tp.point, pt)
            p.color = C.rsrp(tp.value)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, p)
        }
        current?.let {
            proj.toPixels(it, pt)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 16 * density, halo)
            p.color = C.BLUE
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7 * density, p)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7 * density, ring)
        }
    }
}

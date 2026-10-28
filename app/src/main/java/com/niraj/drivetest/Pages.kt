package com.niraj.drivetest

import android.app.Activity
import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File
import java.util.Date
import java.util.Locale

// ───────────────────────────── CELL ─────────────────────────────

class CellPage(private val ctx: Context) {

    val view: View
    private val ratPill: TextView
    private val opText: TextView
    private val netText: TextView
    private val tileLabel = arrayOfNulls<TextView>(4)
    private val tileValue = arrayOfNulls<TextView>(4)
    private val tileUnit = arrayOfNulls<TextView>(4)
    private val kv = HashMap<String, Pair<TextView, TextView>>()
    private val nsaCard: LinearLayout
    private val nsaText: TextView
    private val nbTitle: TextView
    private val nbText: TextView
    private var labelsFor = ""

    init {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(16))
        }

        // operator header
        val head = ctx.card()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ratPill = ctx.pill("—", C.NONE, sp = 15f)
        row.addView(ratPill, lp(WRAP, WRAP))
        val opCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), 0, 0, 0) }
        opText = ctx.label("Searching…", 17f, C.TEXT, true)
        netText = ctx.label("", 12f, C.MUTED)
        opCol.addView(opText); opCol.addView(netText)
        row.addView(opCol, lp(0, WRAP, 1f))
        head.addView(row)
        col.addView(head, lp(MATCH, WRAP))

        // signal tiles
        for (r in 0 until 2) {
            val tr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 2) {
                val i = r * 2 + c
                val t = ctx.card().apply { setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10)) }
                tileLabel[i] = ctx.label("", 12f, C.MUTED, true).also { t.addView(it) }
                val vr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
                tileValue[i] = ctx.label("—", 30f, C.TEXT, true).also { vr.addView(it) }
                tileUnit[i] = ctx.label("", 12f, C.MUTED).also {
                    it.setPadding(ctx.dp(4), 0, 0, ctx.dp(6)); vr.addView(it)
                }
                t.addView(vr)
                tr.addView(t, lp(0, WRAP, 1f).apply {
                    setMargins(if (c == 0) 0 else ctx.dp(5), 0, if (c == 0) ctx.dp(5) else 0, 0)
                })
            }
            col.addView(tr, lp(MATCH, WRAP).apply { topMargin = ctx.dp(10) })
        }

        // identity
        val idCard = ctx.card()
        idCard.addView(ctx.label("SERVING CELL", 11f, C.ACCENT, true))
        grid(idCard, listOf("mccmnc", "tac", "cid", "node", "sector", "pci", "arfcn", "band", "bw", "ta", "cqi", "level"))
        col.addView(idCard, lp(MATCH, WRAP).apply { topMargin = ctx.dp(10) })

        // 5G NSA
        nsaCard = ctx.card()
        nsaCard.addView(ctx.label("5G NR (NSA / EN-DC)", 11f, 0xFFA855F7.toInt(), true))
        nsaText = ctx.label("", 15f, C.TEXT, true, mono = true).apply { setPadding(0, ctx.dp(6), 0, 0) }
        nsaCard.addView(nsaText)
        nsaCard.visibility = View.GONE
        col.addView(nsaCard, lp(MATCH, WRAP).apply { topMargin = ctx.dp(10) })

        // neighbours
        val nbCard = ctx.card()
        nbTitle = ctx.label("NEIGHBOUR CELLS", 11f, C.ACCENT, true)
        nbCard.addView(nbTitle)
        nbText = ctx.label("", 12.5f, C.TEXT, mono = true).apply { setPadding(0, ctx.dp(6), 0, 0) }
        nbCard.addView(nbText)
        col.addView(nbCard, lp(MATCH, WRAP).apply { topMargin = ctx.dp(10) })

        // GPS
        val gpsCard = ctx.card()
        gpsCard.addView(ctx.label("GPS", 11f, C.ACCENT, true))
        grid(gpsCard, listOf("lat", "lon", "acc", "spd", "alt", "fix"))
        col.addView(gpsCard, lp(MATCH, WRAP).apply { topMargin = ctx.dp(10) })

        val labels = mapOf("lat" to "Latitude", "lon" to "Longitude", "acc" to "Accuracy",
            "spd" to "Speed", "alt" to "Altitude", "fix" to "Fix")
        labels.forEach { (k, v) -> kv[k]?.first?.text = v }

        view = ScrollView(ctx).apply { addView(col); isFillViewport = true }
        setLabels("LTE")
    }

    private fun grid(parent: LinearLayout, keys: List<String>) {
        keys.chunked(3).forEach { chunk ->
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dp(8), 0, 0) }
            chunk.forEach { k ->
                val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val kt = ctx.label(k, 11f, C.MUTED)
                val vt = ctx.label("—", 15f, C.TEXT, true, mono = true)
                cell.addView(kt); cell.addView(vt)
                kv[k] = Pair(kt, vt)
                r.addView(cell, lp(0, WRAP, 1f))
            }
            repeat(3 - chunk.size) { r.addView(View(ctx), lp(0, 1, 1f)) }
            parent.addView(r)
        }
    }

    private fun setLabels(rat: String) {
        if (rat == labelsFor) return
        labelsFor = rat
        val m = when (rat) {
            "NR" -> listOf("MCC-MNC", "TAC", "NCI", "gNB ID", "Sector", "PCI", "NR-ARFCN", "Band", "BW", "TA", "CQI", "Level")
            "WCDMA" -> listOf("MCC-MNC", "LAC", "UTRAN CID", "RNC ID", "Cell ID", "PSC", "UARFCN", "Band", "BW", "TA", "CQI", "Level")
            "GSM" -> listOf("MCC-MNC", "LAC", "CID", "—", "—", "BSIC", "ARFCN", "Band", "BW", "TA", "CQI", "Level")
            else -> listOf("MCC-MNC", "TAC", "ECI", "eNB ID", "Cell ID", "PCI", "EARFCN", "Band", "BW", "TA", "CQI", "Level")
        }
        listOf("mccmnc", "tac", "cid", "node", "sector", "pci", "arfcn", "band", "bw", "ta", "cqi", "level")
            .forEachIndexed { i, k -> kv[k]?.first?.text = m[i] }
        tileLabel[0]?.text = CellReader.primaryLabel(rat); tileUnit[0]?.text = "dBm"
        tileLabel[1]?.text = CellReader.qualityLabel(rat); tileUnit[1]?.text = "dB"
        tileLabel[2]?.text = CellReader.sinrLabel(rat); tileUnit[2]?.text = "dB"
        tileLabel[3]?.text = "RSSI"; tileUnit[3]?.text = "dBm"
    }

    private fun set(k: String, v: String) { kv[k]?.second?.text = v }

    fun update(s: Snapshot) {
        val c = s.serving
        val rat = c?.rat ?: "LTE"
        setLabels(rat)
        ratPill.text = when {
            c == null -> "NO SERVICE"
            s.nsa != null && c.rat == "LTE" -> "4G+5G"
            c.rat == "NR" -> "5G"
            c.rat == "LTE" -> "4G"
            c.rat == "WCDMA" -> "3G"
            else -> "2G"
        }
        ratPill.background = rounded(if (s.nsa != null) C.rat("NR") else C.rat(c?.rat), ctx.dp(20).toFloat())
        opText.text = s.operator.ifEmpty { if (c == null) "No service" else "Unknown operator" }
        netText.text = "${s.simLabel} · ${s.netType}" + (c?.band?.let { " · $it" } ?: "")

        tileValue[0]?.apply { text = fmt(c?.rsrp); setTextColor(C.rsrp(c?.rsrp)) }
        tileValue[1]?.apply { text = fmt(c?.rsrq); setTextColor(C.rsrq(c?.rsrq)) }
        tileValue[2]?.apply { text = fmt(c?.sinr); setTextColor(C.sinr(c?.sinr)) }
        tileValue[3]?.apply { text = fmt(c?.rssi); setTextColor(C.TEXT) }

        set("mccmnc", if (c?.mcc != null) "${c.mcc}-${c.mnc ?: "?"}" else "—")
        set("tac", fmt(c?.tac))
        set("cid", fmt(c?.cid))
        set("node", fmt(c?.nodeId))
        set("sector", fmt(c?.sector))
        set("pci", fmt(c?.pci))
        set("arfcn", fmt(c?.arfcn))
        set("band", c?.band ?: "—")
        set("bw", c?.bwMhz?.let { "$it MHz" } ?: "—")
        set("ta", fmt(c?.ta))
        set("cqi", fmt(c?.cqi))
        set("level", c?.level?.let { "$it/4" } ?: "—")

        val n = s.nsa
        if (n != null) {
            nsaCard.visibility = View.VISIBLE
            nsaText.text = "SS-RSRP ${fmt(n.ssRsrp)}   SS-RSRQ ${fmt(n.ssRsrq)}   SINR ${fmt(n.ssSinr)}"
        } else nsaCard.visibility = View.GONE

        nbTitle.text = "NEIGHBOUR CELLS (${s.neighbors.size})"
        nbText.text = if (s.neighbors.isEmpty()) "None reported" else buildString {
            append("RAT    PCI   ARFCN   SIG   QUAL\n")
            s.neighbors.take(16).forEach {
                append(it.rat.padEnd(6)).append(fmt(it.pci).padStart(4))
                    .append(fmt(it.arfcn).padStart(8))
                    .append(fmt(it.rsrp).padStart(6))
                    .append(fmt(it.rsrq).padStart(7)).append('\n')
            }
        }.trimEnd()

        set("lat", s.lat?.let { String.format(Locale.US, "%.6f", it) } ?: "—")
        set("lon", s.lon?.let { String.format(Locale.US, "%.6f", it) } ?: "—")
        set("acc", s.accuracy?.let { String.format(Locale.US, "%.0f m", it) } ?: "—")
        set("spd", s.speedKmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "—")
        set("alt", s.altitude?.let { String.format(Locale.US, "%.0f m", it) } ?: "—")
        set("fix", when {
            s.fixAgeSec == null -> "No fix"
            s.lat == null -> "Lost"
            (s.fixAgeSec ?: 0L) <= 2L -> "Live"
            else -> "${s.fixAgeSec}s ago"
        })
    }
}

// ───────────────────────────── GRAPHS ─────────────────────────────

class GraphPage(ctx: Context) {
    val view: View
    private val primary = ChartView(ctx, "RSRP", "dBm", -140f, -40f, 20f) { C.rsrp(it) }
    private val quality = ChartView(ctx, "RSRQ", "dB", -25f, 0f, 5f) { C.rsrq(it) }
    private val sinr = ChartView(ctx, "SINR", "dB", -10f, 30f, 10f) { C.sinr(it) }

    init {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(16))
        }
        listOf(primary, quality, sinr).forEach {
            col.addView(it, lp(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) })
        }
        col.addView(ctx.label("Last 5 minutes · dashed line = serving cell change (handover)", 12f, C.MUTED)
            .apply { setPadding(ctx.dp(4), 0, 0, 0) })
        view = ScrollView(ctx).apply { addView(col) }
    }

    fun update() {
        val h = LiveMonitor.history.toList()
        val n = h.size
        val marks = BooleanArray(n)
        for (i in 1 until n) {
            val a = h[i - 1].serving?.cid
            val b = h[i].serving?.cid
            marks[i] = a != null && b != null && a != b
        }
        fun series(f: (Snapshot) -> Int?): Pair<IntArray, BooleanArray> {
            val v = IntArray(n); val m = BooleanArray(n)
            for (i in 0 until n) {
                val x = f(h[i])
                m[i] = x == null
                v[i] = x ?: 0
            }
            return Pair(v, m)
        }
        val rat = h.lastOrNull()?.serving?.rat
        val sub = h.lastOrNull()?.serving?.let { c -> "PCI ${fmt(c.pci)} · ${c.band ?: c.rat}" } ?: ""
        val p = series { it.serving?.rsrp }
        primary.subtitle = CellReader.primaryLabel(rat).let { if (it == "RSRP") sub else "$it · $sub" }
        primary.setData(p.first, p.second, marks)
        val q = series { it.serving?.rsrq }
        quality.setData(q.first, q.second, marks)
        val s = series { it.serving?.sinr ?: it.nsa?.ssSinr }
        sinr.setData(s.first, s.second, marks)
    }
}

// ───────────────────────────── MAP ─────────────────────────────

class MapPage(act: Activity) {

    companion object {
        fun initOsm(ctx: Context) {
            val c = Configuration.getInstance()
            c.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            c.userAgentValue = ctx.packageName
            val base = File(ctx.cacheDir, "osmdroid")
            c.osmdroidBasePath = base
            c.osmdroidTileCache = File(base, "tiles")
        }
    }

    val view: View
    private val map = MapView(act)
    private val overlay = TrackOverlay(act.resources.displayMetrics.density)
    private val info: TextView
    private val followBtn: TextView
    private var follow = true
    private var zoomedToFix = false

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(27.7172, 85.3240))
        map.overlays.add(overlay)
        map.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_MOVE) setFollow(false)
            false
        }

        val frame = FrameLayout(act)
        frame.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))

        info = act.label("Waiting for GPS…", 13f, C.TEXT, true, mono = true).apply {
            background = rounded(0xE6131C2E.toInt(), act.dp(12).toFloat())
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
        }
        frame.addView(info, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
            setMargins(act.dp(10), act.dp(10), act.dp(10), 0)
        })

        followBtn = act.button("◎ FOLLOW", C.BLUE) { setFollow(!follow) }
        frame.addView(followBtn, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, act.dp(10), act.dp(52))
        })

        val legend = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xE6131C2E.toInt(), act.dp(12).toFloat())
            setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("≥-80" to C.GREEN, "-90" to C.LIME, "-100" to C.YELLOW, "-110" to C.ORANGE, "<-110" to C.RED)
            .forEach { (t, col) ->
                legend.addView(act.label("●", 13f, col))
                legend.addView(act.label(" $t  ", 11f, C.TEXT))
            }
        frame.addView(legend, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.BOTTOM).apply {
            setMargins(act.dp(10), 0, 0, act.dp(10))
        })

        val attribution = act.label("© OpenStreetMap contributors", 10f, 0xFF333333.toInt()).apply {
            setBackgroundColor(0xB3FFFFFF.toInt())
            setPadding(act.dp(4), act.dp(1), act.dp(4), act.dp(1))
        }
        frame.addView(attribution, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, act.dp(4), act.dp(4))
        })
        view = frame
    }

    private fun setFollow(f: Boolean) {
        if (follow == f) return
        follow = f
        followBtn.background = rounded(if (f) C.BLUE else C.CARD2, followBtn.context.dp(10).toFloat())
        if (f) LiveMonitor.latest?.let { update(it) }
    }

    fun update(s: Snapshot) {
        overlay.points = LiveMonitor.track
        val here = if (s.lat != null && s.lon != null) GeoPoint(s.lat, s.lon) else null
        overlay.current = here
        if (here != null && follow) {
            if (!zoomedToFix) { map.controller.setZoom(17.0); zoomedToFix = true }
            map.controller.setCenter(here)
        }
        val c = s.serving
        info.text = if (c == null) "No service" + (if (here == null) " · waiting for GPS" else "")
        else buildString {
            append(c.rat).append(' ').append(CellReader.primaryLabel(c.rat)).append(' ').append(fmt(c.rsrp))
            append("  PCI ").append(fmt(c.pci))
            c.band?.let { append("  ").append(it) }
            append('\n')
            append(if (c.rat == "LTE") "eNB " else if (c.rat == "NR") "gNB " else "Node ")
                .append(fmt(c.nodeId)).append('-').append(fmt(c.sector))
            append("  TAC ").append(fmt(c.tac))
            if (LiveMonitor.isRecording) append("  ● ").append(LiveMonitor.track.size).append(" pts")
            if (here == null) append("\nWaiting for GPS fix…")
        }
        map.invalidate()
    }

    fun onResume() = map.onResume()
    fun onPause() = map.onPause()
    fun onDetach() = map.onDetach()
}

// ───────────────────────────── LOGS ─────────────────────────────

class LogsPage(
    private val ctx: Context,
    private val onShare: (File) -> Unit,
    private val onSave: (File) -> Unit,
    private val onDelete: (File) -> Unit
) {
    val view: View
    private val list = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(16))
    }

    init {
        view = ScrollView(ctx).apply { addView(list) }
    }

    private fun rowsOf(f: File): Int? {
        if (f.length() > 20L * 1024 * 1024) return null
        return try {
            var n = 0
            f.inputStream().buffered().use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = s.read(buf)
                    if (r <= 0) break
                    for (i in 0 until r) if (buf[i] == '\n'.code.toByte()) n++
                }
            }
            (n - 1).coerceAtLeast(0)
        } catch (e: Exception) { null }
    }

    fun refresh() {
        list.removeAllViews()
        list.addView(ctx.label("Saved drive tests", 17f, C.TEXT, true).apply { setPadding(ctx.dp(4), ctx.dp(6), 0, 0) })
        list.addView(ctx.label("CSV files open directly in Excel. Share to Gmail, Drive or WhatsApp, or save a copy to your phone.",
            12f, C.MUTED).apply { setPadding(ctx.dp(4), ctx.dp(2), 0, ctx.dp(8)) })
        val files = LogFileProvider.list(ctx)
        if (files.isEmpty()) {
            list.addView(ctx.card().apply {
                addView(ctx.label("No drive tests yet.\nPress START DRIVE TEST below to begin logging.", 14f, C.MUTED))
            })
            return
        }
        val recording = LiveMonitor.recordFile
        for (f in files) {
            val card = ctx.card()
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(ctx.label(f.name, 14f, C.TEXT, true, mono = true), lp(0, WRAP, 1f))
            val isRec = recording != null && recording.absolutePath == f.absolutePath
            if (isRec) top.addView(ctx.pill("● REC", C.RED, sp = 10f))
            card.addView(top)
            val rows = if (isRec) LiveMonitor.recordRows else rowsOf(f)
            val date = DateFormat.format("dd MMM yyyy, HH:mm", Date(f.lastModified()))
            val meta = listOfNotNull(
                rows?.let { "$it samples" },
                rows?.let { if (it > 0) durationText(it) else null },
                Formatter.formatShortFileSize(ctx, f.length()),
                date.toString()
            ).joinToString(" · ")
            card.addView(ctx.label(meta, 12f, C.MUTED).apply { setPadding(0, ctx.dp(3), 0, ctx.dp(10)) })
            if (!isRec) {
                val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                btns.addView(ctx.button("SHARE", C.BLUE) { onShare(f) }, lp(0, WRAP, 1f).apply { rightMargin = ctx.dp(6) })
                btns.addView(ctx.button("SAVE TO PHONE", 0xFF0E7490.toInt()) { onSave(f) }, lp(0, WRAP, 1.4f).apply { rightMargin = ctx.dp(6) })
                btns.addView(ctx.button("DELETE", C.CARD2) { onDelete(f) }, lp(0, WRAP, 1f))
                card.addView(btns)
            }
            list.addView(card, lp(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) })
        }
    }

    private fun durationText(samples: Int): String {
        val h = samples / 3600; val m = (samples % 3600) / 60; val s = samples % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
    }
}

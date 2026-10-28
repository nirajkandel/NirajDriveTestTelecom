package com.niraj.drivetest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import org.osmdroid.util.GeoPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One 1-second measurement. */
data class Snapshot(
    val time: Long,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Float?,
    val speedKmh: Float?,
    val altitude: Double?,
    val bearing: Float?,
    val fixAgeSec: Long?,
    val simLabel: String,
    val operator: String,
    val netType: String,
    val serving: CellRec?,
    val neighbors: List<CellRec>,
    val nsa: NrNsa?
)

class TrackPoint(val point: GeoPoint, val value: Int?, val snap: Snapshot)

/**
 * Samples the radio + GPS once per second while anyone (the screen or the
 * recording service) holds it, keeps a short history for graphs and, while a
 * drive test is running, writes every sample to CSV.
 */
object LiveMonitor {

    interface Listener {
        fun onSample(s: Snapshot)
        fun onStateChanged() {}
    }

    const val HISTORY = 300          // 5 minutes of graph history
    private const val MAX_TRACK = 50000

    private val handler = Handler(Looper.getMainLooper())
    private var users = 0
    private var running = false
    private lateinit var app: Context

    private var tm: TelephonyManager? = null
    private var lm: LocationManager? = null
    private var cachedCells: List<CellInfo>? = null

    var subId: Int = -1; private set
    var simLabel: String = "Default SIM"; private set

    private var lastLocation: Location? = null
    private var lastLocationElapsed = 0L
    private var lastGpsElapsed = 0L

    val history = ArrayDeque<Snapshot>()
    val track = ArrayList<TrackPoint>()
    var latest: Snapshot? = null; private set

    private val listeners = LinkedHashSet<Listener>()

    private var recorder: CsvRecorder? = null
    var recordStart = 0L; private set
    val isRecording get() = recorder != null
    val recordFile: File? get() = recorder?.file
    val recordRows: Int get() = recorder?.rows ?: 0
    var lastFinished: Pair<File, Int>? = null; private set

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun acquire(ctx: Context) {
        app = ctx.applicationContext
        users++
        if (!running) start()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && running) stop()
    }

    private fun start() {
        running = true
        val prefs = app.getSharedPreferences("ndt", Context.MODE_PRIVATE)
        subId = prefs.getInt("subId", -1)
        simLabel = prefs.getString("simLabel", "Default SIM") ?: "Default SIM"
        setupTelephony()
        startLocation()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(tick)
        stopLocation()
    }

    /** Call after permissions change. */
    fun refreshPermissions() {
        if (!running) return
        stopLocation()
        setupTelephony()
        startLocation()
    }

    fun setSubscription(ctx: Context, id: Int, label: String) {
        subId = id
        simLabel = label
        ctx.getSharedPreferences("ndt", Context.MODE_PRIVATE).edit()
            .putInt("subId", id).putString("simLabel", label).apply()
        cachedCells = null
        history.clear()
        setupTelephony()
        notifyState()
    }

    private fun setupTelephony() {
        val base = app.getSystemService(TelephonyManager::class.java)
        tm = try {
            if (subId >= 0 && base != null) base.createForSubscriptionId(subId) else base
        } catch (e: Exception) { base }
    }

    private fun has(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            val isGps = location.provider == LocationManager.GPS_PROVIDER
            if (isGps) lastGpsElapsed = now
            // Prefer GPS; accept network fixes only if GPS has been silent for 10 s.
            if (isGps || now - lastGpsElapsed > 10_000) {
                lastLocation = location
                lastLocationElapsed = now
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val m = app.getSystemService(LocationManager::class.java) ?: return
        lm = m
        try {
            if (m.allProviders.contains(LocationManager.GPS_PROVIDER))
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            if (m.allProviders.contains(LocationManager.NETWORK_PROVIDER))
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, locListener, Looper.getMainLooper())
            if (lastLocation == null) {
                val last = m.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: m.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (last != null) {
                    lastLocation = last
                    lastLocationElapsed = SystemClock.elapsedRealtime() -
                        (System.currentTimeMillis() - last.time).coerceAtLeast(0)
                }
            }
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun stopLocation() {
        try { lm?.removeUpdates(locListener) } catch (e: Exception) {}
    }

    fun isGpsEnabled(ctx: Context): Boolean {
        val m = ctx.getSystemService(LocationManager::class.java) ?: return false
        return try { m.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
    }

    private val tick = object : Runnable {
        override fun run() {
            val started = SystemClock.uptimeMillis()
            try { sample() } catch (e: Exception) { }
            if (running) {
                val next = 1000L - (SystemClock.uptimeMillis() - started)
                handler.postDelayed(this, next.coerceIn(100L, 1000L))
            }
        }
    }

    private fun sample() {
        val t = tm
        val canCells = has(Manifest.permission.ACCESS_FINE_LOCATION)
        var cells: List<CellInfo>? = null
        var sig: SignalStrength? = null
        if (t != null && canCells) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    t.requestCellInfoUpdate(app.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            cachedCells = cellInfo
                        }
                    })
                    cells = cachedCells ?: t.allCellInfo
                } else {
                    cells = t.allCellInfo
                }
            } catch (e: SecurityException) {
            } catch (e: Exception) {
            }
        }
        if (t != null && Build.VERSION.SDK_INT >= 28) {
            sig = try { t.signalStrength } catch (e: Exception) { null }
        }

        val result = CellReader.read(t, cells, sig)
        val netType = try {
            if (t != null && has(Manifest.permission.READ_PHONE_STATE))
                CellReader.networkTypeName(t.dataNetworkType)
            else result.serving?.rat ?: "Unknown"
        } catch (e: Exception) { result.serving?.rat ?: "Unknown" }
        val netLabel = if (result.nsa != null && result.serving?.rat == "LTE") "LTE + 5G NSA" else netType
        val operator = try { t?.networkOperatorName ?: "" } catch (e: Exception) { "" }

        val loc = lastLocation
        val ageSec = if (loc != null) (SystemClock.elapsedRealtime() - lastLocationElapsed) / 1000 else null
        val fresh = loc != null && ageSec != null && ageSec <= 10

        val s = Snapshot(
            time = System.currentTimeMillis(),
            lat = if (fresh) loc!!.latitude else null,
            lon = if (fresh) loc!!.longitude else null,
            accuracy = if (fresh && loc!!.hasAccuracy()) loc.accuracy else null,
            speedKmh = if (fresh && loc!!.hasSpeed()) loc.speed * 3.6f else null,
            altitude = if (fresh && loc!!.hasAltitude()) loc.altitude else null,
            bearing = if (fresh && loc!!.hasBearing()) loc.bearing else null,
            fixAgeSec = ageSec,
            simLabel = simLabel,
            operator = operator,
            netType = netLabel,
            serving = result.serving,
            neighbors = result.neighbors,
            nsa = result.nsa
        )

        latest = s
        history.addLast(s)
        while (history.size > HISTORY) history.removeFirst()

        val rec = recorder
        if (rec != null) {
            rec.write(s)
            if (s.lat != null && s.lon != null) {
                track.add(TrackPoint(GeoPoint(s.lat, s.lon), s.serving?.rsrp, s))
                if (track.size > MAX_TRACK) track.removeAt(0)
            }
        }
        for (l in listeners.toList()) l.onSample(s)
    }

    fun startRecording(ctx: Context): File? {
        if (recorder != null) return recorder?.file
        val name = "NDT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val file = File(LogFileProvider.dir(ctx), name)
        return try {
            recorder = CsvRecorder(file)
            recordStart = System.currentTimeMillis()
            track.clear()
            notifyState()
            file
        } catch (e: Exception) {
            recorder = null
            null
        }
    }

    fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        r.close()
        lastFinished = Pair(r.file, r.rows)
        notifyState()
    }

    private fun notifyState() {
        for (l in listeners.toList()) l.onStateChanged()
    }
}

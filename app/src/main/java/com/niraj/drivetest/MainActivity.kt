package com.niraj.drivetest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity(), LiveMonitor.Listener {

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_SAVE = 2
    }

    private lateinit var cellPage: CellPage
    private lateinit var graphPage: GraphPage
    private lateinit var mapPage: MapPage
    private lateinit var logsPage: LogsPage
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var startBtn: TextView
    private lateinit var simChip: TextView
    private var tab = 0
    private var pendingSave: File? = null
    private var wasRecording = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapPage.initOsm(this)
        setupEdgeToEdge()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(6))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(label("Niraj Drive Test", 20f, C.TEXT, true))
        titleCol.addView(label("Network drive test & cell monitor", 12f, C.MUTED))
        header.addView(titleCol, lp(0, WRAP, 1f))
        simChip = pill("SIM ▾", C.CARD2, C.TEXT, 12f).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { chooseSim() }
        }
        header.addView(simChip)
        root.addView(header)

        // tabs
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        tabs = listOf("CELL", "MAP", "GRAPHS", "LOGS").mapIndexed { i, t ->
            label(t, 13f, C.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { selectTab(i) }
                tabBar.addView(this, lp(0, WRAP, 1f).apply { if (i < 3) rightMargin = dp(6) })
            }
        }
        root.addView(tabBar)

        // pages
        val content = FrameLayout(this)
        cellPage = CellPage(this)
        mapPage = MapPage(this)
        graphPage = GraphPage(this)
        logsPage = LogsPage(this, ::shareFile, ::saveFile, ::confirmDelete)
        pages = listOf(cellPage.view, mapPage.view, graphPage.view, logsPage.view)
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(content, lp(MATCH, 0, 1f))

        // bottom control bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.CARD, dp(16).toFloat(), dp(1), C.LINE)
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        val barText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusText = label("Idle", 15f, C.TEXT, true, mono = true)
        infoText = label("Press start to log a drive test", 11.5f, C.MUTED)
        barText.addView(statusText); barText.addView(infoText)
        bar.addView(barText, lp(0, WRAP, 1f))
        startBtn = button("START", C.GREEN) { toggleRecording() }.apply {
            textSize = 15f
            setPadding(dp(22), dp(13), dp(22), dp(13))
        }
        bar.addView(startBtn)
        root.addView(bar, lp(MATCH, WRAP).apply { setMargins(dp(10), dp(4), dp(10), dp(8)) })

        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        selectTab(0)
        updateSimChip()
        showDisclosureThenAsk()
    }

    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = 0
            window.navigationBarColor = 0
        }
    }

    override fun onStart() {
        super.onStart()
        LiveMonitor.acquire(this)
        LiveMonitor.addListener(this)
        wasRecording = LiveMonitor.isRecording
        LiveMonitor.latest?.let { onSample(it) }
        refreshState()
    }

    override fun onStop() {
        LiveMonitor.removeListener(this)
        LiveMonitor.release()
        super.onStop()
    }

    override fun onResume() { super.onResume(); mapPage.onResume() }
    override fun onPause() { mapPage.onPause(); super.onPause() }
    override fun onDestroy() { mapPage.onDetach(); super.onDestroy() }

    // ───── tabs ─────
    private fun selectTab(i: Int) {
        tab = i
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { idx, t ->
            val sel = idx == i
            t.setTextColor(if (sel) C.BG else C.MUTED)
            t.background = rounded(if (sel) C.ACCENT else C.CARD, dp(10).toFloat())
        }
        val s = LiveMonitor.latest
        when (i) {
            1 -> if (s != null) mapPage.update(s)
            2 -> graphPage.update()
            3 -> logsPage.refresh()
        }
    }

    // ───── monitor callbacks ─────
    override fun onSample(s: Snapshot) {
        cellPage.update(s)
        when (tab) {
            1 -> mapPage.update(s)
            2 -> graphPage.update()
        }
        updateBar()
    }

    override fun onStateChanged() {
        refreshState()
        updateSimChip()
        if (wasRecording && !LiveMonitor.isRecording) {
            LiveMonitor.lastFinished?.let { (f, n) ->
                Toast.makeText(this, "Saved $n samples → ${f.name}", Toast.LENGTH_LONG).show()
            }
        }
        wasRecording = LiveMonitor.isRecording
        if (tab == 3) logsPage.refresh()
    }

    private fun refreshState() {
        val rec = LiveMonitor.isRecording
        startBtn.text = if (rec) "STOP" else "START"
        startBtn.background = rounded(if (rec) C.RED else C.GREEN, dp(12).toFloat())
        if (rec) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateBar()
    }

    private fun updateBar() {
        if (LiveMonitor.isRecording) {
            val sec = (System.currentTimeMillis() - LiveMonitor.recordStart) / 1000
            statusText.text = String.format(java.util.Locale.US, "● REC  %02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
            statusText.setTextColor(C.RED)
            val gps = if (LiveMonitor.latest?.lat == null) " · no GPS" else ""
            infoText.text = "${LiveMonitor.recordRows} samples · ${LiveMonitor.recordFile?.name ?: ""}$gps"
        } else {
            statusText.text = "Idle"
            statusText.setTextColor(C.TEXT)
            infoText.text = "Press START to begin a drive test"
        }
    }

    // ───── recording ─────
    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun toggleRecording() {
        if (LiveMonitor.isRecording) {
            DriveTestService.stop(this)
            return
        }
        if (!hasLocation()) {
            Toast.makeText(this, "Location permission is needed for cell info and GPS", Toast.LENGTH_LONG).show()
            askPermissions()
            return
        }
        if (!LiveMonitor.isGpsEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("GPS is off")
                .setMessage("Turn on Location so the drive test can record your route.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Start anyway") { _, _ -> DriveTestService.start(this) }
                .show()
            return
        }
        DriveTestService.start(this)
    }

    // ───── permissions ─────
    private fun showDisclosureThenAsk() {
        val prefs = getSharedPreferences("ndt", MODE_PRIVATE)
        if (prefs.getBoolean("disclosed", false)) { askPermissions(); return }
        AlertDialog.Builder(this)
            .setTitle("Location & phone access")
            .setMessage(
                "Niraj Drive Test uses your precise location and phone state to read the serving and " +
                    "neighbour cell information (Cell ID, PCI, RSRP, RSRQ, SINR…) and to plot your route on the map.\n\n" +
                    "While a drive test is running, location is collected in the background (with a visible " +
                    "notification) even when the screen is off.\n\n" +
                    "All measurements stay on your phone in CSV files that only you export or share."
            )
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ ->
                prefs.edit().putBoolean("disclosed", true).apply()
                askPermissions()
            }
            .show()
    }

    private fun askPermissions() {
        val want = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            LiveMonitor.refreshPermissions()
            updateSimChip()
        }
    }

    // ───── SIM selection ─────
    private fun updateSimChip() {
        simChip.text = "${LiveMonitor.simLabel.substringBefore(":")} ▾"
    }

    private fun chooseSim() {
        if (LiveMonitor.isRecording) {
            Toast.makeText(this, "Stop the drive test before changing SIM", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        val subs = try {
            getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
        } catch (e: SecurityException) { emptyList() }
        val ids = mutableListOf(-1)
        val names = mutableListOf("Default SIM")
        subs.forEach {
            ids.add(it.subscriptionId)
            names.add("SIM${it.simSlotIndex + 1}: ${it.displayName ?: it.carrierName ?: ""}")
        }
        AlertDialog.Builder(this)
            .setTitle("Measure which SIM?")
            .setItems(names.toTypedArray()) { _, which ->
                LiveMonitor.setSubscription(this, ids[which], names[which])
                updateSimChip()
            }
            .show()
    }

    // ───── export ─────
    private fun shareFile(f: File) {
        val uri = LogFileProvider.uri(this, f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Drive test ${f.nameWithoutExtension}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share drive test CSV"))
    }

    @Suppress("DEPRECATION")
    private fun saveFile(f: File) {
        pendingSave = f
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, f.name)
        }
        try { startActivityForResult(i, REQ_SAVE) }
        catch (e: Exception) { Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val f = pendingSave ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            Toast.makeText(this, "Saved ${f.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${f.name}?")
            .setMessage("This drive test log will be removed from the phone.")
            .setPositiveButton("Delete") { _, _ ->
                f.delete()
                logsPage.refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

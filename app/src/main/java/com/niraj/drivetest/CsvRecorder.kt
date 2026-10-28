package com.niraj.drivetest

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes drive-test samples as Excel-friendly CSV (UTF-8 with BOM, CRLF). */
class CsvRecorder(val file: File) {

    companion object {
        val HEADER = listOf(
            "Date", "Time", "Latitude", "Longitude", "GPS_Accuracy_m", "Speed_kmh", "Altitude_m", "Bearing_deg",
            "SIM", "Operator", "Network_Type", "RAT", "MCC", "MNC", "TAC_LAC",
            "Cell_ID_ECI_NCI_CID", "eNB_gNB_RNC_ID", "Sector_LCID", "PCI_PSC_BSIC", "ARFCN", "Band", "BW_MHz",
            "RSRP_RSCP_dBm", "RSRQ_EcNo_dB", "SINR_dB", "RSSI_dBm", "CQI", "TA", "Level",
            "NR_NSA_SS_RSRP", "NR_NSA_SS_RSRQ", "NR_NSA_SS_SINR",
            "Neighbor_Count", "Neighbors"
        )

        fun q(v: Any?): String {
            val s = v?.toString() ?: return ""
            return if (s.contains(',') || s.contains('"') || s.contains('\n'))
                "\"" + s.replace("\"", "\"\"") + "\"" else s
        }

        fun neighborText(n: CellRec): String {
            val sb = StringBuilder(n.rat)
            n.pci?.let { sb.append(" PCI ").append(it) }
            n.arfcn?.let { sb.append(" CH ").append(it) }
            n.rsrp?.let { sb.append(" ").append(it).append("dBm") }
            n.rsrq?.let { sb.append(" Q").append(it) }
            return sb.toString()
        }
    }

    private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    var rows = 0; private set
    private var failed = false

    init {
        writer.write("﻿")
        writer.write(HEADER.joinToString(","))
        writer.write("\r\n")
        writer.flush()
    }

    fun write(s: Snapshot) {
        if (failed) return
        val c = s.serving
        val d = Date(s.time)
        val cols = listOf(
            dateFmt.format(d), timeFmt.format(d),
            s.lat?.let { String.format(Locale.US, "%.6f", it) },
            s.lon?.let { String.format(Locale.US, "%.6f", it) },
            s.accuracy?.let { String.format(Locale.US, "%.1f", it) },
            s.speedKmh?.let { String.format(Locale.US, "%.1f", it) },
            s.altitude?.let { String.format(Locale.US, "%.1f", it) },
            s.bearing?.let { String.format(Locale.US, "%.0f", it) },
            s.simLabel, s.operator, s.netType,
            c?.rat, c?.mcc, c?.mnc, c?.tac,
            c?.cid, c?.nodeId, c?.sector, c?.pci, c?.arfcn, c?.band, c?.bwMhz,
            c?.rsrp, c?.rsrq, c?.sinr, c?.rssi, c?.cqi, c?.ta, c?.level,
            s.nsa?.ssRsrp, s.nsa?.ssRsrq, s.nsa?.ssSinr,
            s.neighbors.size,
            s.neighbors.take(12).joinToString(" | ") { neighborText(it) }
        )
        try {
            writer.write(cols.joinToString(",") { q(it) })
            writer.write("\r\n")
            rows++
            if (rows % 5 == 0) writer.flush()
        } catch (e: Exception) {
            failed = true
        }
    }

    fun close() {
        try { writer.flush(); writer.close() } catch (e: Exception) {}
    }
}

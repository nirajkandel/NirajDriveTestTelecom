package com.niraj.drivetest

import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.annotation.TargetApi

/** One cell (serving or neighbour), normalised across 2G/3G/4G/5G. */
data class CellRec(
    val rat: String,            // LTE, NR, WCDMA, GSM
    val registered: Boolean,
    val mcc: String? = null,
    val mnc: String? = null,
    val cid: Long? = null,      // ECI / NCI / UTRAN CID / GSM CID
    val nodeId: Long? = null,   // eNB ID / gNB ID / RNC ID
    val sector: Long? = null,   // local cell ID / sector
    val tac: Int? = null,       // TAC (4G/5G) or LAC (2G/3G)
    val pci: Int? = null,       // PCI / PSC / BSIC
    val arfcn: Int? = null,     // EARFCN / NR-ARFCN / UARFCN / ARFCN
    val band: String? = null,
    val bwMhz: Int? = null,
    val rsrp: Int? = null,      // RSRP / SS-RSRP / RSCP / RxLev(dBm)
    val rsrq: Int? = null,      // RSRQ / SS-RSRQ / Ec/No
    val sinr: Int? = null,      // RS-SNR / SS-SINR
    val rssi: Int? = null,
    val cqi: Int? = null,
    val ta: Int? = null,
    val level: Int? = null      // 0..4 bars
)

/** 5G NR leg of an LTE anchor (NSA / EN-DC). */
data class NrNsa(val ssRsrp: Int?, val ssRsrq: Int?, val ssSinr: Int?)

data class CellResult(val serving: CellRec?, val neighbors: List<CellRec>, val nsa: NrNsa?)

object CellReader {

    private fun Int.ok(): Int? =
        if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this

    private fun Int?.range(lo: Int, hi: Int): Int? = if (this != null && this in lo..hi) this else null

    fun read(tm: TelephonyManager?, cells: List<CellInfo>?, sig: SignalStrength?): CellResult {
        val parsed = cells.orEmpty().mapNotNull { runCatching { parse(it) }.getOrNull() }
        val regs = parsed.filter { it.registered }
        val op = runCatching { tm?.networkOperator }.getOrNull().orEmpty()
        var serving = regs.firstOrNull {
            op.length >= 5 && it.mcc == op.substring(0, 3) && it.mnc == op.substring(3)
        } ?: regs.firstOrNull()

        // Some phones leave signal fields empty in CellInfo; fill from SignalStrength.
        if (serving != null && serving.rat == "LTE" && serving.rsrp == null && sig != null && Build.VERSION.SDK_INT >= 29) {
            val l = sig.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
            if (l != null) serving = serving.copy(
                rsrp = l.rsrp.ok().range(-156, -31),
                rsrq = l.rsrq.ok().range(-43, 20),
                sinr = l.rssnr.ok().range(-23, 40)
            )
        }

        val neighbors = parsed.filter { it !== serving && !it.registered }
            .sortedByDescending { it.rsrp ?: -999 }

        var nsa: NrNsa? = null
        if (sig != null && Build.VERSION.SDK_INT >= 29 && serving?.rat != "NR") {
            val nr = sig.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
            if (nr != null) {
                val n = NrNsa(
                    nr.ssRsrp.ok().range(-156, -31),
                    nr.ssRsrq.ok().range(-43, 20),
                    nr.ssSinr.ok().range(-23, 40)
                )
                if (n.ssRsrp != null || n.ssSinr != null) nsa = n
            }
        }
        return CellResult(serving, neighbors, nsa)
    }

    private fun parse(info: CellInfo): CellRec? {
        if (Build.VERSION.SDK_INT >= 29 && isNr(info)) return parseNr(info)
        return when (info) {
            is CellInfoLte -> parseLte(info)
            is CellInfoWcdma -> parseWcdma(info)
            is CellInfoGsm -> parseGsm(info)
            else -> null
        }
    }

    @TargetApi(29)
    private fun isNr(info: CellInfo) = info is CellInfoNr

    @Suppress("DEPRECATION")
    private fun parseLte(info: CellInfoLte): CellRec {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val ci = id.ci.ok()?.toLong()
        val earfcn = id.earfcn.ok()
        val bands = if (Build.VERSION.SDK_INT >= 30)
            id.bands.takeIf { it.isNotEmpty() }?.joinToString("/") { "B$it" } else null
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.ok()?.toString()
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.ok()?.toString()
        return CellRec(
            rat = "LTE",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc,
            cid = ci,
            nodeId = ci?.shr(8),
            sector = ci?.and(0xFF),
            tac = id.tac.ok(),
            pci = id.pci.ok(),
            arfcn = earfcn,
            band = bands ?: Bands.lte(earfcn),
            bwMhz = if (Build.VERSION.SDK_INT >= 28) id.bandwidth.ok()?.let { if (it > 0) it / 1000 else null } else null,
            rsrp = ss.rsrp.ok().range(-156, -31),
            rsrq = ss.rsrq.ok().range(-43, 20),
            sinr = ss.rssnr.ok().range(-23, 40),
            rssi = if (Build.VERSION.SDK_INT >= 29) ss.rssi.ok().range(-130, -10) else null,
            cqi = ss.cqi.ok().range(0, 15),
            ta = ss.timingAdvance.ok().range(0, 1282),
            level = ss.level
        )
    }

    @TargetApi(29)
    private fun parseNr(info: CellInfo): CellRec {
        val nrInfo = info as CellInfoNr
        val id = nrInfo.cellIdentity as CellIdentityNr
        val ss = nrInfo.cellSignalStrength as CellSignalStrengthNr
        val nci = id.nci.takeIf { it != Long.MAX_VALUE && it >= 0 }
        val arfcn = id.nrarfcn.ok()
        val bands = if (Build.VERSION.SDK_INT >= 30)
            id.bands.takeIf { it.isNotEmpty() }?.joinToString("/") { "n$it" } else null
        return CellRec(
            rat = "NR",
            registered = info.isRegistered,
            mcc = id.mccString, mnc = id.mncString,
            cid = nci,
            nodeId = nci?.shr(12),          // assumes 24-bit gNB ID
            sector = nci?.and(0xFFF),
            tac = id.tac.ok(),
            pci = id.pci.ok(),
            arfcn = arfcn,
            band = bands ?: Bands.nr(arfcn),
            rsrp = ss.ssRsrp.ok().range(-156, -31),
            rsrq = ss.ssRsrq.ok().range(-43, 20),
            sinr = ss.ssSinr.ok().range(-23, 40),
            level = ss.level
        )
    }

    @Suppress("DEPRECATION")
    private fun parseWcdma(info: CellInfoWcdma): CellRec {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val cid = id.cid.ok()?.toLong()
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.ok()?.toString()
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.ok()?.toString()
        return CellRec(
            rat = "WCDMA",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc,
            cid = cid,
            nodeId = cid?.shr(16),
            sector = cid?.and(0xFFFF),
            tac = id.lac.ok(),
            pci = id.psc.ok(),
            arfcn = id.uarfcn.ok(),
            band = Bands.umts(id.uarfcn.ok()),
            rsrp = ss.dbm.ok().range(-130, -20),
            rsrq = if (Build.VERSION.SDK_INT >= 30) ss.ecNo.ok().range(-30, 0) else null,
            level = ss.level
        )
    }

    @Suppress("DEPRECATION")
    private fun parseGsm(info: CellInfoGsm): CellRec {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.ok()?.toString()
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.ok()?.toString()
        return CellRec(
            rat = "GSM",
            registered = info.isRegistered,
            mcc = mcc, mnc = mnc,
            cid = id.cid.ok()?.toLong(),
            tac = id.lac.ok(),
            pci = id.bsic.ok(),
            arfcn = id.arfcn.ok(),
            band = Bands.gsm(id.arfcn.ok()),
            rsrp = ss.dbm.ok().range(-120, -20),
            rssi = if (Build.VERSION.SDK_INT >= 30) ss.rssi.ok().range(-120, -20) else null,
            ta = ss.timingAdvance.ok().range(0, 219),
            level = ss.level
        )
    }

    fun networkTypeName(t: Int): String = when (t) {
        1 -> "GPRS"; 2 -> "EDGE"; 3 -> "UMTS"; 8 -> "HSDPA"; 9 -> "HSUPA"
        10 -> "HSPA"; 15 -> "HSPA+"; 13 -> "LTE"; 19 -> "LTE-CA"; 20 -> "NR"
        16 -> "GSM"; 17 -> "TD-SCDMA"; 18 -> "IWLAN"
        else -> "Unknown"
    }

    fun primaryLabel(rat: String?) = when (rat) {
        "NR" -> "SS-RSRP"; "WCDMA" -> "RSCP"; "GSM" -> "RxLev"; else -> "RSRP"
    }

    fun qualityLabel(rat: String?) = when (rat) {
        "NR" -> "SS-RSRQ"; "WCDMA" -> "Ec/No"; "GSM" -> "—"; else -> "RSRQ"
    }

    fun sinrLabel(rat: String?) = when (rat) {
        "NR" -> "SS-SINR"; else -> "SINR"
    }
}

/** Frequency-number → band lookups (used when the phone doesn't report the band). */
object Bands {
    private val LTE = arrayOf(
        intArrayOf(1, 0, 599), intArrayOf(2, 600, 1199), intArrayOf(3, 1200, 1949),
        intArrayOf(4, 1950, 2399), intArrayOf(5, 2400, 2649), intArrayOf(7, 2750, 3449),
        intArrayOf(8, 3450, 3799), intArrayOf(12, 5010, 5179), intArrayOf(13, 5180, 5279),
        intArrayOf(17, 5730, 5849), intArrayOf(18, 5850, 5999), intArrayOf(19, 6000, 6149),
        intArrayOf(20, 6150, 6449), intArrayOf(25, 8040, 8689), intArrayOf(26, 8690, 9039),
        intArrayOf(28, 9210, 9659), intArrayOf(32, 9920, 10359), intArrayOf(38, 37750, 38249),
        intArrayOf(39, 38250, 38649), intArrayOf(40, 38650, 39649), intArrayOf(41, 39650, 41589),
        intArrayOf(42, 41590, 43589), intArrayOf(43, 43590, 45589), intArrayOf(66, 66436, 67335),
        intArrayOf(71, 68586, 68935)
    )

    fun lte(earfcn: Int?): String? {
        earfcn ?: return null
        return LTE.firstOrNull { earfcn in it[1]..it[2] }?.let { "B${it[0]}" }
    }

    fun nr(arfcn: Int?): String? {
        arfcn ?: return null
        return when (arfcn) {
            in 620000..653333 -> "n78"
            in 653334..680000 -> "n77"
            in 499200..537999 -> "n41"
            in 151600..160600 -> "n28"
            in 422000..434000 -> "n1"
            in 361000..376000 -> "n3"
            in 185000..192000 -> "n8"
            in 173800..178800 -> "n5"
            in 2054166..2104165 -> "n258"
            else -> null
        }
    }

    fun umts(uarfcn: Int?): String? {
        uarfcn ?: return null
        return when (uarfcn) {
            in 10562..10838 -> "B1 (2100)"
            in 2937..3088 -> "B8 (900)"
            in 4357..4458 -> "B5 (850)"
            in 9662..9938 -> "B2 (1900)"
            else -> null
        }
    }

    fun gsm(arfcn: Int?): String? {
        arfcn ?: return null
        return when (arfcn) {
            in 1..124, in 975..1023, 0 -> "GSM900"
            in 512..885 -> "DCS1800"
            in 128..251 -> "GSM850"
            else -> null
        }
    }
}

package com.example.celltrajectorymock.telephony

/**
 * A single registered serving-cell observation extracted from the radio modem.
 *
 * [cid] is a [Long] because the 5G New Radio identity (NCI) is 36 bits and does
 * not fit in an [Int]; LTE's ECI fits comfortably in the same field.
 */
data class CellSample(
    val rat: String,            // "LTE" or "NR"
    val mcc: Int,
    val mnc: Int,
    val lacTac: Int,            // TAC for LTE and NR
    val cid: Long,              // ECI (LTE) or NCI (NR)
    val dbm: Int,               // serving-cell signal strength
    val timestampMillis: Long
) {
    fun sameCellAs(other: CellSample?): Boolean =
        other != null &&
            mcc == other.mcc &&
            mnc == other.mnc &&
            lacTac == other.lacTac &&
            cid == other.cid

    /** Compact identity string for logs and notifications. */
    fun key(): String = "$rat/$mcc-$mnc-$lacTac-$cid"
}

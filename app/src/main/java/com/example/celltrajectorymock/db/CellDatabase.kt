package com.example.celltrajectorymock.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.FileOutputStream

/**
 * Read-only access to the pre-compiled, OpenCellID-derived cell database.
 *
 * The binary [DB_NAME] asset is copied into the app's private databases
 * directory, then opened read-only. The table is declared WITHOUT ROWID with a
 * composite primary key (mcc, mnc, lac_tac, cid), so a lookup is a single
 * index-organised B-Tree descent.
 *
 * The copy is refreshed automatically whenever the bundled asset's size differs
 * from the extracted copy — so shipping a rebuilt cells.db only requires a
 * reinstall, not a manual "clear app data".
 */
class CellDatabase(private val context: Context) {

    private var db: SQLiteDatabase? = null

    /** Extracts the asset (first run, or whenever it changed) and opens it. */
    fun open() {
        val target = context.getDatabasePath(DB_NAME)
        // AssetInputStream.available() reports the full uncompressed length;
        // a full copy makes the target exactly that size, so a mismatch means
        // the bundled database was replaced and must be re-extracted.
        val assetSize = context.assets.open(DB_NAME).use { it.available().toLong() }
        if (!target.exists() || target.length() != assetSize) {
            target.parentFile?.mkdirs()
            context.assets.open(DB_NAME).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
        db = SQLiteDatabase.openDatabase(
            target.path, null, SQLiteDatabase.OPEN_READONLY
        )
    }

    /** Resolves a tower to coordinates, or null if it is not in the database. */
    fun lookup(mcc: Int, mnc: Int, lacTac: Int, cid: Long): CellNode? {
        val handle = db ?: return null
        handle.rawQuery(
            "SELECT latitude, longitude, range_meters FROM cell_nodes " +
                "WHERE mcc = ? AND mnc = ? AND lac_tac = ? AND cid = ? LIMIT 1",
            arrayOf(
                mcc.toString(), mnc.toString(),
                lacTac.toString(), cid.toString()
            )
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return CellNode(
                    latitude = cursor.getDouble(0),
                    longitude = cursor.getDouble(1),
                    rangeMeters = cursor.getInt(2)
                )
            }
        }
        return null
    }

    fun close() {
        db?.close()
        db = null
    }

    companion object {
        const val DB_NAME = "cells.db"
    }
}

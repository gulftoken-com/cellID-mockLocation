#!/usr/bin/env python3
"""Compile an OpenCellID CSV export into the app's cells.db SQLite binary.

Usage:
    python3 tools/build_cell_db.py <opencellid.csv> app/src/main/assets/cells.db

OpenCellID CSV columns (positional — the per-MCC exports ship WITHOUT a header):
    radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal
      0    1   2   3    4    5    6   7    8      9        10       11      12        13

Only GSM, UMTS, LTE and NR rows are kept, matching the RATs this app resolves.
The output table is declared WITHOUT ROWID with a composite primary key so
lookups are a single index-organised B-Tree descent on low-spec hardware.
"""
import csv
import sqlite3
import sys

RADIO_KEEP = {"GSM", "UMTS", "LTE", "NR"}
BATCH = 50_000

# Positional column indices in the OpenCellID export.
C_RADIO, C_MCC, C_NET, C_AREA, C_CELL = 0, 1, 2, 3, 4
C_LON, C_LAT, C_RANGE = 6, 7, 8


def main() -> None:
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]

    con = sqlite3.connect(dst)
    con.execute("DROP TABLE IF EXISTS cell_nodes")
    con.execute(
        """
        CREATE TABLE cell_nodes (
            mcc          INTEGER NOT NULL,
            mnc          INTEGER NOT NULL,
            lac_tac      INTEGER NOT NULL,
            cid          INTEGER NOT NULL,
            latitude     REAL    NOT NULL,
            longitude    REAL    NOT NULL,
            range_meters INTEGER DEFAULT 1000,
            PRIMARY KEY (mcc, mnc, lac_tac, cid)
        ) WITHOUT ROWID
        """
    )

    kept, rows = 0, []
    with open(src, newline="") as fh:
        reader = csv.reader(fh)
        for c in reader:
            # Tolerate either layout: a leading "radio,mcc,net,..." header row
            # (some full exports) or none at all (the per-MCC files). Any row
            # whose first field is not a known RAT is skipped, which discards a
            # header and any stray blank lines without special-casing them.
            if len(c) <= C_RANGE or c[C_RADIO] not in RADIO_KEEP:
                continue
            try:
                rows.append((
                    int(c[C_MCC]), int(c[C_NET]),
                    int(c[C_AREA]), int(c[C_CELL]),
                    float(c[C_LAT]), float(c[C_LON]),
                    int(float(c[C_RANGE] or 1000)),
                ))
            except ValueError:
                continue
            if len(rows) >= BATCH:
                con.executemany(
                    "INSERT OR REPLACE INTO cell_nodes VALUES (?,?,?,?,?,?,?)",
                    rows,
                )
                kept += len(rows)
                rows = []
    if rows:
        con.executemany(
            "INSERT OR REPLACE INTO cell_nodes VALUES (?,?,?,?,?,?,?)", rows
        )
        kept += len(rows)

    con.commit()
    con.execute("VACUUM")
    con.close()
    print(f"Wrote {kept} cell nodes to {dst}")


if __name__ == "__main__":
    main()

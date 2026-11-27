"""Prints how many readings a copy of the app's Room database holds for one shipment, the newest one's time and
sequence number, and how many are still waiting to be sent. Column names as in app/schemas (version 5).

Usage: python -I room_readings.py <copy of coldchain_handshake.db> <shipment ID>
"""
import datetime
import sqlite3
import sys


def main():
    db, shipment = sys.argv[1], sys.argv[2]
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    count, newest, last_seq = con.execute(
        "select count(*), max(timestamp), max(seq) from temperature_events where shipmentId = ?", (shipment,)
    ).fetchone()
    pending = con.execute(
        "select count(*) from temperature_events where shipmentId = ? and syncStatus <> 'SYNCED'", (shipment,)
    ).fetchone()[0]
    when = datetime.datetime.fromtimestamp(newest / 1000).strftime("%H:%M:%S") if newest else "none"
    print(f"{shipment}: {count} readings on this phone (last seq {last_seq}), newest made {when}, {pending} not yet sent")


if __name__ == "__main__":
    main()

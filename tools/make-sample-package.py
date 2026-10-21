#!/usr/bin/env python3
"""Writes a synthetic Discord data package for testing the importer and the message browser on a phone.

Every ID, name and line of text is made up (seeded, so the output is reproducible). The shape follows Discord's
package as the importer reads it: messages/index.json, one folder per channel with channel.json and messages.json
(one folder uses the older messages.csv), plus a server channel the importer must skip and account/, activity/ and
servers/ folders it must never open. Usage: make-sample-package.py OUT.zip
"""
import json
import random
import sys
import zipfile
from datetime import datetime, timedelta, timezone

DISCORD_EPOCH_MS = 1_420_070_400_000
SEED = 34

WORDS = (
    "ok sure thanks see you tomorrow lol haha yes no maybe later tonight pizza meeting photo call game movie "
    "weekend plan done sent file link check this out nice cool great sorry late bus train coffee lunch dinner "
    "birthday ticket road trip rain sun cold hot song album book chapter exam project deadline draft review"
).split()


def snowflake(rng, dt):
    ms = int(dt.timestamp() * 1000) - DISCORD_EPOCH_MS
    return (ms << 22) | (rng.randrange(1, 32) << 17) | (rng.randrange(1, 32) << 12) | rng.randrange(0, 4096)


def stamp(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S.%f") + "+00:00"


def sentence(rng):
    n = rng.randrange(1, 12)
    words = [rng.choice(WORDS) for _ in range(n)]
    s = " ".join(words)
    return s[0].upper() + s[1:] + rng.choice([".", "!", "?", "", ""])


def messages(rng, count, start, spread_days, attachment_rate):
    rows = []
    for i in range(count):
        dt = start + timedelta(seconds=rng.randrange(0, spread_days * 86400))
        att = f"https://example.invalid/attachments/IMG_{rng.randrange(1000, 9999)}.jpg" if rng.random() < attachment_rate else ""
        rows.append((snowflake(rng, dt), dt, sentence(rng), att))
    rows.sort(key=lambda r: r[1])
    return rows


def main(out):
    rng = random.Random(SEED)
    base = datetime(2024, 2, 1, tzinfo=timezone.utc)
    users = {"alt": "710000000000000001", "ana": "710000000000000002", "ben": "710000000000000003", "cy": "710000000000000004"}
    dm_a = (snowflake(rng, base - timedelta(days=300)), "Direct Message with ana_sample#0", [users["ana"]])
    dm_b = (snowflake(rng, base - timedelta(days=200)), "Direct Message with ben_sample#0", [users["ben"]])
    group = (snowflake(rng, base - timedelta(days=100)), "Sample trip planning", [users["ana"], users["ben"], users["cy"]])
    guild_channel = snowflake(rng, base - timedelta(days=400))

    index = {str(dm_a[0]): dm_a[1], str(dm_b[0]): dm_b[1], str(group[0]): group[1], str(guild_channel): "general"}

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("messages/index.json", json.dumps(index, indent=2))

        for cid, _, recipients, kind in ((dm_a[0], None, dm_a[2], 1), (dm_b[0], None, dm_b[2], 1), (group[0], None, group[2], 3)):
            z.writestr(f"messages/c{cid}/channel.json", json.dumps({"id": str(cid), "type": kind, "recipients": recipients}, indent=2))

        rows_a = messages(rng, 240, base, 400, 0.08)
        z.writestr(
            f"messages/c{dm_a[0]}/messages.json",
            json.dumps([{"ID": r[0], "Timestamp": stamp(r[1]), "Contents": r[2], "Attachments": r[3]} for r in rows_a], indent=1),
        )

        rows_b = messages(rng, 60, base + timedelta(days=30), 200, 0.15)
        csv = ["ID,Timestamp,Contents,Attachments"]
        for r in rows_b:
            text = '"' + r[2].replace('"', '""') + '"'
            csv.append(f"{r[0]},{stamp(r[1])},{text},{r[3]}")
        z.writestr(f"messages/c{dm_b[0]}/messages.csv", "\n".join(csv) + "\n")

        rows_g = messages(rng, 120, base + timedelta(days=60), 300, 0.05)
        z.writestr(
            f"messages/c{group[0]}/messages.json",
            json.dumps([{"ID": r[0], "Timestamp": stamp(r[1]), "Contents": r[2], "Attachments": r[3]} for r in rows_g], indent=1),
        )

        # A server channel: the importer skips it (guild info in channel.json).
        z.writestr(
            f"messages/c{guild_channel}/channel.json",
            json.dumps({"id": str(guild_channel), "type": 0, "name": "general", "guild": {"id": "720000000000000001", "name": "Sample Server"}}, indent=2),
        )
        rows_s = messages(rng, 30, base, 100, 0.0)
        z.writestr(
            f"messages/c{guild_channel}/messages.json",
            json.dumps([{"ID": r[0], "Timestamp": stamp(r[1]), "Contents": r[2], "Attachments": ""} for r in rows_s], indent=1),
        )

        # Folders the importer must never open. Their content is made up; the app must show no trace of it.
        z.writestr("account/user.json", json.dumps({"id": users["alt"], "username": "sample_alt", "email": "sample@example.invalid", "phone": "+10000000000"}))
        z.writestr("activity/analytics/events-2024-00000-of-00001.json", '{"event_type":"app_opened","client_track_timestamp":"2024-02-01 00:00:00"}\n')
        z.writestr("servers/index.json", json.dumps({"720000000000000001": "Sample Server"}))
        z.writestr("README.txt", "Synthetic sample data package for DiscoRedact. Nothing in here is real.\n")

    print(f"{out}: DM {dm_a[0]} ({len(rows_a)} json), DM {dm_b[0]} ({len(rows_b)} csv), group {group[0]} ({len(rows_g)}), "
          f"server channel {guild_channel} ({len(rows_s)}, skipped); expected import: 3 channels, {len(rows_a)+len(rows_b)+len(rows_g)} messages")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "sample-package.zip")

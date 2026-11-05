"""Fail publication on bad hashes, identity collisions or unconfigured sources."""
import hashlib
import json
import re
from urllib.parse import urlsplit
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from library import ROOT, LIMITS, read
from event_identity import event_part, row_part


def check_pack(pack, company):
    assert pack["schemaVersion"] == 1 and pack["company"] == company
    assert isinstance(pack["revision"], int) and pack["revision"] > 0
    config = pack["sources"]
    archive = {entry["id"] for entry in config["archive"]["collections"]}
    youtube = {entry["id"]: entry["channelId"] for entry in config["youtube"].get("videos", [])}
    daily = {entry["id"]: entry["owner"] for entry in config.get("dailymotion", {}).get("videos", [])}
    okru = {entry['id']: entry['owner'] for entry in config.get('okru', {}).get('videos', [])}
    identifiers = set()
    rows = {row['id']: row for row in pack['catalogue']['episodes']}
    from library import source_key
    assignments = pack['catalogue'].get('sourceAssignments', {})
    for key, destination in assignments.items():
        assert destination in rows and any(source_key(s) == key for s in rows[destination]['sources']), 'Missing corrected source destination'
    for row in pack["catalogue"]["episodes"]:
        assert row["id"] not in identifiers, "Duplicate broadcast ID: " + row["id"]
        identifiers.add(row["id"])
        assert LIMITS[company][0] <= row["date"] <= LIMITS[company][1]
        assert row["year"] == int(row["date"][:4]) and row["sources"]
        for source in row["sources"]:
            key = source_key(source)
            assert assignments.get(key, row['id']) == row['id'], 'Source attached to wrong event: ' + row['id']
            assert key not in config.get('retiredSources', []), 'Retired source returned'
            title = source.get('title', source.get('filename', ''))
            if row['show'] == 'PPV' and title:
                assert row_part(row) == source.get('eventPart', event_part(title)), 'Main/pre-show mismatch: ' + row['id']
            if source["type"] == "archive":
                for url in source.get("urls", [source.get("url")]):
                    parsed = urlsplit(url)
                    assert parsed.scheme == "https" and parsed.hostname == "archive.org"
                    assert parsed.path.startswith("/download/")
                    assert parsed.path.split("/")[2] in archive, "Unconfigured Archive item: " + url
            elif source["type"] == "youtube":
                assert re.fullmatch(r"[A-Za-z0-9_-]{11}", source["videoId"])
                assert source["channelId"] == config["youtube"]["channelId"] or youtube.get(source["videoId"]) == source["channelId"]
            elif source["type"] == "dailymotion":
                assert all(daily.get(identifier) == source["owner"] for identifier in source.get("videoIds", [source["videoId"]]))
            elif source['type'] == 'okru':
                assert re.fullmatch(r'\d{5,20}',source['videoId'])
                assert okru.get(source['videoId']) == source['owner'], 'Unconfigured OK.ru recording'
                mapped = next(entry for entry in config['okru']['videos'] if entry['id'] == source['videoId'])
                assert mapped.get('episodeId', row['id']) == row['id'], 'Incorrect OK.ru episode mapping'
            else:
                raise AssertionError("Unsupported media provider")
    assert identifiers, "Refusing an empty library"
    return len(identifiers)


def main():
    manifest = read(ROOT / "catalogue/manifest.json")
    assert manifest["schemaVersion"] == 1
    for company in LIMITS:
        path = ROOT / "catalogue" / (company + ".json")
        raw = path.read_bytes()
        entry = manifest["companies"][company]
        if 'expanded' in entry:
            filename, first, last = {
                'njpw': ('njpw-2002.json', '2002-01-01', LIMITS[company][1]),
                'wwe': ('wwe-1985.json', '1985-01-01', LIMITS[company][1]),
                'ecw': ('ecw-2006.json', LIMITS[company][0], '2006-12-31'),
            }[company]
            compatible = (ROOT / 'catalogue' / filename).read_bytes()
            assert entry['bytes'] == len(compatible), filename + ': manifest byte count mismatch'
            assert len(compatible) <= 48 * 1024 * 1024, filename + ': source pack too large'
            assert entry['sha256'] == hashlib.sha256(compatible).hexdigest(), filename + ': manifest hash mismatch'
            legacy = json.loads(compatible)
            assert all(first <= row['date'] <= last for row in legacy['catalogue']['episodes']), filename + ': incompatible broadcast date'
            check_pack(legacy, company)
            entry = entry['expanded']
        assert entry["bytes"] == len(raw), path.name + ': manifest byte count mismatch'
        assert len(raw) <= 48 * 1024 * 1024, path.name + ': source pack too large'
        assert entry["sha256"] == hashlib.sha256(raw).hexdigest(), path.name + ': manifest hash mismatch'
        count = check_pack(json.loads(raw), company)
        print(company, count, "broadcasts; manifest hash verified")


if __name__ == "__main__":
    main()

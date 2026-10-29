"""Fail publication on bad hashes, identity collisions or unconfigured sources."""
import hashlib
import json
import re
from urllib.parse import urlsplit
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from library import ROOT, LIMITS, read


def check_pack(pack, company):
    assert pack["schemaVersion"] == 1 and pack["company"] == company
    assert isinstance(pack["revision"], int) and pack["revision"] > 0
    config = pack["sources"]
    archive = {entry["id"] for entry in config["archive"]["collections"]}
    youtube = {entry["id"]: entry["channelId"] for entry in config["youtube"].get("videos", [])}
    daily = {entry["id"]: entry["owner"] for entry in config.get("dailymotion", {}).get("videos", [])}
    identifiers = set()
    for row in pack["catalogue"]["episodes"]:
        assert row["id"] not in identifiers, "Duplicate broadcast ID: " + row["id"]
        identifiers.add(row["id"])
        assert LIMITS[company][0] <= row["date"] <= LIMITS[company][1]
        assert row["year"] == int(row["date"][:4]) and row["sources"]
        for source in row["sources"]:
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
        assert entry["bytes"] == len(raw) and len(raw) <= 16 * 1024 * 1024
        assert entry["sha256"] == hashlib.sha256(raw).hexdigest()
        count = check_pack(json.loads(raw), company)
        print(company, count, "broadcasts; manifest hash verified")


if __name__ == "__main__":
    main()

"""Build data-only libraries from explicit, human-readable source configurations.

No video is downloaded. A failed collection leaves its existing recordings alone.
Unrecognised dates and incomplete part sets are reported instead of guessed.
"""
from __future__ import annotations

import calendar
import hashlib
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MONTHS = {name.lower(): index for index, name in enumerate(calendar.month_name) if name}
MONTHS.update({name.lower(): index for index, name in enumerate(calendar.month_abbr) if name})
MONTHS["sept"] = 9
LIMITS = {"wwe": ("1995-01-01", "2005-12-31"), "ecw": ("1995-01-01", "2005-12-31"),
          "wcw": ("1983-01-01", "2001-03-26"), "tna": ("2002-01-01", "2015-12-31")}
CHANNELS = {"wwe": "UCJ5v_MCY6GNUBTO8-D3XoAg", "wcw": "UCbGG2hv4cWmIrBAIhGiCdSg",
            "ecw": "UCNSnSemXKDc9UEZ7m31wY9Q", "tna": "UCOp8wkVqdrWbFYHjDv946QQ"}


def read(path):
    return json.loads(Path(path).read_text("utf8"))


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Hash the same bytes GitHub serves after Git's text normalization on every OS.
    path.write_text(json.dumps(value, ensure_ascii=True, indent=2) + "\n",
                    encoding="utf8", newline="\n")


def key(value):
    return re.sub(r"[^a-z0-9]", "", value.lower())


def broadcast_date(name, year=None, order="MDY"):
    name = name.replace("_", " ")
    match = re.search(r"(?<!\d)((?:19|20)\d{2})[ .-](\d{1,2})[ .-](\d{1,2})(?!\d)", name)
    if match:
        try:
            return date(*map(int, match.groups())).isoformat()
        except ValueError:
            return None
    match = re.search(r"(?<!\d)(\d{1,2})[ .-](\d{1,2})[ .-]((?:19|20)\d{2})(?!\d)", name)
    if match:
        a, b, y = map(int, match.groups())
        # The collection declares ambiguous numeric date order; never infer from weekday.
        month, day = (b, a) if a > 12 or (b <= 12 and order == "DMY") else (a, b)
        try:
            return date(y, month, day).isoformat()
        except ValueError:
            return None
    match = re.search(r"\b(" + "|".join(MONTHS) + r")\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+((?:19|20)\d{2}))?", name, re.I)
    if match and (match[3] or year):
        try:
            return date(int(match[3] or year), MONTHS[match[1].lower()], int(match[2])).isoformat()
        except ValueError:
            return None
    if year:
        match = re.search(r"(?<!\d)(\d{1,2})[ .-](\d{1,2})[ .-]" + str(year)[2:] + r"(?!\d)", name)
        if match:
            a, b = map(int, match.groups())
            month, day = (b, a) if a > 12 or (b <= 12 and order == "DMY") else (a, b)
            try:
                return date(int(year), month, day).isoformat()
            except ValueError:
                return None
    return None


def source_key(source):
    value = source.get("videoId", source.get("url", json.dumps(source.get("urls", []), separators=(",", ":"))))
    return source["type"] + ":" + value


def archive_source(identifier, file, metadata):
    # Prefer an Archive-provided H.264 derivative when the original uses an older container.
    playable = file
    if not file["name"].lower().endswith((".mp4", ".m4v", ".mkv")):
        alternatives = [value for value in metadata.get("files", [])
                        if value.get("original") == file["name"] and value["name"].lower().endswith(".mp4")]
        if alternatives:
            playable = max(alternatives, key=lambda value: int(value.get("height") or 0))
    filename = playable["name"]
    height = int(playable.get("height") or 0)
    return {"type": "archive", "url": "https://archive.org/download/" + identifier + "/" + urllib.parse.quote(filename, safe="/"),
            "filename": filename, "duration": int(float(playable.get("length") or file.get("length") or 0)),
            "height": height, "bytes": int(playable.get("size") or 0),
            "label": "Internet Archive" + (f" · {height}p" if height else ""), "language": "en"}


def episode_thumbnail(identifier, file, metadata):
    """Use a still linked to this video, never the cover of its whole year collection."""
    candidates = []
    target = float(file.get("length") or 0) * .35
    for image in metadata.get("files", []):
        if image.get("original") != file["name"] or image.get("format") != "Thumbnail":
            continue
        match = re.search(r"_(\d+)\.(?:jpg|png)$", image["name"], re.I)
        if match:
            candidates.append((abs(int(match[1]) - target), image["name"]))
    if not candidates:
        return ""  # The app uses the company logo if a real episode image is unavailable.
    image = min(candidates)[1]
    return "https://archive.org/download/" + identifier + "/" + urllib.parse.quote(image, safe="/")


def add_record(library, company, when, show, title, source, description="", thumbnail=""):
    if not when or not LIMITS[company][0] <= when <= LIMITS[company][1]:
        return False
    identity = company + ":" + when + ":" + key(show)
    if show in ("PPV", "Live event", "One Night Only"):
        identity += ":" + key(title)
    rows = library["episodes"]
    existing = next((row for row in rows if row["id"] == identity
                     or any(source_key(value) == source_key(source) for value in row['sources'])), None)
    if existing is None:
        # Keep imported IDs (and watch history) when another source finds the same broadcast.
        matches = [row for row in rows if row['date'] == when and row['show'] == show
                   and (show not in ('PPV', 'Live event', 'One Night Only') or key(row['title']) == key(title))]
        if len(matches) == 1:
            existing = matches[0]
    if existing is None:
        existing = {"id": identity, "date": when, "year": int(when[:4]), "show": show,
                    "title": title, "description": description, "duration": source.get("duration", 0),
                    "thumbnail": thumbnail, "poster": thumbnail, "sources": [], "discoveredAt": 0}
        rows.append(existing)
    if not any(source_key(value) == source_key(source) for value in existing["sources"]):
        existing["sources"].append(source)
    if thumbnail and (source["type"] == "youtube" or not existing.get("thumbnail")):
        existing["thumbnail"] = thumbnail
    return True


def programme(filename, hint=""):
    """Explicit programme words override collection hints; dates never imply a show."""
    value = filename.replace('_', ' ')
    names = [(r'\b(?:thunder|rednuht)\b', 'Thunder'),
             (r'\b(?:nitro|ortin)\b', 'Nitro'), (r'\bworld\s*wide\b', 'Worldwide'),
             (r'\bsaturday\s*night\b', 'Saturday Night'),
             (r'\bpower\s*hour\b', 'Power Hour'), (r'\bmain\s*event\b', 'Main Event'),
             (r'\bsmack\s*down\b', 'SmackDown'), (r'\b(?:raw|war)\b', 'Raw'),
             (r'\b(?:sunday\s*night\s*)?heat\b', 'Heat'),
             (r'\b(?:ecw\s*on\s*)?tnn\b', 'ECW on TNN'),
             (r'\bhardcore\s*tv\b', 'Hardcore TV'), (r'\bimpact\b', 'Impact')]
    # PPV names such as Heat Wave must not be classified as weekly Heat.
    if hint == 'PPV':
        return hint
    return next((name for pattern, name in names if re.search(pattern, value, re.I)), hint)


def original_videos(metadata):
    return [value for value in metadata.get("files", []) if value.get("source") == "original"
            and re.search(r"\.(mp4|mkv|m4v|avi|wmv|mpg|mpeg)$", value["name"], re.I)
            and not value["name"].split("/")[-1].startswith("._")
            and int(value.get("size") or 0) > 1_000_000]


def scan_archive(library, company, config, metadata, events):
    identifier = config["id"]
    if config.get("frozen", False):
        return {"source": identifier, "status": "curated source; explicit mappings kept"}
    files = original_videos(metadata)
    grouped = defaultdict(list)
    skipped = []
    for file in files:
        filename = file["name"]
        if re.search(r"\b(?:partial|incomplete|inc|sample|highlights|clip|segment|recap|compilation|preview|review|full[ .-]+match|reliving[ .-]+the[ .-]+war)\b", filename.replace("_", " "), re.I):
            skipped.append(filename)
            continue
        override = config.get("overrides", {}).get(filename, {})
        when = override.get("date") or broadcast_date(filename, config.get("year"), config.get("dateOrder", "MDY"))
        show = override.get('show') or programme(filename, config.get("showHint", ""))
        if not when or not show:
            skipped.append(filename)
            continue
        # Source-specific exceptions are reviewable data, including taped/broadcast date differences.
        when = override.get("date", when)
        title = override.get("title", show)
        show = override.get("show", show)
        part = re.search(r"(?:part|cd)[ ._-]*(\d+)", filename, re.I)
        grouped[(when, show, title)].append((int(part[1]) if part else 0, file))
    added = 0
    for (when, show, title), parts in grouped.items():
        numbered = [number for number, _ in parts if number]
        if numbered and (len(numbered) != len(parts) or sorted(numbered) != list(range(1, max(numbered) + 1))):
            skipped.extend(file["name"] for _, file in parts)
            continue
        if numbered and max(numbered) < 2:
            skipped.extend(file["name"] for _, file in parts)
            continue
        sources = [archive_source(identifier, file, metadata) for _, file in sorted(parts, key=lambda value: value[0])]
        if numbered:
            source = sources[0].copy()
            source.pop("url", None)
            source["urls"] = [value["url"] for value in sources]
            source["duration"] = sum(value["duration"] for value in sources)
            source["bytes"] = sum(value["bytes"] for value in sources)
            sources = [source]
        for source in sources:
            minimum = 1200 if show in ("Heat", "Impact", "Hardcore TV", "ECW on TNN") else 2300
            if source["duration"] < minimum:
                skipped.append(source["filename"])
                continue
            added += bool(add_record(library, company, when, show, title, source,
                                     thumbnail=episode_thumbnail(identifier, parts[0][1], metadata)))
    return {"source": identifier, "status": "checked", "recordings": added, "skipped": skipped}


def fetch_metadata(identifier, cache_dir):
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / (identifier + ".json")
    tag_path = cache_dir / (identifier + ".etag")
    headers = {"User-Agent": "WrestlingChronology-Catalogue/0.1"}
    if path.exists() and tag_path.exists():
        headers["If-None-Match"] = tag_path.read_text("utf8")
    request = urllib.request.Request("https://archive.org/metadata/" + urllib.parse.quote(identifier), headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            data = response.read(12 * 1024 * 1024 + 1)
            if len(data) > 12 * 1024 * 1024:
                raise ValueError("Metadata exceeds size limit")
            result = json.loads(data)
            if not result.get("metadata") or not result.get("files"):
                raise ValueError("Empty metadata; previous recordings retained")
            write(path, result)
            tag_path.write_text(response.headers.get("ETag", ""), "utf8")
            return result
    except urllib.error.HTTPError as error:
        if error.code == 304 and path.exists():
            return read(path)
        raise


def public_request(url, payload=None):
    headers = {"User-Agent": "Mozilla/5.0", "Accept-Language": "en-GB,en;q=0.9"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=json.dumps(payload).encode() if payload else None, headers=headers)
    with urllib.request.urlopen(request, timeout=25) as response:
        data = response.read(12 * 1024 * 1024 + 1)
        if len(data) > 12 * 1024 * 1024:
            raise ValueError("Public metadata exceeds size limit")
        return data.decode("utf8")


def walk(value, wanted):
    if isinstance(value, dict):
        for name, child in value.items():
            if name == wanted:
                yield child
            yield from walk(child, wanted)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child, wanted)


def text(value):
    return value.get("simpleText", "".join(run.get("text", "") for run in value.get("runs", [])))


def scan_youtube(library, company, config, playlist):
    """Follow both public continuation formats and check every newly seen uploader."""
    url = "https://www.youtube.com/playlist?" + urllib.parse.urlencode({"list": playlist["id"], "hl": "en", "gl": "GB"})
    html = public_request(url)
    match = re.search(r"(?:var\s+)?ytInitialData\s*=\s*", html)
    if not match:
        raise ValueError("Playlist data unavailable")
    data = json.JSONDecoder().raw_decode(html[match.end():])[0]
    version = re.search(r'"INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"', html)
    known = {source.get("videoId") for row in library["episodes"] for source in row["sources"] if source["type"] == "youtube"}
    found, skipped, seen_tokens = 0, [], set()
    events = read(ROOT / "catalogue/event-reference.json").get(company, [])
    if company == "wcw":
        events = library.get("eventCalendar", [])
    max_pages = min(60, max(1, playlist.get('maxPages', 60)))
    for page in range(max_pages):
        videos = list(walk(data, "playlistVideoRenderer")) + list(walk(data, "videoRenderer"))
        for value in walk(data, "lockupViewModel"):
            if value.get("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO":
                continue
            badges = list(walk(value.get("contentImage", {}), "thumbnailBadgeViewModel"))
            duration = next((badge.get("text", "") for badge in badges if re.fullmatch(r"[0-9:]+", badge.get("text", ""))), "")
            videos.append({"videoId": value.get("contentId"), "title": {"simpleText": value.get("metadata", {}).get("lockupMetadataViewModel", {}).get("title", {}).get("content", "")}, "lengthText": {"simpleText": duration}})
        for video in videos:
            identifier = video.get("videoId", "")
            if identifier in known or not re.fullmatch(r"[A-Za-z0-9_-]{11}", identifier):
                continue
            title = text(video.get("title", {}))
            if not re.search(r"\bFULL(?:[ -]LENGTH)?[ :|-]+(?:EPISODE|EVENT|SHOW|PPV|PAY.PER.VIEW)\b", title, re.I):
                continue
            if re.search(r"FULL MATCH|compilation|tournament|watch party|best of", title, re.I):
                continue
            duration = 0
            try:
                for part in text(video.get("lengthText", {})).split(":"):
                    duration = duration * 60 + int(part)
            except ValueError:
                continue
            when = broadcast_date(title)
            show = playlist.get("show", "PPV")
            if show == 'Auto':
                # Upload feeds can mix companies and programme types.
                if company == 'ecw' and not re.search(r'\bECW\b', title, re.I):
                    continue
                if company == 'wwe' and re.search(r'\b(?:ECW|WCW|TNA)\b', title, re.I):
                    continue
                show = programme(title, 'PPV' if re.search(r'\b(?:FULL(?:[ -]LENGTH)?[ :|-]+(?:EVENT|PPV|PAY.PER.VIEW))\b', title, re.I) else '')
                if not show:
                    continue
            if not when:
                year = re.search(r"\b(19\d{2}|20\d{2})\b", title)
                matches = [event for event in events if year and event.get("date", "").startswith(year[1])
                           and key(event.get("name", event.get("title", "")))
                           and key(event.get("name", event.get("title", ""))) in key(title)]
                if len(matches) == 1:
                    when = matches[0]["date"]
            if not when or not LIMITS[company][0] <= when <= LIMITS[company][1] or duration < 1200:
                skipped.append(title)
                continue
            owner = playlist.get("authorUrl", {"wcw": "https://www.youtube.com/@WCW", "wwe": "https://www.youtube.com/@WWEVault", "ecw": "https://www.youtube.com/@WWEVault", "tna": "https://www.youtube.com/@tnawrestling"}[company])
            oembed = json.loads(public_request("https://www.youtube.com/oembed?" + urllib.parse.urlencode({"url": "https://www.youtube.com/watch?v=" + identifier, "format": "json"})))
            if oembed.get("author_url", "").lower() != owner.lower():
                skipped.append(title + ": unexpected uploader")
                continue
            channel = playlist.get("channelId", config["youtube"]["channelId"])
            same_day = [event for event in events if event.get('date') == when
                        and key(event.get('name', event.get('title', '')))
                        and key(event.get('name', event.get('title', ''))) in key(title)]
            canonical = same_day[0].get('name', same_day[0].get('title')) if show == 'PPV' and len(same_day) == 1 else title
            approved = {"id": identifier, "name": title, "title": canonical, "date": when, "show": show,
                        "duration": duration, "enabled": True, "channelId": channel, "authorUrl": owner}
            config["youtube"].setdefault("videos", []).append(approved)
            source = {"type": "youtube", "videoId": identifier, "channelId": channel, "direct": True,
                      "title": title, "duration": duration, "label": "Official YouTube"}
            found += bool(add_record(library, company, when, show, canonical if show == "PPV" else show, source,
                                     thumbnail="https://i.ytimg.com/vi/" + identifier + "/hqdefault.jpg"))
            known.add(identifier)
        command = next((node.get("continuationEndpoint", {}) for node in walk(data, "continuationItemRenderer")
                        if node.get("continuationEndpoint", {}).get("continuationCommand", {}).get("token")), None)
        if command is None:
            command = next((node.get("continuationCommand", {}).get("innertubeCommand", {}) for node in walk(data, "continuationItemViewModel")
                            if node.get("continuationCommand", {}).get("innertubeCommand", {}).get("continuationCommand", {}).get("token")), None)
        if not command:
            return {"source": "YouTube · " + playlist.get("name", playlist["id"]), "status": "checked", "new_recordings": found, "skipped": skipped}
        token = command["continuationCommand"]["token"]
        if token in seen_tokens or not version:
            raise ValueError("Playlist continuation repeated or unavailable; existing shows retained")
        seen_tokens.add(token)
        api = command.get("commandMetadata", {}).get("webCommandMetadata", {}).get("apiUrl", "/youtubei/v1/browse")
        if not api.startswith("/youtubei/v1/"):
            raise ValueError("Unexpected playlist continuation endpoint")
        data = json.loads(public_request("https://www.youtube.com" + api + "?prettyPrint=false", {
            "context": {"client": {"clientName": "WEB", "clientVersion": version[1], "hl": "en", "gl": "GB"}}, "continuation": token}))
    if playlist.get('recentOnly'):
        return {'source': 'YouTube recent uploads · ' + playlist.get('name', playlist['id']),
                'status': 'checked recent uploads', 'new_recordings': found, 'skipped': skipped}
    raise ValueError("Playlist page limit reached; existing shows retained")


def publish_packs(base_url, refresh=False):
    import time
    from concurrent.futures import ThreadPoolExecutor
    manifest = {"schemaVersion": 1, "companies": {}}
    report = []
    for company in LIMITS:
        config = read(ROOT / "sources" / (company + ".json"))
        seed = read(ROOT / "app/src/main/assets/libraries" / (company + ".json"))
        destination = ROOT / "catalogue" / (company + ".json")
        old = read(destination) if destination.exists() else None
        if old:
            # Discovered uploads stay configured; root source entries can explicitly disable them.
            discovered = {entry["id"]: entry for entry in old["sources"]["youtube"].get("videos", [])}
            discovered.update({entry["id"]: entry for entry in config["youtube"].get("videos", [])})
            config["youtube"]["videos"] = list(discovered.values())
        library = old["catalogue"] if old else seed
        if refresh:
            def scan(entry):
                try:
                    data = fetch_metadata(entry["id"], ROOT / ".source-cache")
                    return entry, data, None
                except Exception as error:
                    return entry, None, str(error)
            # Download concurrently, merge on one thread to keep identities deterministic.
            active = [entry for entry in config["archive"]["collections"] if entry.get("enabled", True)
                      and not entry.get('frozen', False)]
            with ThreadPoolExecutor(max_workers=3) as pool:
                for entry, metadata, error in pool.map(scan, active):
                    if error:
                        report.append({"company": company, "source": entry["id"], "status": "skipped", "reason": error})
                    else:
                        try:
                            report.append({"company": company, **scan_archive(library, company, entry, metadata, [])})
                        except Exception as error:
                            report.append({'company': company, 'source': entry['id'], 'status': 'skipped', 'reason': str(error)})
            for playlist in config["youtube"].get("playlists", []):
                if not playlist.get("enabled", True):
                    continue
                try:
                    report.append({"company": company, **scan_youtube(library, company, config, playlist)})
                except Exception as error:
                    report.append({"company": company, "source": "YouTube · " + playlist.get("name", playlist["id"]), "status": "skipped", "reason": str(error)})
        pack = {"schemaVersion": 1, "company": company, "revision": 1,
                "catalogue": library, "sources": config, "retiredSources": config.get("retiredSources", [])}
        if old:
            compare = {**old, "revision": 1}
            pack["revision"] = old["revision"] if compare == pack else max(old["revision"] + 1, int(time.time()))
        else:
            pack["revision"] = int(time.time())
        write(destination, pack)
        raw = destination.read_bytes()
        manifest["companies"][company] = {"url": base_url.rstrip("/") + "/" + company + ".json",
            "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw), "revision": pack["revision"]}
    write(ROOT / "catalogue/manifest.json", manifest)
    if refresh:
        write(ROOT / "catalogue/scan-report.json", report)
    return manifest

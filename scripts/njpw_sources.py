"""NJPW source parsing and bounded discovery, independent of APK releases."""
from __future__ import annotations

import concurrent.futures
from datetime import date
import html
import json
from pathlib import Path
import re
import time
from urllib.parse import quote, urlencode, urlsplit, unquote
from urllib.request import Request, urlopen

UA = 'WrestlingChronology source refresh/1.0'
VIDEO = re.compile(r'\.(mp4|m4v)$', re.I)
EXCLUDE = re.compile(r'preview|highlights|press conference|review|entrance|theme|biographies|individual matches|matches only|compilation|title defenses|fire pro|gameplay|\bIGF\b', re.I)


def key(value):
    return re.sub(r'[^a-z0-9]', '', value.lower())


def date_fragments(text, order):
    pattern = (r'(?<!\d)(?=((20\d{2})[ ._/-]+(\d{1,2})[ ._/-]+(\d{1,2}))(?!\d|[ ._/-]+\d{1,4}\b))'
               if order == 'ymd' else r'(?<!\d)(?=((\d{1,2})[ ._/-]+(\d{1,2})[ ._/-]+(20\d{2}|\d{2}))(?!\d))')
    for match in re.finditer(pattern, text):
        if order == 'dmy' and re.search(r'(?:day|night|tag|episode)[ ._/-]*$', text[:match.start()], re.I):
            continue
        try:
            a, b, c = map(int, match.groups()[1:])
            value = date(a, b, c) if order == 'ymd' else date(c + (2000 if c < 100 else 0), b, a)
            if date(2000, 1, 1) <= value <= date.today():
                yield value.isoformat(), match.start(), match.start() + len(match[1])
        except ValueError:
            continue


def numeric_date(text):
    # Four-digit year-first dates are unambiguous, including spaced filenames.
    # Inspect overlapping short dates so "day-9-03-10-21" cannot become 2010-03-09.
    for order in ['ymd', 'dmy']:
        candidates = list(date_fragments(text, order))
        if candidates:
            return candidates[-1][0]
    return None


def clean_title(value):
    value = html.unescape(value)
    value = re.sub(r'(?:\.(?:mp4|mkv|m4v))+(?:\s*\(\d+\))?$', '', value, flags=re.I).replace('_', ' ')
    for order in ['ymd', 'dmy']:
        fragments = list(date_fragments(value, order))
        if fragments:
            _, start, end = fragments[-1]
            value = value[:start] + value[end:]
    value = re.sub(r'^(?:N[ .]*J[ .]*P[ .]*W|N J P W)[ .:/-]*', '', value, flags=re.I)
    value = re.sub(r'\([^)]*(?:ENG|JAP|\d{3,4}p|60fps)[^)]*\)|\[(?:[^\]]*(?:fps|\d{3,4}p))\]', '', value, flags=re.I)
    value = re.sub(r'\b(?:DUAL|ENGLISH|JAPANESE|ENG|JPN|JAP|EN|JA|ORIGINAL|FHD|HD|WEBRIP|HDTV|\d{3,4}p|60fps|WEB|AAC|x26[45]|h26[45]|HEVC)\b.*$', '', value, flags=re.I)
    value = re.sub(r'\s*[-–—|]\s*\d{1,2}\s+(?:January|February|March|April|May|June|July|August|September|October|November|December),?\s+20\d{2}\s*$', '', value, flags=re.I)
    value = re.sub(r'\(\s*(?:20\d{2})?\s*\)', '', value)
    value = re.sub(r'\bG[ .]+1\b', 'G1', value, flags=re.I)
    value = re.sub(r'\bTag\s+(\d+)', r'Day \1', value, flags=re.I)
    value = re.sub(r'\s*[-~–—]*\s*\bDay\s+(\d+)', r' — Day \1', value, flags=re.I)
    value = re.sub(r'\s+', ' ', value.replace('.', ' ')).strip(' -~–—')
    value = re.sub(r'\s+(Of|The|In|To|And)\b', lambda m: ' ' + m[1].lower(), value)
    value = re.sub(r'\s+[-~–]\s+', ' — ', value)
    return value


def archive_date(metadata, filename, options):
    if options.get('date'):
        return options['date']
    file_date = numeric_date(filename)
    # A single item's explicit event date outranks a mistyped short year in its filename.
    # Year-first filenames identify individual broadcasts inside a multi-show collection.
    if list(date_fragments(filename, 'ymd')):
        return file_date
    explicit = str(metadata.get('date', ''))[:10]
    title_year = re.search(r'\b(20\d{2})\b', str(metadata.get('title', '')))
    if (re.fullmatch(r'20\d{2}-\d{2}-\d{2}', explicit) and numeric_date(explicit) == explicit
            and (not file_date or title_year and file_date[:4] != title_year[1] == explicit[:4])):
        return explicit
    return file_date or numeric_date(metadata.get('title', '')) or numeric_date(metadata.get('identifier', ''))


def tournament_date(title, config):
    if re.search(r'Road\s+To', title, re.I):
        return None
    match = re.search(r'G\s*1 Climax\s+(\d+).*?(?:Day|Night|Tag)\s+(\d+)', title, re.I)
    if match:
        edition, day = match.groups()
        if int(edition) >= 2000:
            edition = str(int(edition) - 1990)
        return config.get('tournamentCalendars', {}).get('g1-' + edition, {}).get('days', {}).get(day)
    return None


def event_signature(title):
    title = re.sub(r'\b(?:EN|JA|ENG|JPN|JAP)\b', '', title, flags=re.I)
    # The date identifies a tour night. Preserve the series and Road-to distinction.
    series = re.search(r'Wrestle Kingdom|G\s*1 Climax|Best Of The Super Junior|New Japan Cup|King Of Pro.Wrestling|Sakura Genesis|Death Vegas Invitational|Road To Wrestling Dontaku|Wrestling Dontaku|Dominion|Road To Destruction|Destruction In Kobe|The New Beginning USA', title, re.I)
    if series:
        return key(series[0].replace('G 1', 'G1'))
    return key(re.sub(r'\b20\d{2}\b', '', title))


def row(when, title, show, sources, thumbnail='', description='', duration=0):
    if not when or not sources:
        return None
    title = clean_title(title)
    return {'id': f'njpw:{when}:{key(show)}:{key(title)[:80]}', 'date': when, 'year': int(when[:4]),
            'show': show, 'title': title, 'description': description or title, 'duration': int(duration),
            'thumbnail': thumbnail, 'poster': thumbnail, 'sources': sources, 'discoveredAt': 0}


def archive_rows(metadata, config):
    m = metadata.get('metadata', {})
    ident = m.get('identifier')
    title = str(m.get('title', ''))
    if not ident or metadata.get('is_dark') or m.get('access-restricted-item') or EXCLUDE.search(title):
        return []
    first_year = re.search(r'\b((?:19|20)\d{2})\b', title)
    if first_year and int(first_year[1]) < 2000:
        return []
    if not re.search(r'NJPW|WPJN|New Japan Pro.Wrestling|New Japan Cup|Wrestle Kingdom|G1 Climax|All Together|Wrestle Dynasty|Fantastica Mania', title + ' ' + ident, re.I):
        return []
    if re.search(r'\bvs\.?\b', title, re.I) and not re.search(r'NJPW\s*(?:vs|/|X)\s*(?:NOAH|AJPW|DDT|ROH|CMLL)', title, re.I):
        return []
    options = config.get('archiveOverrides', {}).get(ident, {})
    if options.get('skip'):
        return []
    files = metadata.get('files', [])
    eligible = []
    weekly = bool(re.search(r'\bAXS\b|SXA|episode|STRONG\s*#\s*\d+|Lion[’\']s Break Collision', title, re.I))
    strong = bool(re.search(r'\bSTRONG\b', title, re.I))
    for f in files:
        name = f.get('name', '')
        duration = float(f.get('length') or 0)
        part_file = re.search(r'\b(?:part|pt)[ .-]*\d+\b', name, re.I)
        minimum = 300 if part_file else 1500 if weekly or strong else 5400
        if VIDEO.search(name) and duration >= minimum and not f.get('private') and name not in options.get('excludeFiles', []):
            # Full-show packs also contain individual bouts. Duration is only a first filter.
            if re.search(r'\s[vV][sS]\.?\s|\b(?:match|interview|digest)\b', name, re.I):
                continue
            eligible.append(f)
    if not eligible:
        return []
    collection_dates = {numeric_date(f['name']) for f in eligible} - {None}
    multiple_events = len(collection_dates) > 1
    # Archive derivatives and alternate encodes are alternatives, not additional shows.
    groups = {}
    for f in eligible:
        name = f['name']
        file_info = options.get('files', {}).get(name, {})
        when = file_info.get('date') or options.get('date') or tournament_date(title, config) or archive_date(m, name, options)
        if not when:
            wk = re.search(r'Wrestle Kingdom\s+(\d+)', title, re.I)
            year_match = re.search(r'\b(20\d{2})\b', title)
            if wk and year_match and 'night 3' not in title.lower() and 'night 2' not in title.lower():
                when = year_match[1] + '-01-04'
        if not when:
            continue
        edition = re.search(r'Wrestle[ .]+Kingdom[ .]+(\d+)\b', title, re.I)
        if edition and int(edition[1]) <= 20 and int(when[:4]) != 2006 + int(edition[1]):
            continue
        part = re.search(r'\b(?:part|pt)[ .-]*(\d+)\b', name, re.I)
        part_number = int(part[1]) if part else 0
        event_title = file_info.get('title') or options.get('title') or title
        if not file_info.get('title') and not options.get('title') and (multiple_events or re.fullmatch(r'NJPW\s*Content', title, re.I)):
            individual = clean_title(name)
            individual = re.sub(r'\b(?:part|pt)[ .-]*\d+\b|\bia\b', '', individual, flags=re.I).strip(' .-')
            event_title = individual or title
        # Distinct days in tournament collections receive explicit reviewed file dates.
        group = groups.setdefault((when, event_title), {})
        existing = group.get(part_number)
        score = (bool(re.search(r'\bENG(?:LISH)?\b', name, re.I)), not name.lower().endswith('.ia.mp4'),
                 int(f.get('height') or 0), -int(f.get('size') or 0))
        if existing is None or score > existing[0]:
            group[part_number] = (score, f)
    result = []
    for (when, event_title), parts in groups.items():
        if 0 in parts:
            selected = [parts[0][1]]
        else:
            numbers = sorted(parts)
            if len(numbers) < 2 or numbers != list(range(1, max(numbers) + 1)):
                continue
            selected = [parts[n][1] for n in numbers]
        urls = ['https://archive.org/download/' + ident + '/' + quote(f['name'], safe='/') for f in selected]
        source = {'type': 'archive', 'label': 'Internet Archive', 'duration': int(sum(float(f.get('length') or 0) for f in selected))}
        if source['duration'] < (1500 if weekly or strong else 5400):
            continue
        source['language'] = 'ja' if re.search(r'\bJA(?:PANESE)?\b|\bJPN\b', selected[0]['name'], re.I) else 'en' if re.search(r'\bEN(?:GLISH)?\b|\bENG\b', selected[0]['name'], re.I) else 'und'
        source['url' if len(urls) == 1 else 'urls'] = urls[0] if len(urls) == 1 else urls
        weekly_broadcast = weekly or strong and source['duration'] < 5400
        show = options.get('show') or ('NJPW on AXS' if re.search(r'AXS|SXA', title, re.I) else 'NJPW STRONG' if weekly_broadcast else 'PPV')
        if re.search(r'Lion[’\']s Break Collision', title, re.I):
            show = "Lion's Break Collision"
        if int(when[:4]) < 2007 and show == 'PPV' and re.search(r'\bSxw|Complete Edition|World Pro.Wrestling', ' '.join(f['name'] for f in selected), re.I):
            show = 'World Pro-Wrestling'
        if re.search(r'pre.?show|zero.hour|buy.in', event_title, re.I):
            continue
        target = float(selected[0].get('length') or 0) * .35
        stills = [f for f in files if f.get('format') == 'Thumbnail' and f.get('original') == selected[0]['name']]
        def distance(f):
            match = re.search(r'_(\d+)\.(?:jpg|png)$', f['name'], re.I)
            return abs(int(match[1]) - target) if match else 10**9
        thumbnail = 'https://archive.org/download/' + ident + '/' + quote(min(stills, key=distance)['name'], safe='/') if stills else ''
        broadcast = row(when, event_title, show, [source], thumbnail, duration=source['duration'])
        if show == 'NJPW on AXS':
            broadcast.update(title=show, description=show)
        result.append(broadcast)
    return result


def source_key(source):
    return source['type'] + ':' + source.get('videoId', source.get('url', json.dumps(source.get('urls', []))))


def merge_rows(existing, incoming):
    merged = {r['id']: dict(r, sources=list(r['sources'])) for r in existing}
    owners = {source_key(s): r['id'] for r in existing for s in r['sources']}
    def event_key(r):
        signature = r['show'] if r['show'] in ('NJPW STRONG', 'STRONG On Demand', 'NJPW on AXS') else event_signature(r['title'])
        return r['date'], r['show'], signature
    events = {event_key(r): r['id'] for r in existing}
    for r in incoming:
        if not r:
            continue
        event = event_key(r)
        identity = next((owners[source_key(s)] for s in r['sources'] if source_key(s) in owners), events.get(event, r['id']))
        previous = merged.get(identity)
        if previous:
            sources = {source_key(s): s for s in previous['sources']}
            sources.update({source_key(s): s for s in r['sources']})
            previous['sources'] = list(sources.values())
            if not previous.get('thumbnail') and r.get('thumbnail'):
                previous['thumbnail'] = r['thumbnail']
            if r['sources'][0]['type'] == 'youtube':
                previous.update(title=r['title'], description=r['description'], thumbnail=r['thumbnail'])
            elif 'day ' in r['title'].lower() and 'day ' not in previous['title'].lower():
                previous['title'] = r['title']
        else:
            merged[identity] = r
        for s in r['sources']:
            owners[source_key(s)] = identity
        events[event] = identity
    return sorted(merged.values(), key=lambda r: (r['date'], r['show'], r['id']))


def fetch_json(url):
    with urlopen(Request(url, headers={'User-Agent': UA}), timeout=12) as response:
        raw = response.read(12 * 1024 * 1024 + 1)
    if len(raw) > 12 * 1024 * 1024:
        raise ValueError('Source metadata exceeds size limit')
    return json.loads(raw)


def known_archive_items(rows):
    result = set()
    for row in rows:
        for source in row.get('sources', []):
            if source.get('type') != 'archive':
                continue
            for url in source.get('urls', [source.get('url', '')]):
                parsed = urlsplit(url)
                parts = parsed.path.split('/')
                if parsed.hostname == 'archive.org' and len(parts) > 3 and parts[1] == 'download':
                    result.add(unquote(parts[2]))
    return result


def refresh(root, previous, progress):
    config = json.loads((root / 'sources/njpw.json').read_bytes())
    path = root / 'data/njpw-source-state.json'
    state = json.loads(path.read_bytes()) if path.exists() else {'archiveSeen': []}
    known = known_archive_items(previous)
    seen = set(state.get('archiveSeen', [])) | known
    fresh = []
    failures = []
    configured = set(config.get('archiveItems', []))
    checked_configured = set(state.get('configuredArchiveItems', [])) | (configured & known)
    for ident in sorted(configured - checked_configured)[:30]:
        try:
            fresh.extend(archive_rows(fetch_json('https://archive.org/metadata/' + quote(ident, safe='')), config))
            checked_configured.add(ident)
        except Exception as error:
            failures.append('Archive item ' + ident + ': ' + str(error))
    for index, source in enumerate(config['archiveProfiles']):
        progress(10 + index * 10, 'Scanning Internet Archive: ' + source['name'])
        try:
            params = urlencode({'q': source['query'], 'rows': 1000, 'output': 'json',
                                'fl[]': 'identifier', 'sort[]': 'publicdate desc'})
            docs = fetch_json('https://archive.org/advancedsearch.php?' + params)['response']['docs']
            new_ids = [r['identifier'] for r in docs if r['identifier'] not in seen][:30]
            def inspect(ident):
                try:
                    parsed = archive_rows(fetch_json('https://archive.org/metadata/' + quote(ident, safe='')), config)
                    return ident, parsed, None
                except Exception as error:
                    return ident, [], str(error)
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                for ident, rows, error in pool.map(inspect, new_ids):
                    if error:
                        failures.append(ident + ': ' + error)
                    else:
                        seen.add(ident)
                        fresh.extend(rows)
        except Exception as error:
            failures.append(source['name'] + ': ' + str(error))
    for ident in config.get('archiveCollections', []):
        try:
            fresh.extend(archive_rows(fetch_json('https://archive.org/metadata/' + quote(ident, safe='')), config))
        except Exception as error:
            failures.append('Archive collection ' + ident + ': ' + str(error))
    # YouTube uploads are checked against explicit full-show title and date rules.
    from njpw_youtube import discover
    youtube, notes = discover(config, progress)
    fresh.extend(youtube)
    failures.extend(notes)
    result = merge_rows(previous, fresh)
    previous_ids = {r['id'] for r in previous}
    now = int(time.time() * 1000)
    for r in result:
        if r['id'] not in previous_ids:
            r['discoveredAt'] = now
    state.update(archiveSeen=sorted(seen), configuredArchiveItems=sorted(checked_configured), checkedAt=int(time.time()), skipped=failures[:30])
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(state, separators=(',', ':')), encoding='utf8')
    temporary.replace(path)
    progress(95, 'Source scan finished' + (f'; {len(failures)} skipped (details retained)' if failures else ''))
    return result, len({r['id'] for r in result} - previous_ids), failures

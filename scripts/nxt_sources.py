"""Bounded NXT discovery from reviewed Archive packs and official YouTube feeds.

sources/nxt.json is the editable source list. archiveOverrides accepts either
{collection: {files: {filename: {date, show, title}}}} or collection/filename keys.
weeklyDates is a reference, never a cutoff for newly published dated episodes.
OK.ru mappings are reviewed seeds; this module does not bulk-probe their players.
"""
from __future__ import annotations

from datetime import date, datetime
import json
from pathlib import Path
import re
import time
from urllib.parse import quote
from urllib.request import Request, urlopen

UA = 'WrestlingChronology NXT source refresh/1.0'
MAX_BYTES = 12 * 1024 * 1024
VIDEO = re.compile(r'\.(?:mp4|m4v|mkv|webm)$', re.I)
PROGRAMME = re.compile(r'\bN[ ._-]*X[ ._-]*T\b|\bTXN\b|\bNTX\b', re.I)
EXCLUDE = re.compile(
    r'highlights?|preview|countdown|kick[ ._-]*off|pre[ ._-]*show|'
    r'podcast|review|reaction|predictions?|interview|entrance|theme[ ._-]*song|'
    r'gameplay|\b2k\d{2}\b|\buniverse\b|compilation|matches[ ._-]*only|'
    r'full[ ._-]*match|\bvs[ ._-]|\bversus\b|incomplete|partial|'
    r'\bclip\b|\b(?:part|pt|half)[ ._-]*\d+|\b[hc][12]\b', re.I)
FULL = re.compile(r'(?:full|complete|free)[ ._-]+(?:episode|event|show)|'
                  r'full[ ._-]+(?:WWE[ ._-]+)?NXT', re.I)


def key(value):
    return re.sub(r'[^a-z0-9]', '', str(value).lower().replace('&', 'and'))


def valid_date(value):
    try:
        parsed = date.fromisoformat(str(value))
        return parsed.isoformat() if date(2010, 1, 1) <= parsed <= date.today() else None
    except (TypeError, ValueError):
        return None


def iso_date(title):
    """Only an original year-first filename date; never an upload timestamp."""
    match = re.search(r'(?<!\d)((?:19|20)\d{2})[ ._-](\d{1,2})[ ._-](\d{1,2})(?!\d)', str(title))
    if match:
        try:
            return valid_date(date(*map(int, match.groups())).isoformat())
        except ValueError:
            pass
    return None


def title_date(title):
    found = iso_date(title)
    if found:
        return found
    months = r'January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec'
    patterns = [(rf'\b({months})\.?\s+(\d{{1,2}})(?:st|nd|rd|th)?[,]?\s+(20\d{{2}})\b', False),
                (rf'\b(\d{{1,2}})(?:st|nd|rd|th)?\s+({months})\.?[,]?\s+(20\d{{2}})\b', True)]
    for pattern, reverse in patterns:
        match = re.search(pattern, title, re.I)
        if match:
            a, b, year = match.groups()
            day, month = (a, b) if reverse else (b, a)
            month = month[:3].title()
            try:
                return valid_date(datetime.strptime(f'{day} {month} {year}', '%d %b %Y').date().isoformat())
            except ValueError:
                pass
    return None


def length(value):
    try:
        if ':' in str(value):
            total = 0
            for piece in str(value).split(':'):
                total = total * 60 + int(piece)
            return total
        return max(0, int(float(value or 0)))
    except (ValueError, TypeError, OverflowError):
        return 0


def minimum(when, show):
    return 5400 if show == 'PPV' else 4200 if show == 'NXT' and when >= '2019-09-18' else 1800


def event_signature(title):
    value = re.sub(r'\b(?:WWE|NXT)\b|\b20\d{2}\b|\b(?:FULL|COMPLETE) (?:EVENT|SHOW)\b', '', str(title), flags=re.I)
    value = re.sub(r'^\W*the\b', '', value, flags=re.I)
    return key(value)


def mapping_values(value):
    """Both ID-keyed dictionaries and editable lists of mapping records work."""
    if isinstance(value, dict):
        return {str(k): v for k, v in value.items() if isinstance(v, dict)}
    if isinstance(value, list):
        return {str(v.get('videoId', v.get('id', ''))): v for v in value if isinstance(v, dict)}
    return {}


def file_override(config, ident, filename):
    overrides = config.get('archiveOverrides', {})
    nested = overrides.get(ident, {})
    return overrides.get(ident + '/' + filename, overrides.get(filename,
           nested.get('files', {}).get(filename, {}) if isinstance(nested, dict) else {}))


def event_mapping(title, config, when=None):
    events = config.get('eventDates', {})
    if when in events:
        value = events[when]
        return when, value.get('title', '') if isinstance(value, dict) else str(value)
    years = set(re.findall(r'\b(20\d{2})\b', str(title)))
    signature = event_signature(title)
    matches = []
    for candidate, value in events.items():
        name = value.get('title', '') if isinstance(value, dict) else str(value)
        identity = event_signature(name)
        if (valid_date(candidate) and candidate[:4] in years and identity
                and identity in signature):
            matches.append((candidate, name))
    return matches[0] if len(matches) == 1 else None


def identity(title, config, options=None, filename=False):
    options = options or {}
    if options.get('skip'):
        return None
    when = valid_date(options.get('date')) or (iso_date(title) if filename else title_date(title))
    show = options.get('show')
    name = options.get('title')
    event = event_mapping(title, config, when)
    if show == 'PPV':
        # Explicit reviewed filename/video mapping is sufficient; metadata dates are not.
        if not when or not name:
            return None
    elif show:
        if show not in ('NXT', 'NXT UK', 'NXT Level Up') or not when:
            return None
        name = name or show
    elif event:
        when, name = event
        show = 'PPV'
    elif when and PROGRAMME.search(title):
        # Historical packs mix taping, upload and broadcast dates. Use the reviewed
        # calendar for those dates; allow new broadcasts beyond that calendar.
        reviewed = config.get('weeklyDates', [])
        if reviewed and when <= max(reviewed) and when not in reviewed:
            return None
        # A dated TakeOver/PLE cannot silently become an ordinary weekly episode.
        if re.search(r'take[ ._-]*over|vengeance[ ._-]*day|stand[ ._-]*(?:and|&)[ ._-]*deliver|'
                     r'\bdeadline\b|\bbattleground\b|no[ ._-]*mercy|worlds[ ._-]*collide', title, re.I):
            return None
        show, name = 'NXT', 'NXT'
    else:
        return None
    return when, show, name


def row(when, show, title, source, duration=0, thumbnail=''):
    suffix = ':' + event_signature(title)[:80] if show == 'PPV' else ''
    return {'id': f'nxt:{when}:{key(show)}{suffix}', 'date': when, 'year': int(when[:4]),
            'show': show, 'title': title, 'description': title, 'duration': int(duration),
            'thumbnail': thumbnail, 'poster': thumbnail, 'sources': [source], 'discoveredAt': 0}


def archive_rows(metadata, config):
    meta = metadata.get('metadata', {})
    ident = str(meta.get('identifier', ''))
    if not ident or metadata.get('is_dark') or meta.get('access-restricted-item'):
        return []
    result = []
    for file in metadata.get('files', []):
        filename = file.get('name', '')
        options = file_override(config, ident, filename)
        if not VIDEO.search(filename) or file.get('private') or EXCLUDE.search(filename):
            continue
        # Pack membership and runtime alone never identify a full NXT programme.
        if not options and not PROGRAMME.search(filename):
            continue
        parsed = identity(filename, config, options, filename=True)
        if not parsed:
            continue
        when, show, title = parsed
        runtime = length(file.get('length') or file.get('duration'))
        if runtime < minimum(when, show):
            continue
        source = {'type': 'archive', 'url': 'https://archive.org/download/' + quote(ident, safe='') + '/' + quote(filename, safe='/'),
                  'filename': filename, 'creator': ident, 'label': 'Internet Archive · ' + ident,
                  'duration': runtime, 'height': length(file.get('height')),
                  'dateEvidence': 'Reviewed filename mapping' if options else 'Original ISO filename date' if iso_date(filename) else 'Explicit event calendar mapping'}
        if file.get('size'):
            source['bytes'] = length(file['size'])
        if file.get('md5'):
            source['md5'] = file['md5']
        result.append(row(when, show, title, source, runtime))
    return merge_rows([], result)


def source_key(source):
    identifiers = source.get('videoIds') or source.get('urls')
    return source.get('type', '') + ':' + (json.dumps(identifiers, separators=(',', ':')) if identifiers
                                           else str(source.get('videoId') or source.get('url', '')))


def merge_rows(previous, incoming):
    merged = {r['id']: dict(r, sources=[dict(s) for s in r.get('sources', [])]) for r in previous}
    def broadcast(r):
        return r['date'], r['show'], event_signature(r['title']) if r['show'] == 'PPV' else r['show']
    events = {broadcast(r): r['id'] for r in previous}
    owners = {source_key(s): r['id'] for r in previous for s in r.get('sources', [])}
    for candidate in incoming:
        if not candidate:
            continue
        owned = {owners[source_key(s)] for s in candidate['sources'] if source_key(s) in owners}
        # Conflicting new mappings cannot move an existing recording to another date.
        if any(broadcast(merged[i]) != broadcast(candidate) for i in owned):
            continue
        ident = next(iter(owned), events.get(broadcast(candidate), candidate['id']))
        if ident in merged:
            old = merged[ident]
            sources = {source_key(s): s for s in old['sources']}
            sources.update({source_key(s): s for s in candidate['sources']})
            old['sources'] = list(sources.values())
            for field in ('thumbnail', 'poster', 'duration'):
                if not old.get(field) and candidate.get(field):
                    old[field] = candidate[field]
        else:
            merged[ident] = dict(candidate, sources=[dict(s) for s in candidate['sources']])
        events[broadcast(candidate)] = ident
        for source in candidate['sources']:
            owners[source_key(source)] = ident
    return sorted(merged.values(), key=lambda r: (r['date'], r['show'], r['id']))


def fetch_text(url):
    with urlopen(Request(url, headers={'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'}), timeout=15) as response:
        if response.status == 204:
            raise ValueError('HTTP 204 empty-body availability barrier')
        raw = response.read(MAX_BYTES + 1)
    if not raw or len(raw) > MAX_BYTES:
        raise ValueError('Public source data is empty or exceeds size limit')
    return raw.decode('utf-8')


def fetch_json(url):
    return json.loads(fetch_text(url))


def walk(value, name):
    if isinstance(value, dict):
        for k, child in value.items():
            if k == name:
                yield child
            yield from walk(child, name)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child, name)


def text(value):
    return value.get('simpleText', ''.join(r.get('text', '') for r in value.get('runs', [])))


def youtube_data(url):
    raw = fetch_text(url)
    match = re.search(r'(?:var\s+)?ytInitialData\s*=\s*', raw)
    if not match:
        raise ValueError('Public YouTube channel/playlist JSON unavailable')
    return json.JSONDecoder().raw_decode(raw[match.end():])[0]


def youtube_row(video, config):
    ident, title = video['id'], video['title']
    known = mapping_values(config.get('youtubeBroadcasts', {})).get(ident)
    if EXCLUDE.search(title) or (not known and (not FULL.search(title) or not PROGRAMME.search(title))):
        return None
    parsed = identity(title, config, known)
    if not parsed:
        return None
    when, show, name = parsed
    runtime = length(video.get('duration') or (known or {}).get('duration'))
    if runtime < minimum(when, show):
        return None
    thumbnail = 'https://i.ytimg.com/vi/' + ident + '/hqdefault.jpg'
    source = {'type': 'youtube', 'videoId': ident, 'label': 'YouTube · WWE NXT',
              'duration': runtime, 'dateEvidence': 'Reviewed official video mapping' if known else 'Original broadcast date in full-programme title'}
    if video.get('channelId'):
        source['channelId'] = video['channelId']
    return row(when, show, name, source, runtime, thumbnail)


def youtube_videos(data):
    found = {}
    for kind in ('videoRenderer', 'playlistVideoRenderer'):
        for renderer in walk(data, kind):
            ident = renderer.get('videoId')
            if ident:
                found[ident] = {'id': ident, 'title': text(renderer.get('title', {})),
                                'duration': text(renderer.get('lengthText', {})) or renderer.get('lengthSeconds', '')}
                byline = renderer.get('shortBylineText', renderer.get('ownerText', {}))
                owner = next((b.get('browseId') for b in walk(byline, 'browseEndpoint')
                              if str(b.get('browseId', '')).startswith('UC')), '')
                if owner:
                    found[ident]['channelId'] = owner
    for renderer in walk(data, 'lockupViewModel'):
        ident = renderer.get('contentId', '')
        if len(ident) != 11 or 'VIDEO' not in renderer.get('contentType', ''):
            continue
        badges = list(walk(renderer.get('contentImage', {}), 'thumbnailBadgeViewModel'))
        runtime = next((b.get('text') for b in badges if re.fullmatch(r'[\d:]+', b.get('text', ''))), '')
        found[ident] = {'id': ident, 'title': renderer.get('metadata', {}).get('lockupMetadataViewModel', {}).get('title', {}).get('content', ''), 'duration': runtime}
    return list(found.values())


def discover_youtube(config, progress):
    rows, skipped = [], []
    for channel in config.get('youtubeChannels', []):
        handle = channel.get('handle', '').lstrip('@')
        label = channel.get('name', handle)
        progress(60, 'Scanning official NXT YouTube: ' + label)
        try:
            if not re.fullmatch(r'[A-Za-z0-9_.-]+', handle) or not channel.get('channelId'):
                raise ValueError('Configured channel handle and channelId are required')
            data = youtube_data('https://www.youtube.com/@' + quote(handle, safe='') + '/videos?hl=en&gl=GB')
            metadata = next(walk(data, 'channelMetadataRenderer'), {})
            if metadata.get('externalId') != channel['channelId']:
                raise ValueError('Official channel identity mismatch')
            rows.extend(parsed for v in youtube_videos(data)
                        if (parsed := youtube_row(dict(v, channelId=channel['channelId']), config)))
        except Exception as error:
            skipped.append('YouTube ' + label + ': ' + str(error))
    for playlist in config.get('youtubePlaylists', []):
        label = playlist.get('name', playlist.get('id', ''))
        try:
            ident = playlist.get('id', '')
            if not re.fullmatch(r'[A-Za-z0-9_-]+', ident):
                raise ValueError('Invalid playlist ID')
            data = youtube_data('https://www.youtube.com/playlist?list=' + quote(ident, safe='') + '&hl=en&gl=GB')
            rows.extend(parsed for v in youtube_videos(data) if (parsed := youtube_row(v, config)))
        except Exception as error:
            skipped.append('YouTube playlist ' + label + ': ' + str(error))
    return merge_rows([], rows), skipped


def okru_rows(config, previous=()):
    rows, skipped = [], []
    existing_ids = {str(ident) for value in previous for source in value.get('sources', [])
                    if source.get('type') == 'okru'
                    for ident in (source.get('videoIds') or [source.get('videoId', '')])}
    for ident, options in mapping_values(config.get('okruVideos', {})).items():
        if options.get('enabled', True) is False or options.get('skip'):
            continue
        # Lightweight seed mappings need no re-probe or metadata reconstruction.
        # Every component of a catalogued multipart source is already retained.
        if ident in existing_ids:
            continue
        try:
            ids = [str(v) for v in options.get('videoIds', [ident])]
            owner = str(options.get('owner', options.get('uploaderId', '')))
            if not ids or any(not re.fullmatch(r'\d{5,20}', v) for v in ids) or not re.fullmatch(r'\d{5,20}', owner):
                raise ValueError('Reviewed numeric video and uploader identities required')
            parsed = identity('', config, options)
            if not parsed:
                raise ValueError('Explicit original date/show/title mapping required')
            when, show, title = parsed
            runtime = length(options.get('duration', options.get('durationSeconds')))
            if runtime < minimum(when, show):
                raise ValueError('Reviewed full-programme runtime is missing or too short')
            source = {'type': 'okru', 'videoId': ids[0], 'owner': owner, 'label': 'OK.ru',
                      'duration': runtime, 'title': options.get('name', title), 'dateEvidence': 'Reviewed explicit broadcast mapping'}
            if len(ids) > 1:
                source['videoIds'] = ids
            rows.append(row(when, show, title, source, runtime, options.get('thumbnail', '')))
        except Exception as error:
            skipped.append('OK.ru mapping ' + ident + ': ' + str(error))
    return merge_rows([], rows), skipped


def refresh(root, previous, progress):
    root = Path(root)
    skipped = []
    try:
        config = json.loads((root / 'sources/nxt.json').read_bytes())
    except (OSError, ValueError) as error:
        return list(previous), 0, ['NXT source configuration: ' + str(error)]
    discovery = config.get('discovery', {})
    enabled = config.get('enabled', True) and config.get('discoveryEnabled', True)
    if isinstance(discovery, dict):
        enabled = enabled and discovery.get('enabled', True)
    elif discovery is False:
        enabled = False
    if not enabled:
        progress(95, 'NXT discovery disabled; existing sources retained')
        return list(previous), 0, []
    path = root / 'data/nxt-source-state.json'
    try:
        state = json.loads(path.read_bytes()) if path.exists() else {}
        if not isinstance(state, dict):
            raise ValueError('State must be an object')
    except (OSError, ValueError) as error:
        skipped.append('NXT source state: ' + str(error))
        state = {}
    collections = list(dict.fromkeys(str(v) for v in config.get('archiveCollections', []) if isinstance(v, str) and re.fullmatch(r'[A-Za-z0-9_.-]+', v)))
    failed = [v for v in state.get('archiveFailures', []) if v in collections]
    try:
        cursor = int(state.get('archiveCursor', 0)) % len(collections) if collections else 0
    except (ValueError, TypeError):
        cursor = 0
    selected = list(dict.fromkeys(failed[:3]))
    inspected = 0
    while collections and len(selected) < min(12, len(collections)) and inspected < len(collections):
        ident = collections[cursor]
        cursor = (cursor + 1) % len(collections)
        inspected += 1
        if ident not in selected:
            selected.append(ident)
    fresh = []
    failures = set(failed)
    for index, ident in enumerate(selected):
        progress(10 + int(40 * index / max(1, len(selected))), 'Scanning NXT Archive collection: ' + ident)
        try:
            metadata = fetch_json('https://archive.org/metadata/' + quote(ident, safe=''))
            if metadata.get('metadata', {}).get('identifier') != ident:
                raise ValueError('Collection identity missing or mismatched')
            fresh.extend(archive_rows(metadata, config))
            failures.discard(ident)
        except Exception as error:
            failures.add(ident)
            skipped.append('Archive collection ' + ident + ': ' + str(error))
    youtube, notes = discover_youtube(config, progress)
    fresh.extend(youtube)
    skipped.extend(notes)
    okru, notes = okru_rows(config, previous)
    fresh.extend(okru)
    skipped.extend(notes)
    if config.get('okruProfiles'):
        skipped.append('OK.ru profiles retained as reference links; reviewed video mappings used. Profile discovery was unavailable during the documented HTTP 204 barrier; no existing sources removed.')
    result = merge_rows(previous, fresh)
    old_ids = {r['id'] for r in previous}
    for value in result:
        if value['id'] not in old_ids:
            value['discoveredAt'] = int(time.time() * 1000)
    state.update(archiveCursor=cursor, archiveFailures=sorted(failures), checkedAt=int(time.time()), skipped=skipped[:50])
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(state, separators=(',', ':')), encoding='utf-8')
        temporary.replace(path)
    except OSError as error:
        skipped.append('NXT source state save: ' + str(error))
    progress(95, 'NXT source scan finished' + (f'; {len(skipped)} skipped (details retained)' if skipped else ''))
    return result, len({r['id'] for r in result} - old_ids), skipped

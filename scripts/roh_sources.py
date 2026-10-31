"""ROH identities and bounded discovery of new full broadcasts.

Research mappings are explicit in sources/roh.json. Discovery only admits dated,
full-length weeklies or clearly labelled official full shows; ambiguous events
remain in the scan report for review. No recording is deleted after a failed scan.
"""
import json
import re
from datetime import date
from urllib.parse import urlencode

CHANNEL = 'UC4_uDa6w1kOyzsikIzReCKQ'
AUTHOR = 'https://www.youtube.com/@ringofhonor'
EXCLUDE = re.compile(r'(?i)\b(?:abridged|partial|incomplete|highlights?|compilation|countdown|preview|review|podcast|scrum|trailer|promo|honorwave)\b|full[ ._-]*match|zero[ ._-]*hour|hour[ ._-]*one|pre[ ._-]*show|week[ ._-]*by[ ._-]*week|best[ ._-]*of')


def dated_title(value, order='DMY'):
    from library import broadcast_date
    value = value.replace('_', ' ')
    iso = re.search(r'(?<!\d)(20\d{2})([./-])(\d{2})\2(\d{2})(?!\d)', value)
    if iso:
        try:
            return date(int(iso[1]), int(iso[3]), int(iso[4])).isoformat()
        except ValueError:
            return None
    match = re.search(r'(?<!\d)(\d{1,2})[/.-](\d{1,2})[/.-](20\d{2}|\d{2})(?!\d)', value)
    if match:
        a, b, year = map(int, match.groups())
        if year < 100:
            year += 2000
        month, day = (a, b) if order == 'MDY' else (b, a)
        try:
            return date(year, month, day).isoformat()
        except ValueError:
            pass
    # Month-name dates are safe; do not combine an episode number or year suffix
    # with the adjacent numeric date (e.g. "Final Battle 2025 05-12-25").
    if re.search(r'(?i)\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\b', value):
        return broadcast_date(value, order=order)
    return None


def weekly_identity(title, filename='', metadata_date=''):
    """No date inference from an upload timestamp or episode-number arithmetic."""
    clean = re.sub(r'[._]+', ' ', title)
    if EXCLUDE.search(clean) or EXCLUDE.search(filename.replace('_', ' ')):
        return None
    if not re.search(r'(?i)\b(?:ROH|Ring of Honou?r)\b', clean):
        return None
    if re.search(r'(?i)honou?r\s*club|on My\s*AEW', clean):
        show = 'HonorClub'
    elif re.search(r'(?i)high impact tv', clean):
        show = 'High Impact TV'
    elif re.search(r'(?i)hd\s*net', clean):
        show = 'HDNet'
    elif re.search(r'(?i)YouTube|\bSpecial\b', clean):
        show = 'ROH TV'
    elif re.search(r'(?i)ROH\s+(?:Wrestling|TV)\s+(?:\d|Episode)', clean):
        show = 'ROH TV'
    else:
        return None
    # Source titles explicitly identify the broadcast; generic metadata dates can
    # be upload dates (one researched item even claims 2002 for a 2024 episode).
    when = dated_title(title) or dated_title(filename)
    if not when or not '2002-02-23' <= when <= date.today().isoformat():
        return None
    if show == 'HonorClub' and when < '2023-03-02':
        return None
    episode = re.search(r'(?i)(?:e[a-z]{1,9}|#|club)\s*(\d{1,3})(?!\d|[-/])\b', clean)
    label = show + (' #' + episode[1] if episode else '')
    return {'date': when, 'show': show, 'title': label}


def archive_mapping(identifier, metadata):
    from library import original_videos
    title = str(metadata.get('metadata', {}).get('title', ''))
    if metadata.get('is_dark') or metadata.get('metadata', {}).get('access-restricted-item'):
        return None
    overrides = {}
    for file in original_videos(metadata):
        identity = weekly_identity(title, file['name'])
        # Standalone parts and short recordings cannot prove a complete programme.
        if not identity or float(file.get('length') or 0) < 2300:
            continue
        if re.search(r'(?i)\b(?:part|disc|cd)[ ._-]*\d+', file['name']):
            continue
        overrides[file['name']] = identity
    if not overrides:
        return None
    return {'id': identifier, 'name': title, 'enabled': True, 'mappedOnly': True,
            'dateOrder': 'DMY', 'overrides': overrides}


def refresh_roh(library, config):
    from library import ROOT, fetch_metadata, public_request, scan_archive
    report = []
    known = {entry['id'] for entry in config['archive']['collections']}
    discovery = config['archive'].get('discovery', {})
    if discovery.get('enabled', False):
        for query in discovery.get('queries', []):
            try:
                # New uploads first; the historical seed is already mapped. A bounded
                # daily scan avoids fetching hundreds of unchanged item manifests.
                url = 'https://archive.org/advancedsearch.php?' + urlencode({
                    'q': query, 'output': 'json', 'rows': 100, 'page': 1,
                    'fl[]': 'identifier', 'sort[]': 'addeddate desc'})
                results = json.loads(public_request(url))['response']['docs']
                for row in results:
                    identifier = row['identifier']
                    if identifier in known:
                        continue
                    try:
                        metadata = fetch_metadata(identifier, ROOT / '.source-cache')
                        entry = archive_mapping(identifier, metadata)
                        if entry:
                            config['archive']['collections'].append(entry)
                            known.add(identifier)
                            report.append({'company': 'roh', **scan_archive(library, 'roh', entry, metadata, [])})
                        else:
                            report.append({'company': 'roh', 'source': identifier, 'status': 'skipped',
                                           'reason': 'No unambiguous dated full weekly recording'})
                    except Exception as error:
                        report.append({'company': 'roh', 'source': identifier, 'status': 'skipped', 'reason': str(error)})
            except Exception as error:
                report.append({'company': 'roh', 'source': 'Archive discovery', 'status': 'skipped', 'reason': str(error)})
    return report


def youtube_identity(title, events):
    """Official uploads use US dates. Never mistake FULL MATCH for FULL SHOW."""
    if EXCLUDE.search(title) or not re.search(r'(?i)full (?:show|event|episode|ppv)|TV.*Special.*Episode', title):
        return None
    when = dated_title(title, 'MDY')
    if re.search(r'(?i)\bTV\b|Special.*Episode', title):
        if not when:
            return None
        # Numbered modern ROH TV uploads are the same broadcasts as HonorClub.
        numbered = re.search(r'(?i)\bTV Episode\s+(\d+)', title)
        show = 'HonorClub' if numbered and when >= '2023-03-01' else 'ROH TV'
        return {'date': when, 'show': show, 'title': show}
    from library import key
    matches = [event for event in events if key(event['name']) in key(title)
               and ((when and event['date'] == when) or
                    (not when and event['date'][:4] in title))]
    if len(matches) == 1:
        return {'date': matches[0]['date'], 'show': 'PPV', 'title': matches[0]['name']}
    return None

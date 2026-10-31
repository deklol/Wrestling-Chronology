"""Identify complete AEW pre-shows without treating them as main PPVs."""
import json
import re
from datetime import datetime
from zoneinfo import ZoneInfo

CHANNEL = 'UCFN4JkGP_bVhAdBsoV9xftA'
PRESHOW = re.compile(r'(?i)\b(?:zero[ ._-]*hour|buy[ ._-]*in|pre[ ._-]*show)\b')


def candidate(title):
    if not PRESHOW.search(title):
        return False
    if re.search(r'(?i)ring of honor|\bROH\b|dynamite|rampage|highlights|full match|compilation', title):
        return False
    # A match mentioning a pre-show in its description is not the whole pre-show.
    return bool(re.search(r'(?i)\bAEW\b', title))


def identity(title, duration, metadata, events):
    if not candidate(title) or duration < 1200 or metadata.get('channelId') != CHANNEL:
        return None
    live = metadata.get('live', {})
    if live.get('isLiveNow'):
        return None
    timestamp = live.get('startTimestamp') or metadata.get('publishDate')
    if not timestamp:
        return None
    # Most AEW events are in North America; late UTC uploads belong to the prior evening.
    instant = datetime.fromisoformat(timestamp.replace('Z', '+00:00'))
    try:
        when = instant.astimezone(ZoneInfo('America/New_York')).date().isoformat()
    except Exception:
        # Minimal Windows Python lacks tzdata. Publication timestamps carry a local offset.
        when = str(metadata.get('publishDate') or timestamp)[:10]
    names = ('Double or Nothing', 'Forbidden Door', 'WrestleDream', 'Revolution',
             'Worlds End', 'Full Gear', 'All Out', 'All In', 'Dynasty', 'Fyter Fest', 'Fight for the Fallen')
    event = next((name for name in names if re.sub(r'\W', '', name).lower()
                  in re.sub(r'\W', '', title).lower()), None)
    if not event:
        return None
    matches = [entry for entry in events if entry.get('date') == when
               and re.sub(r'\W', '', event).lower() in re.sub(r'\W', '', entry.get('name', entry.get('title', ''))).lower()]
    if matches:
        event = matches[0].get('name', matches[0].get('title'))
    label = 'Zero Hour' if re.search(r'(?i)zero[ ._-]*hour', title) else 'Buy In'
    return {'date': when, 'show': 'PPV', 'title': event + ' — ' + label,
            'eventPart': 'preshow', 'eventOrder': -1}


def video_metadata(identifier, request):
    html = request('https://www.youtube.com/watch?v=' + identifier + '&hl=en')
    start = re.search(r'ytInitialPlayerResponse\s*=\s*', html)
    if not start:
        raise ValueError('YouTube broadcast metadata unavailable')
    player = json.JSONDecoder().raw_decode(html[start.end():])[0]
    micro = player.get('microformat', {}).get('playerMicroformatRenderer', {})
    return {'channelId': player.get('videoDetails', {}).get('channelId'),
            'publishDate': micro.get('publishDate'), 'live': micro.get('liveBroadcastDetails', {})}

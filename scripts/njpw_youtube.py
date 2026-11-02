"""Discover full broadcasts on the configured official NJPW channels."""
from datetime import datetime
import json
import re
from urllib.request import Request, urlopen

from njpw_sources import numeric_date, row


def walk(value, name):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == name:
                yield child
            yield from walk(child, name)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child, name)


def text(node):
    return node.get('simpleText', ''.join(r.get('text', '') for r in node.get('runs', [])))


def duration(value):
    try:
        total = 0
        for n in value.split(':'):
            total = total * 60 + int(n)
        return total
    except (ValueError, AttributeError):
        return 0


def video_row(video, config):
    title = video['title']
    ident = video['id']
    if re.search(r'only.*matches|preview|press conference|review|documentary|Lions Roar', title, re.I):
        return None
    known = config.get('youtubeBroadcasts', {}).get(ident)
    if known:
        when, show, name = known['date'], known['show'], known['title']
    else:
        number = re.search(r'STRONG\s+(?:Ep(?:isode)?[.\s-]*|#)?(\d+)', title, re.I)
        guide = config.get('strongEpisodes', {}).get(number[1]) if number else None
        full = re.search(r'FULL (?:EVENT|SHOW|EPISODE)|FREE EPISODE|過去大会フル公開|全編無料', title, re.I)
        if not full:
            return None
        if guide:
            when, show, name = guide['date'], 'NJPW STRONG', guide['title']
        else:
            when = numeric_date(title)
            if not when:
                match = re.search(r'(\d{1,2})\s+(January|February|March|April|May|June|July|August|September|October|November|December),?\s+(20\d{2})', title, re.I)
                if match:
                    try:
                        when = datetime.strptime(' '.join(match.groups()), '%d %B %Y').date().isoformat()
                    except ValueError:
                        pass
            if not when:
                return None
            show, name = 'PPV', re.sub(r'\[FULL EVENT\]|FREE FULL EVENT:|FULL EVENT', '', title, flags=re.I).strip(' :-|')
    length = duration(video.get('duration', ''))
    if length < 1200:
        return None
    source = {'type': 'youtube', 'videoId': ident, 'label': 'YouTube', 'duration': length,
              'language': 'ja' if video.get('channel') == 'NJPW' else 'en'}
    owner = next((channel for channel in config['youtubeChannels'] if channel['handle'] == video.get('channel')), None)
    if owner:
        source.update(channelId=owner['channelId'], authorUrl='https://www.youtube.com/@'+owner['handle'])
    return row(when, name, show, [source], 'https://i.ytimg.com/vi/' + ident + '/hqdefault.jpg', duration=length)


def discover(config, progress):
    found, notes = [], []
    for index, channel in enumerate(config['youtubeChannels']):
        progress(55 + index * 8, 'Scanning YouTube: ' + channel['name'])
        try:
            with urlopen(Request('https://www.youtube.com/@' + channel['handle'] + '/videos?hl=en&gl=GB',
                                 headers={'User-Agent': 'Mozilla/5.0', 'Accept-Language': 'en-GB,en;q=0.9'}), timeout=15) as response:
                raw = response.read(8 * 1024 * 1024).decode('utf8')
            match = re.search(r'(?:var\s+)?ytInitialData\s*=\s*', raw)
            if not match:
                raise ValueError('Public channel data unavailable')
            data = json.JSONDecoder().raw_decode(raw[match.end():])[0]
            meta = next(walk(data, 'channelMetadataRenderer'))
            if meta.get('externalId') != channel['channelId']:
                raise ValueError('Channel identity mismatch')
            videos = {}
            for renderer in walk(data, 'videoRenderer'):
                videos[renderer['videoId']] = {'id': renderer['videoId'], 'title': text(renderer.get('title', {})),
                    'duration': text(renderer.get('lengthText', {})), 'channel': channel['handle']}
            for renderer in walk(data, 'lockupViewModel'):
                ident = renderer.get('contentId', '')
                if len(ident) != 11 or 'VIDEO' not in renderer.get('contentType', ''):
                    continue
                title = renderer.get('metadata', {}).get('lockupMetadataViewModel', {}).get('title', {}).get('content', '')
                badges = list(walk(renderer.get('contentImage', {}), 'thumbnailBadgeViewModel'))
                length = next((b.get('text') for b in badges if re.fullmatch(r'[\d:]+', b.get('text', ''))), '')
                videos[ident] = {'id': ident, 'title': title, 'duration': length, 'channel': channel['handle']}
            for video in videos.values():
                parsed = video_row(video, config)
                if parsed:
                    found.append(parsed)
        except Exception as error:
            notes.append('YouTube ' + channel['name'] + ': ' + str(error))
    for playlist in config.get('youtubePlaylists', []):
        try:
            with urlopen(Request('https://www.youtube.com/playlist?list=' + playlist['id'] + '&hl=en&gl=GB',
                                 headers={'User-Agent': 'Mozilla/5.0'}), timeout=15) as response:
                raw = response.read(8 * 1024 * 1024).decode('utf8')
            match = re.search(r'(?:var\s+)?ytInitialData\s*=\s*', raw)
            if not match:
                raise ValueError('Public playlist data unavailable')
            data = json.JSONDecoder().raw_decode(raw[match.end():])[0]
            for renderer in walk(data, 'playlistVideoRenderer'):
                parsed = video_row({'id': renderer['videoId'], 'title': text(renderer.get('title', {})),
                    'duration': text(renderer.get('lengthText', {})), 'channel': 'NJPW'}, config)
                if parsed:
                    found.append(parsed)
        except Exception as error:
            notes.append('YouTube playlist ' + playlist['name'] + ': ' + str(error))
    return found, notes

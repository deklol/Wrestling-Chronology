"""Bounded public discovery; explicit mappings preserve identities between updates."""
from html.parser import HTMLParser
import json
import re
from event_identity import event_part, event_title

_pages = {}


class Page(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.owner = None
        self.metadata = []
        self.links = {}
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'a':
            href = attrs.get('href', '')
            match = re.search(r'/video/(\d{5,20})(?:\?|$)', href)
            if not match and 'video-card_n' in attrs.get('class', '').split():
                match = re.search(r'OK\.VideoPlayer\.openMovie\([\"\'](\d{5,20})[\"\']', attrs.get('onclick', ''))
            if match and attrs.get('title'):
                self.links[match[1]] = attrs['title']
            if 'movie_author_link' in attrs.get('class', ''):
                owner = re.search(r'/profile/(\d+)/', href)
                if owner:
                    self.owner = owner[1]
        if 'data-options' in attrs:
            try:
                meta = json.loads(attrs['data-options']).get('flashvars', {}).get('metadata')
                if isinstance(meta, str):
                    meta = json.loads(meta)
                if isinstance(meta, dict) and meta.get('provider') == 'UPLOADED_ODKL':
                    self.metadata.append(meta)
            except (ValueError, AttributeError):
                pass


def page(url):
    from library import public_request
    if not re.fullmatch(r'https://ok\.ru/(?:profile/\d+/video(?:/all|/c\d+)?|video/(?:c)?\d+)', url):
        raise ValueError('Invalid public OK.ru collection URL')
    if url not in _pages:
        _pages[url] = Page(public_request(url))
    return _pages[url]


def refresh_okru(library, company, config):
    from library import add_record, broadcast_date, programme, read, ROOT, key
    settings = config.get('okru', {})
    if not settings.get('enabled', False):
        return []
    known = {v['id'] for v in settings.get('videos', [])}
    known.update(source.get('videoId') for row in library.get('episodes', [])
                 for source in row.get('sources', []) if source.get('type') == 'okru')
    references = read(ROOT / 'catalogue/event-reference.json').get(company, [])
    report = []
    budget = 20
    for collection in settings.get('collections', []):
        if not collection.get('enabled', True):
            continue
        label = collection.get('name', collection['url'])
        added, skipped = 0, []
        try:
            candidates = page(collection['url']).links
            for identifier, listing_title in candidates.items():
                if identifier in known or budget <= 0:
                    continue
                # Mixed uploaders require a recognisable company/programme/event.
                events = [event for event in references if len(key(event.get('name', ''))) >= 6
                          and key(event['name']) in key(listing_title)
                          and str(event.get('year', event['date'][:4])) in listing_title]
                show = programme(listing_title)
                allowed = {'aew': {'Dynamite', 'Collision', 'Rampage', 'Dark', 'Dark: Elevation'},
                           'wcw': {'Nitro', 'Thunder', 'Worldwide'}, 'wwe': {'Raw', 'SmackDown', 'Heat'},
                           'ecw': {'Hardcore TV', 'ECW on TNN'}, 'tna': {'Impact'}, 'roh': {'HonorClub'},
                           'nxt': {'NXT', 'NXT UK', 'NXT Level Up'}}[company]
                if company == 'roh' and re.search(r'(?i)HonorClub|ROH[ .]E\d+', listing_title):
                    show = 'HonorClub'
                if show not in allowed and len(events) != 1:
                    continue
                budget -= 1
                try:
                    details = page('https://ok.ru/video/' + identifier)
                    if details.owner != collection['owner'] or not details.metadata:
                        raise ValueError('Public metadata or configured uploader unavailable')
                    metadata = details.metadata[0]
                    movie = metadata['movie']
                    title = movie['title']
                    if movie.get('isClip') or movie.get('isLive') or re.search(r'(?i)highlights|full.match|compilation|countdown|trailer|partial|incomplete|documentary|interview', title):
                        raise ValueError('Not an eligible full recording')
                    actual_show = programme(title)
                    if company == 'roh' and re.search(r'(?i)HonorClub|ROH[ .]E\d+', title):
                        actual_show = 'HonorClub'
                    events = [event for event in references if len(key(event.get('name', ''))) >= 6
                              and key(event['name']) in key(title)
                              and str(event.get('year', event['date'][:4])) in title]
                    if len(events) != 1 and (actual_show not in allowed or actual_show != show):
                        raise ValueError('Upload identity conflicts with listing; review required')
                    show = actual_show
                    duration = int(movie['duration'])
                    if not (metadata.get('hlsManifestUrl') or metadata.get('ondemandHls')):
                        raise ValueError('Public stream unavailable')
                    when = broadcast_date(title)
                    listing_date = broadcast_date(listing_title)
                    if when and listing_date and when != listing_date:
                        raise ValueError('Upload date conflicts with listing; review required')
                    part = event_part(title)
                    pre = part != 'main'
                    if pre and company != 'aew':
                        raise ValueError('Separate pre-show needs an explicit mapping')
                    if len(events) == 1:
                        if when and when != events[0]['date']:
                            raise ValueError('Recording date conflicts with event reference; review required')
                        show, when, canonical = 'PPV', events[0]['date'], events[0]['name']
                    else:
                        canonical = show
                    if not when or duration < (1200 if pre else 3600 if show == 'PPV' else 1800):
                        raise ValueError('Ambiguous date or insufficient runtime')
                    canonical = event_title(canonical, part, title)
                    source = {'type':'okru','videoId':identifier,'owner':details.owner,'title':title,
                              'duration':duration,'programme':show,'label':'OK.ru','thumbnail':movie.get('poster','')}
                    if pre:
                        source.update(eventPart=part,company=company)
                    if not add_record(library,company,when,show,canonical,source,thumbnail=movie.get('poster','')):
                        raise ValueError('Outside company date range')
                    row = next(r for r in library['episodes'] if any(s.get('videoId')==identifier and s['type']=='okru' for s in r['sources']))
                    settings.setdefault('videos',[]).append({'id':identifier,'owner':details.owner,'episodeId':row['id'],
                        'date':when,'show':show,'title':canonical,'name':title,'duration':duration,'enabled':True})
                    known.add(identifier)
                    added += 1
                except Exception as error:
                    skipped.append(listing_title + ': ' + str(error))
            report.append({'company':company,'source':'OK.ru · '+label,'status':'checked public recent uploads',
                           'new_recordings':added,'skipped':skipped})
        except Exception as error:
            report.append({'company':company,'source':'OK.ru · '+label,'status':'skipped','reason':str(error)})
    return report

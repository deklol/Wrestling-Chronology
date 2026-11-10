"""Loopback-only prototype. No credentials, database or media downloads."""
from pathlib import Path
import argparse
import hashlib
import html
from html.parser import HTMLParser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import shutil
import threading
from urllib.error import HTTPError
from urllib.parse import urlsplit, parse_qs, quote, urlencode, unquote
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
SLOTS = threading.BoundedSemaphore(2)
PROVIDERS = {'archive.org':'archive','youtube.com':'youtube','www.youtube.com':'youtube','m.youtube.com':'youtube','youtu.be':'youtube','dailymotion.com':'dailymotion','www.dailymotion.com':'dailymotion','dai.ly':'dailymotion','ok.ru':'okru','www.ok.ru':'okru'}

def get(url):
    with urlopen(Request(url, headers={'User-Agent':'Mozilla/5.0','Accept':'application/json,text/html'}), timeout=18) as response:
        body = response.read(12*1024*1024+1)
        if len(body)>12*1024*1024: raise ValueError('This source is too large for one preview. Try a smaller collection.')
        return body

def clean(text):
    return re.sub(r'\s+', ' ', html.unescape(re.sub('<[^>]*>', '', str(text or '')))).strip()[:500]

def video_entry(info, provider):
    ident = str(info.get('id',''))
    url = info.get('webpage_url') or info.get('url') or ''
    if provider=='youtube':url='https://www.youtube.com/watch?v='+ident
    elif provider=='dailymotion':url='https://www.dailymotion.com/video/'+ident
    elif provider=='okru':url='https://ok.ru/video/'+ident
    return {'id':provider+':'+ident,'provider':provider,'url':url,'title':clean(info.get('title')) or 'Untitled video','thumbnail':info.get('thumbnail') or (info.get('thumbnails') or [{}])[-1].get('url',''), 'duration':info.get('duration') or 0,'date':''}

def archive_item(identifier, offset=0):
    data=json.loads(get('https://archive.org/metadata/'+quote(identifier,safe='')))
    metadata=data.get('metadata',{})
    if not metadata: raise ValueError('This Archive item could not be found.')
    if metadata.get('mediatype')=='collection':
        return archive_search('collection:'+identifier,offset,clean(metadata.get('title')))
    files=[f for f in data.get('files',[]) if re.search(r'\.(mp4|m4v|webm|mkv|mov|avi|mpeg|mpg|ogv)$',f.get('name',''),re.I) and not f.get('private')]
    groups={}
    for f in files:
        # Derivatives and originals belonging to one recording are one choice.
        key=f.get('original') or f['name']
        groups.setdefault(key,[]).append(f)
    items=[]
    for original,variants in groups.items():
        chosen=min(variants,key=lambda f:(0 if f['name'].lower().endswith('.mp4') else 1,0 if f.get('source')=='derivative' else 1,int(f.get('size',0) or 0)))
        filename=chosen['name'];title=chosen.get('title') or Path(original).stem.replace('_',' ')
        try:duration=float(chosen.get('length',0))
        except (ValueError,TypeError):duration=0
        items.append({'id':'archive:'+identifier+':'+original,'provider':'archive','title':clean(title),'url':'https://archive.org/details/'+quote(identifier)+'/'+quote(original,safe='/'),'mediaUrl':'https://archive.org/download/'+quote(identifier)+'/'+quote(filename,safe='/'),'thumbnail':'https://archive.org/services/img/'+quote(identifier),'duration':duration,'date':''})
    return {'name':clean(metadata.get('title')) or identifier,'provider':'archive','items':items[offset:offset+60],'next':offset+60 if len(items)>offset+60 else None,'total':len(items)}

def archive_search(query,offset=0,name='Internet Archive collection'):
    params=urlencode({'q':query+' AND mediatype:movies','fl[]':['identifier','title','runtime'],'rows':40,'page':offset//40+1,'output':'json'},doseq=True)
    result=json.loads(get('https://archive.org/advancedsearch.php?'+params))['response']
    # Each result remains an item link; expand its files only when played/added.
    items=[{'id':'archive-item:'+d['identifier'],'provider':'archive','title':clean(d.get('title')) or d['identifier'],'url':'https://archive.org/details/'+quote(d['identifier']),'thumbnail':'https://archive.org/services/img/'+quote(d['identifier']),'duration':0,'date':'','expandable':True} for d in result['docs']]
    return {'name':name,'provider':'archive','items':items,'next':offset+40 if result['numFound']>offset+40 else None,'total':result['numFound']}

def discover(url,offset=0):
    parsed=urlsplit(url);provider=PROVIDERS.get(parsed.hostname)
    if parsed.scheme!='https' or not provider or parsed.username or parsed.password:raise ValueError('Paste a public Internet Archive, YouTube, Dailymotion or OK.ru link.')
    if provider=='archive':
        path=[unquote(x) for x in parsed.path.split('/') if x]
        if len(path)<2 or path[0] not in ('details','download'):raise ValueError('Use an Archive item, collection or uploader link.')
        identifier=path[1]
        if identifier.startswith('@'):
            # Use the public profile endpoint: a display handle need not match
            # the uploader email indexed by advanced search.
            params=urlencode({'page_type':'account_details','page_target':identifier,'page_elements':'["uploads"]','hits_per_page':40,'page':offset//40+1,'sort':'publicdate:desc','aggregations':'false'})
            data=json.loads(get('https://archive.org/services/search/beta/page_production/?'+params))
            hits=data['response']['body']['page_elements']['uploads']['hits']
            docs=[h['fields'] for h in hits['hits'] if h.get('fields',{}).get('mediatype') in ('movies','collection')]
            items=[{'id':'archive-item:'+d['identifier'],'provider':'archive','title':clean(d.get('title')) or d['identifier'],'url':'https://archive.org/details/'+quote(d['identifier']),'thumbnail':'https://archive.org/services/img/'+quote(d['identifier']),'duration':0,'date':'','expandable':True} for d in docs]
            return {'name':identifier[1:]+' - uploads','provider':'archive','items':items,'next':offset+40 if hits['total']>offset+40 else None,'total':hits['total'],'note':'Uploader results can contain whole Archive collections. Open a saved item to choose the recordings inside it.'}
        return archive_item(identifier,offset)
    video_id=None
    if provider=='youtube' and 'list' not in parse_qs(parsed.query):
        video_id=(parsed.path.strip('/') if parsed.hostname=='youtu.be' else (parse_qs(parsed.query).get('v') or [''])[0])
        endpoint='https://www.youtube.com/oembed?'+urlencode({'url':url,'format':'json'})
    elif provider=='dailymotion':
        match=re.search(r'(?:/video/|^/)([a-zA-Z0-9]+)',parsed.path)
        video_id=match[1] if match else None
        endpoint='https://www.dailymotion.com/services/oembed?'+urlencode({'url':url,'format':'json'})
    if video_id:
        info=json.loads(get(endpoint))
        entry=video_entry({'id':video_id,'title':info.get('title'),'thumbnail':info.get('thumbnail_url')},provider)
        return {'name':entry['title'],'provider':provider,'items':[entry],'next':None,'total':1}
    if provider=='okru' and (re.fullmatch(r'/(?:video|videoembed)/\d+',parsed.path) or re.fullmatch(r'/profile/\d+/video(?:/all|/c\d+)?',parsed.path)):
        single=bool(re.fullmatch(r'/(?:video|videoembed)/\d+',parsed.path))
        video_id=parsed.path.rsplit('/',1)[-1] if single else None
        class Metadata(HTMLParser):
            result=None
            def __init__(self):super().__init__();self.movies=[]
            def handle_starttag(self,tag,attrs):
                options=dict(attrs).get('data-options')
                if not options:return
                try:
                    meta=json.loads(options).get('flashvars',{}).get('metadata')
                    if isinstance(meta,str):meta=json.loads(meta)
                    if isinstance(meta,dict) and meta.get('movie'):
                        self.result=meta['movie'];self.movies.append(meta['movie'])
                except (ValueError,TypeError,AttributeError):pass
        page_source=get('https://ok.ru/videoembed/'+video_id if single else url).decode('utf-8','replace')
        parser=Metadata();parser.feed(page_source)
        if not single and parser.movies:
            entries={}
            for movie in parser.movies:
                match=re.search(r'/video/(\d+)',movie.get('link',''))
                ident=match[1] if match else str(movie.get('movieId') or movie.get('id') or '')
                if ident.isdigit():entries[ident]=video_entry({'id':ident,'title':movie.get('title'),'thumbnail':movie.get('poster'),'duration':movie.get('duration')},provider)
            items=list(entries.values())
            return {'name':'OK.ru uploader videos','provider':provider,'items':items[offset:offset+60],'next':offset+60 if len(items)>offset+60 else None,'total':len(items),'note':'These are the public videos visible on this uploader page. Specific albums can also be added using their own links.'}
        if not single:
            class Cards(HTMLParser):
                def __init__(self):super().__init__();self.items=[];self.current=None;self.capture=None
                def handle_starttag(self,tag,attrs):
                    a=dict(attrs);classes=a.get('class','').split()
                    if 'js-movie-card' in classes and a.get('data-id','').isdigit():
                        self.current={'id':a['data-id'],'title':'','thumbnail':''};self.items.append(self.current)
                    if self.current:
                        if tag=='img' and 'video-card_img' in classes:self.current['thumbnail']=a.get('src','');self.current['title']=a.get('alt','')
                        if 'video-card_n' in classes:self.capture=tag
                def handle_data(self,value):
                    if self.current and self.capture:self.current['title']+=value
                def handle_endtag(self,tag):
                    if self.capture==tag:self.capture=None
            cards=Cards();cards.feed(page_source)
            items=list({v['id']:video_entry(v,provider) for v in cards.items}.values())
            if items:return {'name':'OK.ru uploader videos','provider':provider,'items':items[offset:offset+60],'next':offset+60 if len(items)>offset+60 else None,'total':len(items),'note':'These are the public videos visible on this uploader page. Specific albums can also be added using their own links.'}
        if parser.result:
            movie=parser.result
            entry=video_entry({'id':video_id,'title':movie.get('title'),'thumbnail':movie.get('poster'),'duration':movie.get('duration')},provider)
            return {'name':entry['title'],'provider':provider,'items':[entry],'next':None,'total':1}
    # Flat playlist extraction reads metadata only, not the media files.
    import yt_dlp
    class Quiet:
        def debug(self,*a):pass
        def warning(self,*a):pass
        def error(self,*a):pass
    options={'quiet':True,'no_warnings':True,'logger':Quiet(),'skip_download':True,'extract_flat':'in_playlist','ignoreerrors':True,'socket_timeout':12,'retries':0,'extractor_retries':0,'playliststart':offset+1,'playlistend':offset+61,'lazy_playlist':True,'noplaylist':False,'cachedir':False,'extractor_args':{'youtube':{'skip':['dash','hls','translated_subs']}}}
    if shutil.which('node'):options['js_runtimes']={'node':{'path':shutil.which('node')}}
    with yt_dlp.YoutubeDL(options) as ydl:
        result=ydl.extract_info(url,download=False)
        if not result:raise ValueError('This source did not return any public videos. Check the link and try again.')
        if result.get('_type') in ('playlist','multi_video'):
            entries=[]
            for entry in result.get('entries',[]):
                if entry:entries.append(video_entry(entry,provider))
                if len(entries)>=61:break
            return {'name':clean(result.get('title')) or 'My collection','provider':provider,'items':entries[:60],'next':offset+60 if len(entries)>60 else None,'total':result.get('playlist_count')}
        return {'name':clean(result.get('title')) or 'My video','provider':provider,'items':[video_entry(result,provider)],'next':None,'total':1}

class Handler(SimpleHTTPRequestHandler):
    def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(ROOT/'site'),**kwargs)
    def log_message(self,*args):pass
    def json(self,status,data):
        body=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)
    def do_POST(self):
        if self.path!='/local-api/import':return self.json(404,{'error':'Not found'})
        if self.headers.get('Origin') not in (None,'http://127.0.0.1:'+str(self.server.server_port),'http://localhost:'+str(self.server.server_port)):
            return self.json(403,{'error':'Open the local preview to import a collection.'})
        if not SLOTS.acquire(False):return self.json(429,{'error':'Another import is running. Please wait a moment.'})
        try:
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<8192:raise ValueError('This link is too long.')
            body=json.loads(self.rfile.read(length));offset=body.get('offset',0)
            if type(offset) is not int or not 0<=offset<=50000:raise ValueError('Invalid page.')
            self.json(200,discover(str(body.get('url','')).strip(),offset))
        except Exception as error:
            message=str(error) if isinstance(error,ValueError) else 'Could not read this source just now. Check that it is public, then try again.'
            self.json(422,{'error':message})
        finally:SLOTS.release()
    def do_GET(self):
        path=urlsplit(self.path).path
        if path=='/local-api/status':return self.json(200,{'localOnly':True,'database':False})
        super().do_GET()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=8080);args=parser.parse_args()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
    print('My Library preview: http://127.0.0.1:'+str(args.port)+'/',flush=True)
    server.serve_forever()

"""Adapt reviewed NXT/NJPW discovery to the same checked Android data packs.

Discovery rules and dates live in sources/discovery. A provider failure retains
existing rows, identities and alternatives. No APK or private website is needed.
"""
from pathlib import Path
from urllib.parse import urlsplit, unquote
import copy,json,shutil

HANDLES={'UC9YHZGzVhgZJRhqYOoBQ_zA':'WWENXT','UCJ5v_MCY6GNUBTO8-D3XoAg':'WWE',
         'UCNSnSemXKDc9UEZ7m31wY9Q':'WWEVault','UC1lgJkpCx_0SMzsvrTCdxPw':'NJPWGLOBAL',
         'UCWHkw0DdTCtbMpzeUkqtGRA':'NJPW','UCM1wTUmm8lFq8NP3rWF4_Qg':'NJPWTAMASHII'}

def adapt(company,library,config=None):
    library=copy.deepcopy(library)
    config=copy.deepcopy(config or {'schemaVersion':1,'description':'Reviewed full-show sources. Discovery rules are in sources/discovery/'+company+'.json.',
        'youtube':{'enabled':True,'channelId':'UC9YHZGzVhgZJRhqYOoBQ_zA' if company=='nxt' else 'UC1lgJkpCx_0SMzsvrTCdxPw','playlists':[],'videos':[]},
        'archive':{'enabled':True,'collections':[]},'okru':{'enabled':True,'videos':[]},'dailymotion':{'enabled':True,'videos':[]}})
    archive={v['id']:v for v in config['archive']['collections']}
    youtube={v['id']:v for v in config['youtube'].get('videos',[])}
    okru={v['id']:v for v in config.get('okru',{}).get('videos',[])}
    daily={v['id']:v for v in config.get('dailymotion',{}).get('videos',[])}
    for row in library['episodes']:
        for source in row['sources']:
            source.setdefault('programme',row['show'])
            kind=source['type']
            if kind=='archive':
                for url in source.get('urls',[source.get('url')]):
                    parsed=urlsplit(url)
                    if parsed.hostname!='archive.org' or not parsed.path.startswith('/download/'):raise ValueError('Invalid Archive URL')
                    ident=unquote(parsed.path.split('/')[2])
                    archive.setdefault(ident,{'id':ident,'name':ident,'enabled':True,'frozen':True})
            elif kind=='youtube':
                ident=source['videoId'];previous=youtube.get(ident,{})
                owner=source.get('channelId',previous.get('channelId'))
                if not owner:raise ValueError('YouTube owner missing for '+ident)
                author=source.get('authorUrl',previous.get('authorUrl','https://www.youtube.com/@'+HANDLES.get(owner,'')))
                if author.endswith('/@'):raise ValueError('YouTube handle missing for '+ident)
                source.update(channelId=owner,direct=True)
                youtube[ident]={'id':ident,'channelId':owner,'authorUrl':author,'enabled':True,
                    'name':source.get('title',row['title']),'title':row['title'],'date':row['date'],
                    'show':row['show'],'duration':int(source.get('duration') or row['duration']), 'reviewedFullShow':True}
            elif kind in ('okru','dailymotion'):
                listing=okru if kind=='okru' else daily
                for ident in source.get('videoIds',[source['videoId']]):
                    listing[ident]={'id':ident,'owner':source['owner'],'enabled':True,'name':row['title'],
                        'title':row['title'],'date':row['date'],'show':row['show'],'episodeId':row['id'],
                        'duration':int(source.get('duration') or row['duration'])}
    config['archive']['collections']=list(archive.values())
    config['youtube']['videos']=list(youtube.values())
    config['okru']={'enabled':True,'videos':list(okru.values()),'collections':[]}
    config['dailymotion']={'enabled':True,'videos':list(daily.values())}
    return library,config

def refresh_curated(root,company,library,config):
    # Reuse the audited date/tournament parsers, keeping only small discovery state.
    import importlib
    module=importlib.import_module(company+'_sources')
    cache=Path(root)/'.source-cache'/'curated'
    (cache/'sources').mkdir(parents=True,exist_ok=True)
    (cache/'data').mkdir(exist_ok=True)
    shutil.copyfile(Path(root)/'sources/discovery'/f'{company}.json',cache/'sources'/f'{company}.json')
    rows,added,notes=module.refresh(cache,library['episodes'],lambda progress,message:None)
    # Refreshers may add recordings to existing episodes: attach identities from the
    # reviewed manifest before checking the entire pack, preserving previous sources.
    known={s['videoId']:s for row in library['episodes'] for s in row['sources'] if s['type']=='youtube'}
    accepted=[]
    for row in rows:
        checked=[]
        for source in row['sources']:
            if source['type']=='youtube' and not source.get('channelId'):
                previous=known.get(source['videoId'])
                if previous:source.update(channelId=previous['channelId'])
            if source['type']=='youtube' and (not source.get('channelId') or
                    (source['channelId'] not in HANDLES and not source.get('authorUrl'))):
                notes.append('YouTube '+source['videoId']+': uploader identity not verified; recording held for review')
                continue
            checked.append(source)
        if checked:
            accepted.append({**row,'sources':checked})
    updated,config=adapt(company,{**library,'episodes':accepted},config)
    return updated,config,[{'company':company,'source':'Reviewed discovery','status':'checked','new_recordings':added},
        *({'company':company,'source':'Discovery','status':'skipped','reason':note} for note in notes)]

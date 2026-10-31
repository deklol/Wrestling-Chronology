/* Reproducible, scoped Archive import. Metadata is cached; videos are not downloaded. */
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const cache = path.join(root, '.source-cache', 'scoped-archive-20261029');
fs.mkdirSync(cache, { recursive: true });
async function json(url) {
  for (let n = 0; n < 4; n++) {
    try { const r = await fetch(url, { signal: AbortSignal.timeout(60000) }); if (!r.ok) throw Error(r.status); return await r.json(); }
    catch (e) { if (n === 3) throw e; }
  }
}
async function discover() {
  const ids = new Set(['05-27-95-1-saturday-night']);
  for (const uploader of ['wrestlingpreservation@gmail.com', 'TheSquaredCircle1996@hotmail.com']) {
    const r = await json('https://archive.org/advancedsearch.php?' + new URLSearchParams({ q: `uploader:${uploader}`, 'fl[]': 'identifier', rows: '1000', output: 'json' }));
    for (const d of r.response.docs) {
      if (uploader.startsWith('TheSquared') || /^(wwfshows|wwfmnr|wwfsmackdown|wwfppv|wwemnr|wwesd|wwenxt|wwemainevent|wweecw|wweppv|wweshows|wcwwrestling|tnaimpact|tnaimpac|tnashows|tnaxplosion|tnappv|aew(dynamite|shows|collision|ppv)|ecwwrestling)/i.test(d.identifier)) ids.add(d.identifier);
    }
  }
  fs.writeFileSync(path.join(cache, 'items.json'), JSON.stringify([...ids].sort(), null, 2));
  let done = 0;
  const queue = [...ids];
  await Promise.all(Array.from({ length: 6 }, async () => {
    for (let id; (id = queue.shift()); ) {
      const file = path.join(cache, `${id}.json`);
      if (!fs.existsSync(file)) fs.writeFileSync(file, JSON.stringify(await json(`https://archive.org/metadata/${id}`)));
      if (++done % 20 === 0) console.log(`Metadata ${done}/${ids.size}`);
    }
  }));
  console.log(`Cached ${done} scoped items`);
}
if (process.argv.includes('--discover')) discover().catch(e => { console.error(e); process.exitCode = 1; });
module.exports = { root, cache };
if (process.argv.includes('--import')) importLibraries();

function importLibraries() {
 const {dates,norm,calendars,classify}=require('./archive-parser.cjs');
 const companies=['wwe','wcw','ecw','tna','aew'];
 const packs={}; const original={}; const rows={}; const refs=calendars();
 const report={date:'2026-10-29',scope:['https://archive.org/details/05-27-95-1-saturday-night','https://archive.org/details/@thesquaredcircle1996','https://archive.org/details/@WrestlingPreservation'],excludedShows:['Superstars','Saturday Morning Slam'],minimumDate:'1985-01-01',companies:{},pending:[],corrections:[]};
 const firetv=root;
 const baseline=path.join(cache,'firetv-baseline');fs.mkdirSync(baseline,{recursive:true});
 for(const co of companies){
  const file=path.join(root,'app','assets','libraries',`${co}.json`), backup=path.join(baseline,`${co}.json`);
  const nativeLibrary=path.join(firetv,'app/src/main/assets/libraries',`${co}.json`);
  if(fs.existsSync(nativeLibrary)&&!fs.existsSync(backup))fs.writeFileSync(backup,JSON.stringify({schemaVersion:1,company:co,revision:1793232000,catalogue:JSON.parse(fs.readFileSync(nativeLibrary)),sources:JSON.parse(fs.readFileSync(path.join(firetv,'sources',`${co}.json`))),retiredSources:[]}));
  const pack=co!=='aew'&&fs.existsSync(backup)?JSON.parse(fs.readFileSync(backup)): {schemaVersion:1,company:co,revision:1,catalogue:{schemaVersion:1,sourceValidation:5,episodes:[],eventCalendar:[],playlists:[],archiveItems:[]},sources:{schemaVersion:1,youtube:{enabled:false,channelId:'',videos:[],playlists:[]},archive:{enabled:true,collections:[]}},retiredSources:[]};
  packs[co]=pack;original[co]=pack.catalogue.episodes.length;rows[co]=new Map();
  report.companies[co]={before:original[co],added:0,alternativeSources:0,shows:{}};
  for(const e of pack.catalogue.episodes){
   const key=`${e.show==='Clash of the Champions'?'PPV':e.show}|${e.date}`;
   const old=rows[co].get(key);
   if(old){const seen=new Set(old.sources.map(s=>s.url||JSON.stringify(s)));for(const s of e.sources)if(!seen.has(s.url||JSON.stringify(s)))old.sources.push(s);}
   else rows[co].set(key,e);
   refs.push({company:co,...e,ref:'existing catalogue'});
  }
 }
 const tv={};
 const tvNames={WWE_205_Live:'205 Live',WWE_Raw:'Raw',WWE_SmackDown:'SmackDown',WWE_NXT:'NXT',WWE_Main_Event:'Main Event',AEW_Dark:'Dark',WWE_Saturday_Night_s_Main_Event:'Saturday Night’s Main Event',WWE_Cruiserweight_Classic:'Cruiserweight Classic',WWE_Mae_Young_Classic:'Mae Young Classic',WWE_NXT_UK:'NXT UK',WWE_NXT_Level_Up:'NXT Level Up',TNA_One_Night_Only:'One Night Only'};
 for(const [key,show]of Object.entries(tvNames)){
  const f=path.join(cache,`tv-${key}.json`);if(!fs.existsSync(f))continue;
  const p=JSON.parse(fs.readFileSync(f));
  if(/Highlights|Pre-Show|Elevation/.test(p.show.name))throw Error('Wrong TV reference '+p.show.name);
  tv[show]=p.episodes.filter(e=>e.airdate&&e.airdate<='2026-10-29');
  for(const e of tv[show])if(e.name&&e.airdate>='1985-01-01')refs.push({company:key.startsWith('AEW')?'aew':key.startsWith('TNA')?'tna':'wwe',show,title:e.name,date:e.airdate,year:+e.airdate.slice(0,4),ref:e.url});
 }
 const ids=JSON.parse(fs.readFileSync(path.join(cache,'items.json')));
 const candidates=[];
 for(const id of ids){
  const p=JSON.parse(fs.readFileSync(path.join(cache,`${id}.json`)));
  const thumbs=new Map();
  for(const image of p.files||[]) {
   const prefix=id+'.thumbs/';
   if(!image.name.startsWith(prefix)||!/_\d+\.jpg$/i.test(image.name))continue;
   const stem=image.name.slice(prefix.length).replace(/_\d+\.jpg$/i,'').replace(/\.ia$/i,'');
   const list=thumbs.get(stem)||[];list.push(image.name);thumbs.set(stem,list);
  }
  for(const f of p.files||[]){
   if(!/\.(mp4|m4v|webm|mkv)$/i.test(f.name))continue;
   const type=classify(id,f.name);if(!type)continue;
   const [co,rawShow]=type; const show=rawShow==='Saturday Night’s Main Event'?'Saturday Night':rawShow;
   const base=f.name.split('/').pop();
   const folderYear=+(f.name.match(/(?:^|\/)((?:19|20)\d{2})(?:\/|$)/)||[])[1];
   const yy=folderYear||+(base.match(/\b((?:19|20)\d{2})\b/)||[])[1]||+(id.match(/((?:19|20)\d{2})/)||[])[1];
   if(yy&&yy<1985)continue;
   const length=+f.length||0;
   const min=['Dark','Dark: Elevation','NXT Level Up'].includes(show)?300:['205 Live','NXT UK'].includes(show)?600:show==='PPV'?3600:1200;
   if(length&&length<min)continue;
   if(/\b(?:cd|disc|part|pt)[ ._-]*\d\b/i.test(base)&&! /WrestleMania|Stand.and.Deliver/i.test(base)) {report.pending.push({item:id,filename:f.name,reason:'Multipart file needs an explicit complete part set'});continue;}
   const ds=dates(base,folderYear);
   const images=thumbs.get(f.name.replace(/\.(mp4|mkv|m4v|webm)$/i,'').replace(/\.ia$/i,''))||[];
   const thumb=images.length?`https://archive.org/download/${encodeURIComponent(id)}/${images[Math.min(1,images.length-1)].split('/').map(encodeURIComponent).join('/')}`:'';
   const c={id,f,co,show,year:yy,dates:ds,base,thumbnail:thumb};
   if(ds.length===1)c.date=ds[0];
   if(['PPV','One Night Only','Twitch Specials'].includes(show)){
    const ns=norm(f.name);
    let matches=refs.filter(r=>r.company===co && (r.show===show||(show==='PPV'&&r.show==='NXT'&&/TakeOver|Arrival|Fatal|Evolution|Stand.and.Deliver/i.test(r.title))) && (!yy||r.year===yy) && norm(r.title).length>3 && ns.includes(norm(r.title)));
    const longest=Math.max(0,...matches.map(r=>norm(r.title).length));
    matches=matches.filter(r=>norm(r.title).length===longest);
    const unique=new Map(matches.map(r=>[r.date,r]));
    if(unique.size===1){const r=[...unique.values()][0];c.date=r.date;c.title=r.title;c.reference=r.ref;
     if(ds.length===1&&ds[0]!==c.date)report.corrections.push({item:id,filename:f.name,filenameDate:ds[0],date:c.date,reference:r.ref});
    }
    if(!c.title && c.date)c.title=cleanTitle(base);
   }
   const tvShow=show==='Saturday Night'&&co==='wwe'?'Saturday Night’s Main Event':show;
   if(tv[tvShow]){
    const ep=/\bS(\d{1,4})\s*E\s*(\d{1,3})\b/i.exec(base)||/\b(?:Episode|Ep)[ ._-]*(\d{1,3})\b/i.exec(base);
    if(ep){
     let found=[];
     if(ep.length===3){const season=+ep[1],number=+ep[2];found=tv[tvShow].filter(e=>e.number===number&&(season>=1985?+e.airdate.slice(0,4)===season:e.season===season)&&(!yy||+e.airdate.slice(0,4)===yy));}
     else found=tv[tvShow].filter(e=>e.number===+ep[1]&&(!yy||+e.airdate.slice(0,4)===yy));
     if(found.length===1){
      const target=found[0];
      const swapped=c.date&&c.date.slice(0,5)+c.date.slice(8,10)+'-'+c.date.slice(5,7);
      const knownDate=c.date&&tv[tvShow].some(e=>e.airdate===c.date);
      if(!c.date||!knownDate||swapped===target.airdate){
       if(c.date&&c.date!==target.airdate)report.corrections.push({item:id,filename:f.name,filenameDate:c.date,date:target.airdate,reference:target.url});
       c.date=target.airdate;c.reference=target.url;
      }
      if(c.date===target.airdate)c.episodeNumber=target.season*1000+target.number;
     }
    }
   }
   if(!c.date&&ds.length>1){report.pending.push({item:id,filename:f.name,reason:'Conflicting dates',dates:ds});continue;}
   candidates.push(c);
  }
 }
 // Identical file hashes give undated copies the identity of their dated counterpart.
 const hashes=new Map();for(const c of candidates)if(c.date&&c.f.md5&&(!['Raw','SmackDown'].includes(c.show)||tv[c.show]?.some(e=>e.airdate===c.date))){const k=c.co+'|'+c.show+'|'+c.f.md5;const set=hashes.get(k)||new Set();set.add(c.date);hashes.set(k,set);}
 const chosen=new Map();
 for(const c of candidates){
  if(c.date&&['Raw','SmackDown'].includes(c.show)&&tv[c.show]&&!tv[c.show].some(e=>e.airdate===c.date)){
   const known=hashes.get(c.co+'|'+c.show+'|'+c.f.md5);
   if(known?.size===1){c.date=[...known][0];c.reference='Identical MD5 to dated broadcast';}
   else{report.pending.push({item:c.id,filename:c.f.name,reason:'Filename date does not match saved broadcast schedule'});continue;}
  }
  if(!c.date){const set=hashes.get(c.co+'|'+c.show+'|'+c.f.md5);if(set?.size===1){c.date=[...set][0];c.reference='Identical MD5 to dated file';}}
  if(!c.date){report.pending.push({item:c.id,filename:c.f.name,show:c.show,reason:'No unambiguous broadcast date'});continue;}
  if(c.date<'1985-01-01'||c.date>'2026-10-29'||(c.co==='wcw'&&c.date>'2001-03-26')||(c.co==='aew'&&c.date<'2019-01-01'))continue;
  if(c.show==='Raw'&&/S00E/i.test(c.base))continue;
  if(c.year&&c.year!==+c.date.slice(0,4)&&!c.reference){report.pending.push({item:c.id,filename:c.f.name,reason:'Year conflicts with file date'});continue;}
  const ext=c.f.name.split('.').pop().toLowerCase();
  const key=[c.co,c.show,c.date,c.id,c.f.name.replace(/\.ia(?=\.mp4$)/i,'').replace(/\.(mp4|mkv|m4v|webm)$/i,'')].join('|');
  const rank=(ext==='mp4'?0:ext==='mkv'?3:1)+( /\.ia\.mp4$/i.test(c.f.name)?1:0);
  if(!chosen.has(key)||rank<chosen.get(key).rank)chosen.set(key,{...c,rank});
 }
 for(const c of chosen.values()){
  const {co,show,date,f,id}=c;const key=`${show}|${date}`;const pack=packs[co];
  const source={type:'archive',url:`https://archive.org/download/${encodeURIComponent(id)}/${f.name.split('/').map(encodeURIComponent).join('/')}`,filename:f.name,duration:+f.length||0,height:+f.height||0,bytes:+f.size||0,label:`Internet Archive · ${id}`,creator:id,md5:f.md5||'',dateEvidence:c.reference||'Explicit filename date',...(c.episodeNumber?{episodeNumber:c.episodeNumber}:{})};
  const e=rows[co].get(key);
  if(e){if(!e.thumbnail&&c.thumbnail)e.thumbnail=c.thumbnail;if(!e.sources.some(s=>s.url===source.url)){e.sources.push(source);report.companies[co].alternativeSources++;}}
  else{
   const title=c.title||show;
   rows[co].set(key,{id:`${co}:${date}:${show.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/-$/,'')}`,date,year:+date.slice(0,4),show,title,description:'',duration:source.duration,thumbnail:c.thumbnail,poster:'',sources:[source],discoveredAt:1793232000000});
   report.companies[co].added++;report.companies[co].shows[show]=(report.companies[co].shows[show]||0)+1;
  }
  const cols=pack.sources.archive.collections;
  let col=cols.find(x=>x.id===id);
  if(!col){col={id,name:id,enabled:true,dateOrder:'MDY',overrides:{}};cols.push(col);}
  col.overrides=col.overrides||{};
  col.overrides[f.name]={date,show,title:rows[co].get(key).title};
 }
 fs.mkdirSync(path.join(root,'reports'),{recursive:true});
 for(const co of companies){
  packs[co].catalogue.episodes=[...rows[co].values()].filter(e=>e.date>='1985-01-01').sort((a,b)=>a.date.localeCompare(b.date)||a.show.localeCompare(b.show));
  if(packs[co].sources.youtube?.videos)packs[co].sources.youtube.videos=packs[co].sources.youtube.videos.filter(v=>!v.date||v.date>='1985-01-01');
  // NXT UK sometimes broadcast two separate episodes on one date. A timeline row
  // plays both in sequence; they must never become interchangeable "other copies".
  for(const e of packs[co].catalogue.episodes)if(e.show==='NXT UK'){
   const parts=[...new Set(e.sources.map(s=>s.episodeNumber).filter(Boolean))].sort((a,b)=>a-b);
   if(parts.length<2)continue;
   const groups=new Map();for(const s of e.sources){const k=s.creator;const a=groups.get(k)||[];a.push(s);groups.set(k,a);}
   const combined=[];
   for(const [creator,sources]of groups){const selected=parts.map(n=>sources.find(s=>s.episodeNumber===n));if(selected.some(s=>!s))continue;
    combined.push({type:'archive',urls:selected.map(s=>s.url),filename:`NXT UK · ${e.date} · ${parts.length} episodes`,duration:selected.reduce((n,s)=>n+s.duration,0),bytes:selected.reduce((n,s)=>n+s.bytes,0),label:`Internet Archive · ${creator} · ${parts.length} episodes`,creator});
   }
   if(!combined.length){const selected=parts.map(n=>e.sources.find(s=>s.episodeNumber===n));combined.push({type:'archive',urls:selected.map(s=>s.url),filename:`NXT UK · ${e.date} · ${parts.length} episodes`,label:`Internet Archive · ${parts.length} episodes`});}
   e.sources=combined;e.title=`NXT UK · ${parts.length} episodes`;
  }
  packs[co].revision=Math.max(1793232000,packs[co].revision+1);
  report.companies[co].after=packs[co].catalogue.episodes.length;
  fs.mkdirSync(path.join(cache,'preview'),{recursive:true});
  fs.writeFileSync(path.join(cache,'preview',`${co}.json`),JSON.stringify(packs[co]));
  if(process.argv.includes('--write')) {
   fs.writeFileSync(path.join(firetv,'app/src/main/assets/libraries',`${co}.json`),JSON.stringify(packs[co].catalogue,null,2)+'\n');
   fs.writeFileSync(path.join(firetv,'sources',`${co}.json`),JSON.stringify(packs[co].sources,null,2)+'\n');
   fs.writeFileSync(path.join(firetv,'catalogue',`${co}.json`),JSON.stringify(packs[co],null,2)+'\n');
  }
 }
 fs.writeFileSync(path.join(root,'reports','archive-import.json'),JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify(report.companies,null,2));
 console.log('Pending file records:',report.pending.length,'Calendar corrections:',report.corrections.length);
}
function cleanTitle(base) {
 return base.replace(/\.(mp4|mkv|m4v|webm)$/i,'').replace(/\.ia$/i,'').replace(/^[\d.]+[ ._-]+/,'').replace(/\b(?:19|20)\d{2}[. -]\d{2}[. -]\d{2}\b/g,'').replace(/\b(?:2160|1080|720|576|480|360)[pi]\b[\s\S]*$/i,'').replace(/[._]/g,' ').replace(/\b(?:WW[EF]|AEW|TNA|IMPACT|NJPW|PPV)\b/gi,'').replace(/\b(?:19|20)\d{2}\b/g,'').replace(/^[ -]+|[ -]+$/g,'').replace(/\s+/g,' ').trim();
}

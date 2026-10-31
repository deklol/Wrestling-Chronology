const fs = require('node:fs');
const { root, cache } = require('./import-archive.cjs');
const TODAY = '2026-10-29';
const MONTHS = ['jan','feb','mar','apr','may','jun','jul','aug','sep','oct','nov','dec'];
function iso(y,m,d) {
 y=+y; m=+m; d=+d; if(y<100) y += y>=60?1900:2000;
 if(y<1985 || y>2026 || m<1 || m>12 || d<1 || d>31) return '';
 const s = `${y}-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')}`;
 return new Date(s+'T12:00:00Z').toISOString().slice(0,10)===s && s<=TODAY ? s : '';
}
function dates(text, year) {
 const out = new Set(); let m;
 const add = (y,mo,d)=>{const s=iso(y,mo,d); if(s)out.add(s);};
 const s = text.replace(/(\d)(st|nd|rd|th)\b/gi,'$1').replace(/[_,]/g,' ');
 for(const m of s.matchAll(/\b((?:19|20)\d{2})[. /-](\d{1,2})[. /-](\d{1,2})\b/g))add(m[1],m[2],m[3]);
 for(const m of s.matchAll(/\b(\d{1,2})[. /-](\d{1,2})[. /-]((?:19|20)\d{2})\b/g))add(m[3],+m[1]>12?m[2]:m[1],+m[1]>12?m[1]:m[2]);
 for(const m of s.matchAll(/\b(\d{1,2})[.-](\d{1,2})[.-](\d{2})\b/g))add(m[3],m[1],m[2]);
 const mon='(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)';
 for(const m of s.matchAll(new RegExp('\\b'+mon+'[. \\-]+(\\d{1,2})[. \\-]+((?:19|20)?\\d{2})\\b','gi')))add(m[3],MONTHS.indexOf(m[1].slice(0,3).toLowerCase())+1,m[2]);
 for(const m of s.matchAll(new RegExp('\\b(\\d{1,2})[. \\-]*'+mon+'[. \\-]*((?:19|20)?\\d{2})\\b','gi')))add(m[3],MONTHS.indexOf(m[2].slice(0,3).toLowerCase())+1,m[1]);
 if(!out.size && year) {
  for(const m of s.matchAll(new RegExp('\\b'+mon+'[. \\-]+(\\d{1,2})\\b','gi')))add(year,MONTHS.indexOf(m[1].slice(0,3).toLowerCase())+1,m[2]);
  // Month/day-only filenames are supported only inside an explicit year folder.
  if(!out.size && /^\d{1,2}[.-]\d{1,2}\s/.test(s)) {m=/^(\d{1,2})[.-](\d{1,2})/.exec(s); add(year,m[1],m[2]);}
 }
 return [...out];
}
const plain = s=>s.replace(/<sup\b[\s\S]*?<\/sup>/gi,'').replace(/<[^>]+>/g,' ').replace(/&#0*39;|&apos;/g,"'").replace(/&amp;/g,'&').replace(/&nbsp;|&#160;/g,' ').replace(/&quot;/g,'"').replace(/\s+/g,' ').trim();
function norm(s) {
 const romans={i:1,ii:2,iii:3,iv:4,v:5,vi:6,vii:7,viii:8,ix:9,x:10,xi:11,xii:12,xiii:13,xiv:14,xv:15,xvi:16,xvii:17,xviii:18,xix:19,xx:20,xxi:21,xxii:22,xxiii:23,xxiv:24,xxv:25,xxvi:26,xxvii:27,xxviii:28,xxix:29,xxx:30,xxxi:31,xxxii:32,xxxiii:33,xxxiv:34,xxxv:35};
 return s.toLowerCase().replace(/\bone[ ._-]+night[ ._-]+only\b/g,' ').replace(/\bS\d+\s*E\s*\d+\b/gi,' ').replace(/\b(?:ww[ef]|aew|tna|nwa|impact|njpw|nxt|takeover|ono|ppv|ple|wrestling)\b/g,' ').replace(/\b(?:19|20)\d{2}\b/g,' ').replace(/\b[ivx]+\b/g,m=>romans[m]||m).replace(/\band\b/g,' ').replace(/[^a-z0-9]/g,'').replace(/wrestlemania1$/,'wrestlemania');
}
function calendars() {
 const all=[];
 for(const co of ['wwe','tna','aew','nxt']) {
  const file=`${cache}/calendar-${co}.html`; if(!fs.existsSync(file))continue;
  const html=fs.readFileSync(file,'utf8'); let year=0;
  for(const m of html.matchAll(/<h[234]\b[^>]*>[\s\S]*?<\/h[234]>|<tr\b[^>]*>[\s\S]*?<\/tr>/gi)) {
   if(/^<h/i.test(m[0])) {const y=/^((?:19|20)\d{2})\b/.exec(plain(m[0])); if(y)year=+y[1]; continue;}
   const cells=[...m[0].matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)].map(c=>plain(c[1]));
   const di=cells.findIndex(c=>/^(?:January|February|March|April|May|June|July|August|September|October|November|December) \d/.test(c));
   if(di<0)continue;
   const ei = co==='aew'?0:co==='nxt'?di-1:di+1;
   const title=cells[ei]; if(!title || ei===di)continue;
   const y=+(cells[di].match(/\b(?:19|20)\d{2}\b/)||[])[0]||year;
   const ds=dates(cells[di],y);
   for(const d of ds) all.push({company:co==='nxt'?'wwe':co,show:'PPV',title:title.replace(/\[.*?\]/g,'').trim(),date:d,year:+d.slice(0,4),ref:`https://en.wikipedia.org/wiki/${co==='nxt'?'NXT_TakeOver':co==='wwe'?'List_of_WWE_pay-per-view_and_livestreaming_supercards':co==='tna'?'List_of_TNA_pay-per-view_and_livestreaming_events':'List_of_All_Elite_Wrestling_pay-per-view_events'}`});
  }
 }
 return all;
}
const BAD = /\b(?:documentar(?:y|ies)|docs?|dvd\d?|video.ts|vts.\d|compilations?|best[ ._-]+of|greatest|highlights?|count[ ._-]*down|pre[ ._-]*shows?|pregame|post[ ._-]*show|after[ ._-]*show|kickoff|kick[ ._-]*off|zero[ ._-]*hour|buy[ ._-]*in|free[ ._-]*for[ ._-]*all|red[ ._-]*carpet|media[ ._-]*scrum|press[ ._-]*conference|preview|trailer|sample|clips?|full[ ._-]*match|dark[ ._-]*match|match[ ._-]*packs?|extras|recap|review|incomplete|partial|promos?|home[ ._-]*video|house[ ._-]*shows?|hidden[ ._-]*gems|anarchy[ ._-]*in[ ._-]*the[ ._-]*arena[ ._-]*pack)\b/i;
function classify(id, filename) {
 const s=filename.replace(/[._]/g,' '); const base=s.split('/').pop();
 if(/Weekend.*Pack|\/Pack\//i.test(filename)) return null;
 if(BAD.test(filename.replace(/_/g,' ')) || /\b(?:Superstars|Saturday Morning Slam|Afterburn|Excess|Blast Off|Vintage Collection|The Bump|Hall Of Fame|All Access|Outside the Ring|The Road To|Stocking Stuffer|Kill Tony)\b/i.test(s))return null;
 if(id==='05-27-95-1-saturday-night')return ['wcw','Saturday Night'];
 if(id==='wcwwrestling') {
  for(const [re,show]of [[/Monday Nitro/i,'Nitro'],[/Thunder/i,'Thunder'],[/Power Hour/i,'Power Hour'],[/Main Event/i,'Main Event'],[/Saturday Night|World Championship Wrestling/i,'Saturday Night'],[/Clash of Champions|WCW PPV/i,'PPV']])if(re.test(s))return ['wcw',show];
  return null;
 }
 if(id==='ecwwrestling')return /Hardcore TV/i.test(s)?['ecw','Hardcore TV']:/ECW on TNN/i.test(s)?['ecw','ECW on TNN']:null;
 if(id==='wweecw'||/^(?:t?oobeRWCE)/i.test(id))return ['wwe','ECW'];
 if(/^wwfshows/i.test(id)) {
  for(const [re,show]of [[/Action Zone/i,'Action Zone'],[/All American/i,'All American Wrestling'],[/All[ -]Star Wrestling/i,'All Star Wrestling'],[/Championship Wrestling/i,'Championship Wrestling'],[/Prime Time Wrestling/i,'Prime Time Wrestling'],[/Saturday Night.?s Main Event/i,"Saturday Night’s Main Event"],[/Shotgun Saturday Night/i,'Shotgun Saturday Night'],[/Sunday Night Heat/i,'Heat'],[/Wrestling Challenge/i,'Wrestling Challenge'],[/Wrestling at the Chase/i,'Wrestling at the Chase'],[/Spotlight/i,'Wrestling Spotlight'],[/Livewire/i,'Livewire'],[/\bMania\b/i,'Mania'],[/\bMetal\b/i,'Metal']])if(re.test(s))return ['wwe',show];
  return null;
 }
 if(/^wweshows/i.test(id)) {
  for(const [re,show]of [[/205 Live/i,'205 Live'],[/Velocity/i,'Velocity'],[/Sunday Night Heat/i,'Heat'],[/\bEvolve\b/i,'Evolve'],[/Saturday Night.?s Main Event/i,"Saturday Night’s Main Event"],[/Cruiserweight Classic/i,'Cruiserweight Classic'],[/Mae Young Classic/i,'Mae Young Classic']])if(re.test(s))return ['wwe',show];
  return null;
 }
 if(/^aew/i.test(id)) {
  if(/(?:^|\/)PPV\//i.test(filename)||/^aewppv/.test(id))return ['aew',/Fyter Fest.*2025/.test(s)?'Dynamite':'PPV'];
  for(const [re,show]of [[/Dark Elevation/i,'Dark: Elevation'],[/\bDark\b/i,'Dark'],[/Battle of the Belts/i,'Battle of the Belts'],[/Deadly Draw/i,'The Deadly Draw'],[/Collision/i,'Collision'],[/Rampage/i,'Rampage'],[/Dynamite/i,'Dynamite']])if(re.test(s))return ['aew',show];
  if(/^aewdynamite/i.test(id))return ['aew','Dynamite'];
  return null;
 }
 if(/^tna/i.test(id)) {
  if(/^tnappv/.test(id))return ['tna','PPV'];
  if(/^tnaxplosion/.test(id))return ['tna','Xplosion'];
  if(/^tnaimpac/.test(id))return ['tna','Impact'];
  if(/^NWA-TNA\//i.test(filename))return ['tna','Weekly PPV'];
  if(/^One Night Only\//i.test(filename)&&!/Amped Anthology/i.test(s))return ['tna','One Night Only'];
  if(/^Twitch Specials\//i.test(filename))return ['tna','Twitch Specials'];
  return null;
 }
 if(/^VPPEWW|^wwfppv|^wweppv|^vppeww|srevoekaTTXN/i.test(id)) {
  if(/The Big Event|UK Rampage|Global Warming|Royal Rumble.*1988|1988.*Royal Rumble/i.test(s))return null;
  if(/Spectacular|Showdown|March to Wrestlemania|Prime Time|Sunday Night Slam|Face to Face|All Day Long|Weekly Shows|WWE World|Tailgate|\bvs\b|\bversus\b/i.test(s))return null;
  return ['wwe','PPV'];
 }
 if(/^KUTXN/i.test(id)||/NXT UK/i.test(s))return ['wwe','NXT UK'];
 if(/puleveltxn/i.test(id)||/NXT Level Up/i.test(s))return ['wwe','NXT Level Up'];
 if(/^TXN|^wwenxt/i.test(id))return ['wwe',/TakeOver|Stand and Deliver|Vengeance Day|No Mercy|Heatwave|Battleground/i.test(s)?'PPV':'NXT'];
 if(/^eviL502/i.test(id))return ['wwe','205 Live'];
 if(/^t?nevEniaM|^wwemainevent/i.test(id))return ['wwe','Main Event'];
 if(/^nwoDkcamS|^wwesd|^wwfsmackdown/i.test(id))return ['wwe','SmackDown'];
 if(/^war|^wwemnr|^wwfmnr/i.test(id))return ['wwe','Raw'];
 return null;
}
module.exports={dates,iso,norm,calendars,classify,BAD};

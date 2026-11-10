const monthNames={jan:1,feb:2,mar:3,apr:4,may:5,jun:6,jul:7,aug:8,sep:9,oct:10,nov:11,dec:12};
function iso(year,month,day){const d=new Date(Date.UTC(Number(year),Number(month)-1,Number(day)));return d.getUTCFullYear()===Number(year)&&d.getUTCMonth()+1===Number(month)&&d.getUTCDate()===Number(day)?`${year}-${String(month).padStart(2,'0')}-${String(day).padStart(2,'0')}`:'';}
export function parseVideo(item, context='') {
  const originalTitle=item.originalTitle||item.title||'', original=originalTitle.replace(/\.(?:mp4|mkv|webm|avi|mov|m4v|mpeg|mpg|ts|ogv)$/i,'');
  const text=original.replace(/[_.]/g,' ').replace(/\s+/g,' ').trim();
  let date=item.date||'', dateMatch='', ambiguous=false;
  let match=text.match(/\b((?:19|20)\d{2})[ /-]+(\d{1,2})[ /-]+(\d{1,2})\b/);
  if(match){date=date||iso(match[1],match[2],match[3]);dateMatch=date?match[0]:'';}
  if(!date){match=text.match(/\b(\d{1,2})[ /-]+(\d{1,2})[ /-]+((?:19|20)\d{2})\b/);if(match){if(+match[1]>12&&+match[2]<=12)date=iso(match[3],match[2],match[1]);else if(+match[2]>12&&+match[1]<=12)date=iso(match[3],match[1],match[2]);else if(+match[1]===+match[2])date=iso(match[3],match[1],match[2]);else ambiguous=true;if(date)dateMatch=match[0];}}
  if(!date){match=text.match(/\b(\d{1,2})(?:st|nd|rd|th)?\s+(Jan\w*|Feb\w*|Mar\w*|Apr\w*|May|Jun\w*|Jul\w*|Aug\w*|Sep\w*|Oct\w*|Nov\w*|Dec\w*)[,]?\s+((?:19|20)\d{2})\b/i);if(match){date=iso(match[3],monthNames[match[2].slice(0,3).toLowerCase()],match[1]);dateMatch=date?match[0]:'';}}
  if(!date){match=text.match(/\b(Jan\w*|Feb\w*|Mar\w*|Apr\w*|May|Jun\w*|Jul\w*|Aug\w*|Sep\w*|Oct\w*|Nov\w*|Dec\w*)\s+(\d{1,2})(?:st|nd|rd|th)?[,]?\s+((?:19|20)\d{2})\b/i);if(match){date=iso(match[3],monthNames[match[1].slice(0,3).toLowerCase()],match[2]);dateMatch=date?match[0]:'';}}
  const company=item.company||'',show=item.show||'';
  let kind=item.kind||'Video';
  if(/\bdocumentary\b/i.test(text))kind='Documentary';
  let season=Number(item.season)||0,episode=Number(item.episode)||0;
  match=text.match(/\bS(\d{1,2})\s*E(\d{1,3})\b/i);
  if(match){season=+match[1];episode=+match[2];}
  else{match=text.match(/\bseason\s*(\d{1,2})\b/i);if(match)season=+match[1];match=text.match(/\b(?:episode|ep)\s*#?\s*(\d{1,3})\b/i);if(match)episode=+match[1];}
  const title=text.replace(dateMatch||/$(?!)/,'').replace(/\b(?:\d{3,4}[pi]|h\s*26[45]|x26[45]|hevc|avc|aac|ac3)\b/gi,'').replace(/[()[\]]/g,'').replace(/\s+/g,' ').replace(/^[\s-]+|[\s-]+$/g,'').trim();
  return {...item,originalTitle,title:title||originalTitle,company,show,kind,date,year:date?Number(date.slice(0,4)):Number(text.match(/\b(?:19|20)\d{2}\b/)?.[0])||0,season,episode,dateAmbiguous:ambiguous&&!date};
}
export function parsedSummary(item){return [item.company,item.show&&item.show!==item.title?item.show:'',item.date?new Date(item.date+'T12:00:00').toLocaleDateString('en-GB',{day:'numeric',month:'short',year:'numeric'}):item.year?String(item.year):'',item.dateAmbiguous?'Check date':''].filter(Boolean).join(' · ');}

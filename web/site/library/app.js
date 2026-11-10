import * as store from './store.js';
import {localThumbnail} from './thumbnails.js';
import {DEFAULT_CATEGORIES,keyOf,categoryFor,makeCompany,categoryId} from './folders.js';
import {parseVideo,parsedSummary} from './parse.js';

const companyChoices=saved=>saved.map(company=>({name:company.name,value:company.id,thumbnail:company.thumbnail||'',record:company}));
const labels={archive:'Internet Archive',youtube:'YouTube',dailymotion:'Dailymotion',okru:'OK.ru',local:'Local videos'};
const paths={link:'M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-2 2M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l2-2',folder:'M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 8h18',file:'M14 2H5v20h14V7zM14 2v6h5M9 12l5 3-5 3z',lock:'M6 10V7a6 6 0 0 1 12 0v3M4 10h16v12H4zM12 15v3',device:'M3 3h18v14H3zM8 21h8M12 17v4',play:'m8 5 11 7-11 7z',back:'m15 5-7 7 7 7M8 12h13',plus:'M12 4v16M4 12h16'};
const svg=(name,cls='')=>`<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" stroke-linecap="round"><path d="${paths[name]||paths.file}"/></svg>`;
const imageUrl=value=>{if(typeof value!=='string')return '';if(/^data:image\/(?:webp|png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(value)&&value.length<150000)return value;if(/^\/assets\/[a-z0-9_.-]+$/.test(value))return value;return safeUrl(value);};
const icon=name=>`<span class="pl-icon">${svg(name)}</span>`;
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const safeUrl=value=>{try{const u=new URL(value);return u.protocol==='https:'?u.href:'';}catch{return '';}};
const time=n=>{n=Math.floor(Number(n)||0);return n>=3600?`${Math.floor(n/3600)}:${String(Math.floor(n%3600/60)).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`:`${Math.floor(n/60)}:${String(n%60).padStart(2,'0')}`;};
const size=n=>n>1024**3?`${(n/1024**3).toFixed(1)} GB`:`${Math.max(1,Math.round(n/1024**2))} MB`;
const fileKey=file=>'local:'+file.name+':'+file.size+':'+file.lastModified;
const sameVideo=(a,b)=>a.id===b.id||(a.provider==='local'&&b.provider==='local'&&a.path?.split('/').pop()===b.path?.split('/').pop()&&a.size===b.size&&a.modified===b.modified);
const files=new Map();
let categories=[],folder=null;
let collections=[],root,opened=null,query='',sourceFilter='all',order='original',modal,playerDialog,activeVideo,playerUrl,lastFocus,importAbort,saveProgressAt=0,mountId=0;
const $=(s,scope=root)=>scope?.querySelector(s);
const button=(text,action,cls='',disabled=false)=>`<button type="button" class="${cls}" data-pl="${action}" ${disabled?'disabled':''}>${text}</button>`;
const addOptions=()=>`<div class="pl-add-options">${[['link','Add a website link','A video, playlist, collection or uploader'],['file','Choose video files','Pick one or more videos on this device'],['folder','Choose a folder','Add the videos inside a folder']].map(([type,title,description])=>`<button type="button" class="pl-add-option" data-pl="add-${type}" aria-label="${title}">${icon(type)}<span><strong>${title}</strong><small>${description}</small></span></button>`).join('')}</div>`;
function notify(text){const box=document.querySelector('#notice');box.textContent=text;box.hidden=false;clearTimeout(notify.timer);notify.timer=setTimeout(()=>box.hidden=true,5000);}
async function reload(){
  const saved=await Promise.all([store.all(),store.categories()]);
  collections=saved[0].sort((a,b)=>b.updated-a.updated);categories=saved[1];
  const changed=[],added=[];
  for(const company of collections){
    if(company.foldersInitialized)continue;
    const defaults=DEFAULT_CATEGORIES.map(name=>({id:company.id+':'+keyOf(name),name,key:keyOf(name),companyId:company.id,created:Date.now()}));
    for(const category of defaults)if(!categories.some(c=>c.id===category.id))added.push(category);
    company.items=company.items.map(item=>({...item,company:company.name,category:item.category||categoryId([...categories,...defaults],company.id,categoryFor(item))}));
    company.foldersInitialized=true;changed.push(company);
  }
  if(changed.length){await store.saveMany(changed,added);categories.push(...added);}
  if(folder&&folder!=='all'&&!categories.some(c=>c.id===folder))folder=null;
}
function companyCategories(id=opened){return categories.filter(c=>c.companyId===id).sort((a,b)=>{const x=DEFAULT_CATEGORIES.map(keyOf).indexOf(a.key),y=DEFAULT_CATEGORIES.map(keyOf).indexOf(b.key);return (x<0?99:x)-(y<0?99:y)||a.name.localeCompare(b.name);});}
function current(){return collections.find(c=>c.id===opened);}
function categoryOptions(selected='',companyId=opened){return `<option value="">Uncategorised</option>${companyCategories(companyId).map(c=>`<option value="${esc(c.id)}" ${c.id===selected?'selected':''}>${esc(c.name)}</option>`).join('')}`;}
function sourceName(collection){const types=[...new Set(collection.items.map(i=>i.provider))];return types.length===1?labels[types[0]]:'Mixed sources';}
function thumb(item){const src=imageUrl(item?.thumbnail);return src?`<img src="${esc(src)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.hidden=true">`:icon(item?.provider==='local'?'file':'play');}
function tools(){return `<div class="pl-storage-tools">${button('Back up my library','backup')}${button('Restore a backup','restore')}<span>Saved on this device. Your collections are not uploaded.</span></div>`;}
function home(){
  const visible=collections.filter(c=>(sourceFilter==='all'||c.items.some(i=>i.provider===sourceFilter))&&(c.name+' '+c.items.map(i=>i.title).join(' ')).toLowerCase().includes(query.toLowerCase()));
  return `<div class="pl-heading"><div><span class="pl-local">${svg('device')} YOUR PERSONAL COLLECTION</span><h1>My Library</h1><p>Your companies, your categories, your videos.</p></div><div class="pl-actions">${button('Create company','new-empty')}${button('Add videos','add','primary')}</div></div>${addOptions()}
  ${collections.length?`<div class="pl-toolbar"><div><h2>Your companies</h2><span class="pl-count">${collections.length} ${collections.length===1?'company':'companies'} &middot; ${collections.reduce((n,c)=>n+c.items.length,0)} videos</span></div><div class="pl-actions"><input id="pl-search" type="search" placeholder="Search companies or videos" aria-label="Search your companies" value="${esc(query)}"><select id="pl-source" aria-label="Filter by source"><option value="all">All sources</option>${Object.entries(labels).map(([key,label])=>`<option value="${key}" ${key===sourceFilter?'selected':''}>${label}</option>`).join('')}</select></div></div><div class="pl-grid">${visible.map(collectionCard).join('')}</div>${visible.length?'':'<p class="pl-empty-search">No companies match your search.</p>'}`:
  `<section class="pl-empty"><div><h2>Make room for your video collection</h2><p>Create a company folder, or add your first videos. Each company gets Weeklies, Specials, PPVs, Matches, Pre-Shows, DVDs and Documentaries.</p><span class="pl-local">${svg('lock')} Only saved in this browser, on this device.</span></div><div class="pl-empty-art">${svg('folder')}</div></section>`}${tools()}`;
}
function collectionCard(c){
  const first=c.thumbnail?{thumbnail:c.thumbnail}:c.items.find(i=>i.thumbnail)||c.items[0],watched=c.items.filter(i=>i.watched).length;
  return `<article class="pl-collection"><div class="pl-cover ${c.thumbnail?'pl-logo-cover':''}">${thumb(first)}<span>${icon('folder')}${companyCategories(c.id).length} categories</span></div><div class="pl-collection-body"><h3>${esc(c.name)}</h3><p>${c.items.length} videos${watched?' &middot; '+watched+' watched':''}</p><div class="pl-actions"><button type="button" class="primary" data-pl="open" data-id="${esc(c.id)}">Open company</button><button type="button" data-pl="manage" data-id="${esc(c.id)}" aria-label="Manage ${esc(c.name)}">Manage</button></div></div></article>`;
}
function visibleItems(c){let items=c.items.filter(i=>(folder===null||folder==='all'||(i.category||'')===folder)&&i.title.toLowerCase().includes(query.toLowerCase())&&(!filters.company||i.company===filters.company)&&(!filters.show||i.show===filters.show)&&(!filters.year||String(i.year)===filters.year));if(order==='name')items.sort((a,b)=>a.title.localeCompare(b.title,undefined,{numeric:true}));if(order==='date')items.sort((a,b)=>(a.date||'9999').localeCompare(b.date||'9999')||a.title.localeCompare(b.title,undefined,{numeric:true}));if(order==='unwatched')items=items.filter(i=>!i.watched);return items;}
function detail(c){
  const own=companyCategories(c.id),category=own.find(cat=>cat.id===folder);
  const back=folder===null?button('&#8592; My Library','back','pl-back'):button('&#8592; '+esc(c.name)+' categories','back-company','pl-back');
  const title=folder===null?c.name:folder==='all'?'All videos':folder===''?'Uncategorised':category?.name||c.name;
  const actions=folder===null?`${button('Manage categories','categories')}${button('Manage company','manage-current')}`:button('Filter shows','filter');
  const header=`${back}<div class="pl-heading"><div><span class="pl-local">MY LIBRARY / ${esc(c.name)}</span><h1>${esc(title)}</h1><p>${folder===null?c.items.length+' videos in '+own.length+' categories':visibleItems(c).length+' videos'}</p></div><div class="pl-actions">${folder===null?'':button('&#9654; Play all','play-all','primary',!visibleItems(c).length)}${button('Add videos','add','primary')}${actions}</div></div>`;
  if(folder===null){
    const missing=c.items.filter(i=>!i.category).length;
    return header+`<div class="pl-toolbar"><h2>Categories</h2>${button('View all videos','all-videos')}</div><div class="pl-folder-grid">${own.map(cat=>`<article class="pl-folder">${icon('folder')}<h2>${esc(cat.name)}</h2><p>${c.items.filter(i=>i.category===cat.id).length} videos</p><button data-pl="open-folder" data-id="${esc(cat.id)}" aria-label="Open ${esc(cat.name)}">Open category</button></article>`).join('')}${missing?`<article class="pl-folder">${icon('folder')}<h2>Uncategorised</h2><p>${missing} videos to organise</p><button data-pl="open-folder" data-id="" aria-label="Open Uncategorised">Open category</button></article>`:''}</div>${tools()}`;
  }
  const items=visibleItems(c),missing=items.filter(i=>i.provider==='local'&&!files.has(i.id)).length;
  return header+`${missing?`<div class="pl-message">Your video list is saved. Choose the original files or folder to reconnect ${missing} local videos on this visit. ${button('Reconnect files','reconnect-file')}${button('Reconnect folder','reconnect-folder')}</div>`:''}
  <div class="pl-toolbar"><h2>Videos</h2><div class="pl-actions"><input id="pl-search" type="search" aria-label="Search videos in this category" placeholder="Search videos" value="${esc(query)}"><select id="pl-order" aria-label="Video order">${[['original','Original order'],['name','Filename / title'],['date','Date, oldest first'],['unwatched','Unwatched only']].map(([v,t])=>`<option value="${v}" ${v===order?'selected':''}>${t}</option>`).join('')}</select></div></div><div class="pl-videos">${items.slice(0,shown).map(videoCard).join('')}</div>${items.length>shown?`<div class="pl-dialog-footer">${button('Show more videos','more')}</div>`:''}${items.length?'':'<div class="pl-empty-search">No videos here yet. Choose Add videos, or move videos here using Edit.</div>'}${tools()}`;
}
let shown=48;
let filters={company:'',show:'',year:''};
function videoCard(item){const progress=item.watched?100:Math.min(100,(item.position||0)/(item.duration||1)*100);return `<article class="pl-video"><div class="pl-thumbnail">${thumb(item)}${item.duration?`<span class="pl-length">${time(item.duration)}</span>`:''}</div><div class="pl-progress"><span style="width:${progress}%"></span></div><div class="pl-video-copy"><h3>${esc(item.title)}</h3><p>${esc(parsedSummary(item)||labels[item.provider])}${item.watched?' · Watched':item.position>5?' · Resume '+time(item.position):''}</p><div class="pl-actions"><button class="primary" data-pl="play" data-id="${esc(item.id)}">${item.expandable?'View recordings':!['local','archive'].includes(item.provider)?'Watch on '+labels[item.provider]:item.position>5&&!item.watched?'Resume':'Play'}</button><button data-pl="video-options" data-id="${esc(item.id)}" aria-label="Edit ${esc(item.title)}">Edit</button></div></div></article>`;}
function render(){if(!root?.isConnected)return;const focused=document.activeElement,focusId=focused?.id,selection=focused?.selectionStart;const c=current();root.innerHTML=c?detail(c):home();if(focusId){const control=root.querySelector('#'+focusId);if(control){control.focus();if(selection!=null&&control.type==='search')control.setSelectionRange(selection,selection);}}}
export async function mountPersonalLibrary(element,{companyId=null}={}){
  const id=++mountId;opened=companyId;folder=null;query='';sourceFilter='all';order='original';filters={company:'',show:'',year:''};shown=48;
  root=element;root.classList.add('personal-library');root.innerHTML='<p class="pl-status"><span class="pl-spinner"></span>Opening your library...</p>';
  try{await reload();if(id!==mountId)return;render();}catch(error){root.innerHTML=`<div class="pl-message">${esc(error.message)}</div>`;}
  root.onclick=event=>handle(event);
  root.oninput=event=>{if(event.target.id==='pl-search'){query=event.target.value;shown=48;render();}};
  root.onchange=event=>{if(event.target.id==='pl-source'){sourceFilter=event.target.value;render();}if(event.target.id==='pl-order'){order=event.target.value;shown=48;render();}};
  root.ondragover=event=>{if(event.dataTransfer.types.includes('Files')){event.preventDefault();root.classList.add('pl-dragging');}};
  root.ondragleave=()=>root.classList.remove('pl-dragging');root.ondrop=event=>{event.preventDefault();root.classList.remove('pl-dragging');fromFiles([...event.dataTransfer.files]);};
}
export function closePersonalLibrary(){mountId++;closeDialog();root=null;closePlayer();opened=null;folder=null;query='';filters={company:'',show:'',year:''};}
function companyOptionList(selected=''){
  return `<option value="">Choose a company</option>${companyChoices(collections).map(choice=>`<option value="${esc(choice.value)}" ${choice.value===selected?'selected':''}>${esc(choice.name)}</option>`).join('')}`;
}
function closeDialog(){if(modal){importAbort?.abort();modal.close();modal.remove();modal=null;}if(lastFocus?.isConnected)lastFocus.focus();}
function dialog(title,body){closeDialog();lastFocus=document.activeElement;modal=document.createElement('dialog');modal.className='personal-dialog';modal.innerHTML=`<div class="pl-dialog-head"><h2 id="pl-dialog-title">${esc(title)}</h2>${button('Close','close')}</div><div class="pl-dialog-body">${body}</div>`;modal.setAttribute('aria-labelledby','pl-dialog-title');document.body.append(modal);modal.onclick=e=>{if(e.target.closest('[data-pl="close"]'))closeDialog();};modal.addEventListener('cancel',e=>{e.preventDefault();closeDialog();});const own=modal;own.addEventListener('close',()=>{if(modal===own){importAbort?.abort();own.remove();modal=null;if(lastFocus?.isConnected)lastFocus.focus();}});modal.showModal();return modal;}
function pickFiles(folder=false,reconnect=false){const input=document.createElement('input');input.type='file';input.accept='video/*,.mkv,.avi,.mpeg,.mpg,.m4v,.ts,.mov,.webm,.ogv';input.multiple=true;input.className='pl-file-input';input.setAttribute('aria-label',folder?'Choose a video folder':'Choose video files');if(folder)input.setAttribute('webkitdirectory','');document.body.append(input);input.onchange=()=>{const picked=[...input.files];input.remove();if(reconnect){let count=0;for(const file of picked){files.set(fileKey(file),file);for(const item of current()?.items||[]){if(item.provider==='local'&&item.size===file.size&&item.modified===file.lastModified&&item.path.split('/').pop()===file.name){files.set(item.id,file);count++;}}}render();notify('Reconnected '+count+' videos.');}else fromFiles(picked);};input.oncancel=()=>input.remove();input.click();}
function fromFiles(selected){const media=selected.filter(f=>f.type.startsWith('video/')||/\.(mp4|m4v|mkv|webm|mov|avi|mpeg|mpg|ts|ogv)$/i.test(f.name));if(!media.length){notify('No video files were found. Choose a video or a folder containing videos.');return;}const items=media.map(file=>{const id=fileKey(file);files.set(id,file);return {id,provider:'local',title:file.name.replace(/\.[^.]+$/,'').replace(/_/g,' '),path:file.webkitRelativePath||file.name,size:file.size,modified:file.lastModified,duration:0,url:'',thumbnail:'',date:''};});const folder=media[0].webkitRelativePath?.split('/')[0];preview({name:folder||'My videos',provider:'local',items,next:null,total:items.length});}
function addDialog(){const d=dialog('Add to My Library',`<p>Choose where your videos are.</p>${addOptions()}${button('Create a company','new-empty')}<p class="pl-help">Your video list is saved on this device. Your library stays on this device.</p>`);d.addEventListener('click',e=>{const action=e.target.closest('[data-pl]')?.dataset.pl;if(action==='new-empty')emptyCollection();if(action==='add-link')linkDialog();if(action==='add-file'){closeDialog();pickFiles();}if(action==='add-folder'){closeDialog();pickFiles(true);}});}
function linkDialog(){const d=dialog('Add a website link',`<p>Paste the link below. We will find the videos so you can choose what to add.</p><div class="pl-sites"><span>Internet Archive</span><span>YouTube</span><span>Dailymotion</span><span>OK.ru</span></div><form id="pl-link-form"><label for="pl-url">Video, playlist, collection or uploader link</label><input id="pl-url" type="url" inputmode="url" placeholder="https://archive.org/details/..." required autocomplete="off"><p class="pl-help">Copy the address from the website and paste it here.</p><div id="pl-import-status" class="pl-status" role="status"></div><div class="pl-dialog-footer">${button('Cancel','close')}<button id="pl-find" type="submit" class="primary">Find videos</button></div></form>`);$('#pl-link-form',d).onsubmit=async e=>{e.preventDefault();const url=$('#pl-url',d).value.trim();const status=$('#pl-import-status',d),submit=$('#pl-find',d);submit.disabled=true;status.innerHTML='<span class="pl-spinner"></span>Finding videos. Large collections may take a moment...';importAbort=new AbortController();try{const data=await readLink(url,0,importAbort.signal);if(!data.items.length)throw Error('No public videos were found at this link. Try an individual collection or video.');if(d.isConnected)preview({...data,sourceUrl:url});}catch(error){if(!d.isConnected||error.name==='AbortError')return;status.textContent=error.message;status.classList.add('error');submit.disabled=false;}};$('#pl-url',d).focus();}
async function readLink(url,offset,signal){const response=await fetch('/local-api/import',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url,offset}),signal:signal||AbortSignal.timeout(60000)});const data=await response.json();if(!response.ok)throw Error(data.error||'Could not read this link. Please try again.');return data;}
function preview(data){
  data.items=data.items.map(raw=>{
    const item=parseVideo(raw,data.name);
    if(item.provider==='youtube'&&!item.thumbnail){const id=new URL(item.url).searchParams.get('v');if(/^[A-Za-z0-9_-]{11}$/.test(id||''))item.thumbnail='https://i.ytimg.com/vi/'+id+'/hqdefault.jpg';}
    return item;
  });
  const selected=new Set(data.items.map(item=>item.id)),detected=[...new Set(data.items.map(item=>item.company).filter(Boolean))];
  const choices=companyChoices(collections);
  const suggested=detected.length===1?choices.find(choice=>choice.name.toLowerCase()===detected[0].toLowerCase()):null;
  const preferred=suggested?.value||opened||'';
  const d=dialog('Choose the videos to add',`<p class="pl-dialog-step">CHECK YOUR VIDEOS, THEN SAVE</p><p>Found ${data.items.length}${data.total&&data.total>data.items.length?' of '+data.total:''} entries from ${esc(labels[data.provider])}.</p>${data.note?`<p class="pl-help">${esc(data.note)}</p>`:''}
    <label for="pl-destination">Save under this company</label><select id="pl-destination" required>${companyOptionList(preferred)}</select>
    <div class="pl-company-tools">${button('+ Add Company','add-company-inline','pl-add-company')}</div>
    <form id="pl-add-company-form" class="pl-inline-company" hidden><label for="pl-new-company-name">New company name</label><input id="pl-new-company-name" required maxlength="100" placeholder="Enter the company name"><p class="pl-help">New companies are added to My folders on this device.</p><div class="pl-actions"><button type="submit" class="primary">Add company</button>${button('Cancel','cancel-add-company')}</div><p id="pl-company-status" class="pl-status" role="status"></p></form>
    <div id="pl-company-preview" class="pl-company-preview" hidden></div>
    <label for="pl-import-category">Category</label><select id="pl-import-category"></select><p class="pl-help" id="pl-destination-help"></p>
    <div class="pl-result-head"><strong id="pl-selected-count"></strong>${button('Select none','select-all')}</div><div class="pl-results" id="pl-results"></div>${data.next!=null?`<div class="pl-dialog-footer">${button('Find more videos','load-more')}</div>`:''}<div class="pl-status" id="pl-preview-status" role="status"></div><div class="pl-dialog-footer">${button('Back','import-back')}<button id="pl-save-import" type="button" class="primary">Add to My Library</button></div>`);
  function count(){
    $('#pl-selected-count',d).textContent=selected.size+' videos selected';
    $('#pl-save-import',d).disabled=!selected.size||!$('#pl-destination',d).value;
    $('[data-pl="select-all"]',d).textContent=selected.size===data.items.length?'Select none':'Select all';
  }
  function list(){
    $('#pl-results',d).innerHTML=data.items.map(item=>`<label class="pl-check"><input type="checkbox" value="${esc(item.id)}" ${selected.has(item.id)?'checked':''}><span class="pl-preview-art">${thumb(item)}</span><span>${esc(item.title)}<small>${esc(parsedSummary(item)||labels[item.provider])}${categoryFor(item)?' / '+categoryFor(item):' / Uncategorised'}${item.duration?' / '+time(item.duration):''}</small></span></label>`).join('');
    count();
  }
  function destination(){
    const choice=companyChoices(collections).find(entry=>entry.value===$('#pl-destination',d).value),company=choice?.record;
    const options=company?companyCategories(company.id).map(category=>category.name):DEFAULT_CATEGORIES;
    $('#pl-import-category',d).innerHTML=`<option value="auto">Detect from the video metadata</option><option value="">Uncategorised</option>${options.map(name=>`<option>${esc(name)}</option>`).join('')}`;
    if(company?.id===opened&&folder&&folder!=='all'){const previous=categories.find(category=>category.id===folder);if(previous)$('#pl-import-category',d).value=previous.name;}
    $('#pl-destination-help',d).textContent=choice?'All selected videos will be saved only under '+choice.name+'.':'Choose a company, or use + Add Company to create your own.';
    const preview=$('#pl-company-preview',d),image=imageUrl(choice?.thumbnail||choice?.record?.thumbnail);
    preview.hidden=!choice;preview.innerHTML=choice?`${image?`<img src="${esc(image)}" alt="" onerror="this.hidden=true">`:icon('folder')}<span><strong>${esc(choice.name)}</strong><small>Saved on this device</small></span>`:'';
    count();
  }
  $('#pl-destination',d).onchange=destination;list();destination();
  $('#pl-results',d).onchange=event=>{if(event.target.type==='checkbox'){event.target.checked?selected.add(event.target.value):selected.delete(event.target.value);count();}};
  d.addEventListener('click',async event=>{
    const action=event.target.closest('[data-pl]')?.dataset.pl;
    if(action==='select-all'){const none=selected.size===data.items.length;selected.clear();if(!none)for(const item of data.items)selected.add(item.id);list();}
    if(action==='import-back')data.provider==='local'?addDialog():linkDialog();
    if(action==='add-company-inline'){$('#pl-add-company-form',d).hidden=false;$('#pl-new-company-name',d).focus();}
    if(action==='cancel-add-company'){$('#pl-add-company-form',d).hidden=true;$('[data-pl="add-company-inline"]',d).focus();}
    if(action==='load-more'){
      const control=event.target.closest('button');control.disabled=true;const status=$('#pl-preview-status',d);status.innerHTML='<span class="pl-spinner"></span>Finding more videos...';importAbort=new AbortController();
      try{const next=await readLink(data.sourceUrl,data.next,importAbort.signal),seen=new Set(data.items.map(item=>item.id));for(const raw of next.items){const item=parseVideo(raw,data.name);if(item.provider==='youtube'&&!item.thumbnail){const id=new URL(item.url).searchParams.get('v');if(/^[A-Za-z0-9_-]{11}$/.test(id||''))item.thumbnail='https://i.ytimg.com/vi/'+id+'/hqdefault.jpg';}if(!seen.has(item.id)){data.items.push(item);selected.add(item.id);seen.add(item.id);}}data.next=next.next;list();control.hidden=data.next==null;status.textContent='More videos are ready to choose.';}catch(error){status.textContent=error.message;}finally{control.disabled=false;}
    }
  });
  $('#pl-add-company-form',d).onsubmit=async event=>{
    event.preventDefault();const control=$('[type="submit"]',event.currentTarget);control.disabled=true;
    try{
      const name=$('#pl-new-company-name',d).value.trim();if(!name)throw Error('Enter a company name.');
      const exists=companyChoices(collections).find(choice=>choice.name.toLowerCase()===name.toLowerCase());
      let value=exists?.value;
      if(!exists){const fresh=makeCompany(name);await store.saveMany([fresh.company],fresh.categories);await reload();value=fresh.company.id;render();}
      $('#pl-destination',d).innerHTML=companyOptionList(value);$('#pl-add-company-form',d).hidden=true;$('#pl-new-company-name',d).value='';destination();
      $('#pl-preview-status',d).textContent=exists?'The existing company is now selected.':'Company added to My folders and selected for this import.';
    }catch(error){$('#pl-company-status',d).textContent=error.message;}
    finally{control.disabled=false;}
  };
  for(const item of data.items.filter(item=>item.provider==='local'&&!item.thumbnail).slice(0,12)){
    const file=files.get(item.id);if(!file)continue;
    localThumbnail(file).then(thumbnail=>{
      if(!thumbnail)return;item.thumbnail=thumbnail;
      for(const company of collections)for(const saved of company.items)if(saved.id===item.id&&!saved.thumbnail)saved.thumbnail=thumbnail;
      store.saveThumbnail(item.id,thumbnail).catch(()=>{});
      if(d.isConnected){const index=data.items.indexOf(item),image=$('.pl-check:nth-child('+(index+1)+') .pl-preview-art',d);if(image)image.innerHTML=thumb(item);}
    });
  }
  $('#pl-save-import',d).onclick=async()=>{
    const control=$('#pl-save-import',d);control.disabled=true;
    try{
      const choice=companyChoices(collections).find(entry=>entry.value===$('#pl-destination',d).value);
      if(!choice)throw Error('Choose a company before adding videos.');
      const selection=$('#pl-import-category',d).value;
      const company=structuredClone(choice.record),allCategories=categories;let added=0;
      for(const item of data.items){if(!selected.has(item.id)||company.items.some(video=>sameVideo(video,item)))continue;const name=selection==='auto'?categoryFor(item):selection;company.items.push({...item,company:company.name,category:categoryId(allCategories,company.id,name)});added++;}
      company.updated=Date.now();await store.saveMany([company]);await reload();
      opened=company.id;folder=null;query='';shown=48;filters={company:'',show:'',year:''};closeDialog();render();notify(added?added+' videos added only to '+company.name+'.':'These videos are already saved. No duplicates were added.');navigator.storage?.persist?.().catch(()=>{});
    }catch(error){$('#pl-preview-status',d).textContent=error.message;control.disabled=false;}
  };
}
function confirmDialog(title,text,action){const d=dialog(title,`<p>${esc(text)}</p><div class="pl-status" role="status"></div><div class="pl-dialog-footer">${button('Cancel','close')}${button('Remove','confirm-remove','pl-danger')}</div>`);$('[data-pl="confirm-remove"]',d).onclick=async e=>{e.target.disabled=true;try{await action();closeDialog();render();}catch(error){$('.pl-status',d).textContent=error.message;e.target.disabled=false;}};}
function manage(c){
  const d=dialog('Manage company',`<label for="pl-edit-name">Company name</label><input id="pl-edit-name" value="${esc(c.name)}" maxlength="100"><p class="pl-status" role="status"></p><div class="pl-dialog-footer">${button('Cancel','close')}${button('Save changes','save-name','primary')}</div><div class="pl-delete-area"><strong>Remove company</strong><p>This removes this company's folders and saved entries. Your original files and online videos are not deleted.</p>${button('Remove this company','delete-collection','pl-danger')}</div>`);
  $('[data-pl="save-name"]',d).onclick=async()=>{
    const name=$('#pl-edit-name',d).value.trim();if(!name){$('.pl-status',d).textContent='Enter a company name.';return;}
    if(collections.some(company=>company.id!==c.id&&company.name.toLowerCase()===name.toLowerCase())){$('.pl-status',d).textContent='That company already exists.';return;}
    try{const company={...c,name,updated:Date.now(),items:c.items.map(item=>({...item,company:name}))};await store.save(company);await reload();closeDialog();render();notify('Company updated.');}catch(error){$('.pl-status',d).textContent=error.message;}
  };
  $('[data-pl="delete-collection"]',d).onclick=()=>confirmDialog('Remove '+c.name+'?', 'Its saved folders and video entries will be removed. Original files and source videos stay where they are.',async()=>{await store.remove(c.id);await reload();if(opened===c.id){opened=null;folder=null;}});
}
function editVideo(item){
  const source=current();
  const d=dialog('Edit video',`<label for="pl-video-title">Video title</label><input id="pl-video-title" value="${esc(item.title)}" maxlength="500"><label for="pl-move-to">Company</label><select id="pl-move-to">${collections.map(company=>`<option value="${esc(company.id)}" ${company.id===source.id?'selected':''}>${esc(company.name)}</option>`).join('')}</select><label for="pl-edit-category">Category</label><select id="pl-edit-category">${categoryOptions(item.category,source.id)}</select><p class="pl-help">Choose another company or category to move this video.</p><label for="pl-edit-show">Show or event type</label><input id="pl-edit-show" value="${esc(item.show||'')}" placeholder="Show or series"><label for="pl-edit-date">Broadcast or event date (optional)</label><input id="pl-edit-date" type="date" value="${esc(item.date||'')}"><p class="pl-help">Leave blank if the date is unknown. Upload dates are not used as broadcast dates.</p><label class="pl-check"><input id="pl-watched" type="checkbox" ${item.watched?'checked':''}><span>Mark as watched</span></label><p class="pl-source-link">Original: ${esc(item.originalTitle||item.path||item.url)}</p><p class="pl-status" role="status"></p><div class="pl-dialog-footer">${button('Remove video','remove-video','pl-danger')}${button('Save changes','save-video','primary')}</div>`);
  $('#pl-move-to',d).onchange=()=>{const owner=$('#pl-move-to',d).value;$('#pl-edit-category',d).innerHTML=categoryOptions(categoryId(categories,owner,categoryFor(item)),owner);};
  $('[data-pl="save-video"]',d).onclick=async()=>{
    const title=$('#pl-video-title',d).value.trim();if(!title){$('.pl-status',d).textContent='Enter a video title.';return;}
    const target=collections.find(company=>company.id===$('#pl-move-to',d).value);
    if(target.id!==source.id&&target.items.some(video=>sameVideo(video,item))){$('.pl-status',d).textContent='This video is already saved under that company.';return;}
    const updated={...item,title,company:target.name,category:$('#pl-edit-category',d).value,show:$('#pl-edit-show',d).value.trim(),date:$('#pl-edit-date',d).value,watched:$('#pl-watched',d).checked,dateAmbiguous:false};updated.year=updated.date?Number(updated.date.slice(0,4)):item.year;
    try{
      if(target.id===source.id)await store.save({...source,items:source.items.map(video=>video.id===item.id?updated:video)});
      else await store.saveMany([{...source,items:source.items.filter(video=>video.id!==item.id)},{...target,items:[...target.items,updated]}]);
      await reload();closeDialog();render();notify('Video updated.');
    }catch(error){$('.pl-status',d).textContent=error.message;}
  };
  $('[data-pl="remove-video"]',d).onclick=()=>confirmDialog('Remove video?', 'This removes the saved entry only. The original video is not deleted.',async()=>{await store.save({...source,items:source.items.filter(video=>video.id!==item.id)});await reload();});
}
async function backUp(){const data={type:'wc-personal-library',version:1,exported:new Date().toISOString(),categories,collections};const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'})),a=document.createElement('a');a.href=url;a.download='my-library-backup.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);notify('Your library backup contains video details, not the video files.');}
function restore(){const input=document.createElement('input');input.type='file';input.accept='.json,application/json';input.setAttribute('aria-label','Choose a library backup');document.body.append(input);input.onchange=async()=>{try{const file=input.files[0];if(!file)return;if(file.size>30*1024*1024)throw Error('This backup is too large.');const backup=JSON.parse(await file.text()),incoming=store.validateBackup(backup),incomingCategories=store.validateCategories(backup);for(const collection of incoming){const existing=collections.find(c=>c.id===collection.id);if(existing){const ids=new Set(existing.items.map(i=>i.id));collection.items=[...existing.items,...collection.items.filter(i=>!ids.has(i.id))];}}await store.saveMany(incoming,incomingCategories);await reload();opened=null;folder=null;query='';render();notify('Library restored. Existing videos were kept without duplicates.');}catch(error){notify(error.message);}finally{input.remove();}};input.click();}
function closePlayer(){if(activeVideo){saveProgress(true);activeVideo.onpause=activeVideo.ontimeupdate=activeVideo.onended=activeVideo.onerror=activeVideo.onloadedmetadata=null;activeVideo.pause();activeVideo.removeAttribute('src');activeVideo.load();activeVideo=null;}if(playerUrl){URL.revokeObjectURL(playerUrl);playerUrl=null;}if(playerDialog){playerDialog.close();playerDialog.remove();playerDialog=null;}render();}
function saveProgress(force=false){if(!activeVideo?.personalItem)return;const now=Date.now();if(!force&&now-saveProgressAt<5000)return;saveProgressAt=now;const item=activeVideo.personalItem,c=activeVideo.personalCollection;item.position=activeVideo.currentTime||0;if(Number.isFinite(activeVideo.duration))item.duration=activeVideo.duration;if(item.duration&&item.position/item.duration>.95)item.watched=true;store.save(c).catch(()=>{});}
async function play(item,queue=null){
  if(!['local','archive'].includes(item.provider)){window.open(safeUrl(item.url),'_blank','noopener,noreferrer');return;}
  if(item.provider==='local'&&!files.has(item.id)){notify('Choose the original files or folder to reconnect this video.');pickFiles(false,true);return;}
  closePlayer();const c=current();playerDialog=document.createElement('dialog');playerDialog.className='personal-dialog personal-player';playerDialog.setAttribute('aria-label','Personal video player');playerDialog.innerHTML=`<div class="pl-dialog-head"><h2>${esc(item.title)}</h2>${button('Back to collection','close-player')}</div><video controls playsinline preload="metadata"></video><div class="pl-dialog-body"><div class="pl-player-actions">${button('Previous video','previous-personal')}${button('Next video','next-personal')}${item.url?`<a href="${esc(safeUrl(item.url))}" target="_blank" rel="noreferrer noopener">Open original source</a>`:''}</div><p class="pl-player-status" role="status">${item.provider==='local'?'Playing from your device.':'Loading from '+esc(labels[item.provider])+'.'}</p></div>`;document.body.append(playerDialog);activeVideo=$('video',playerDialog);activeVideo.personalItem=item;activeVideo.personalCollection=c;const entries=queue||visibleItems(c),index=entries.findIndex(i=>i.id===item.id);const next=()=>{if(index+1<entries.length)play(entries[index+1],entries);else $('.pl-player-status',playerDialog).textContent='You have reached the end of this collection.';};$('[data-pl="previous-personal"]',playerDialog).disabled=index<=0;$('[data-pl="next-personal"]',playerDialog).disabled=index>=entries.length-1;playerDialog.onclick=e=>{const action=e.target.closest('[data-pl]')?.dataset.pl;if(action==='close-player')closePlayer();if(action==='next-personal')next();if(action==='previous-personal'&&index>0)play(entries[index-1],entries);};playerDialog.oncancel=e=>{e.preventDefault();closePlayer();};const ownPlayer=playerDialog;ownPlayer.addEventListener('close',()=>{if(playerDialog===ownPlayer)closePlayer();});activeVideo.onloadedmetadata=()=>{if(item.position>0&&item.position<activeVideo.duration-3)activeVideo.currentTime=item.position;};activeVideo.ontimeupdate=()=>saveProgress();activeVideo.onpause=()=>saveProgress(true);activeVideo.onended=()=>{item.watched=true;saveProgress(true);next();};activeVideo.onerror=()=>{$('.pl-player-status',playerDialog).textContent=item.provider==='local'?'This browser cannot decode this file. MP4 (H.264/AAC) and WebM are the safest choices. MKV, AVI, MOV and HEVC support depends on your browser. Your file and saved entry have not been changed.':'This source could not play directly in the local preview. You can open its original source above.';};playerDialog.showModal();
  if(item.provider==='local'){playerUrl=URL.createObjectURL(files.get(item.id));activeVideo.src=playerUrl;activeVideo.play().catch(()=>{});}
  else if(item.mediaUrl){activeVideo.src=safeUrl(item.mediaUrl);activeVideo.play().catch(()=>{});}
  else if(item.provider==='archive'&&item.expandable){$('.pl-player-status',playerDialog).textContent='Reading the recordings in this Archive item...';try{const data=await readLink(item.url,0);if(playerDialog!==ownPlayer)return;const list=data.items.filter(i=>i.mediaUrl);if(!list.length)throw Error('No playable video files were found.');closePlayer();preview(data);}catch(error){if(playerDialog)$('.pl-player-status',playerDialog).textContent=error.message;}}
  else {$('.pl-player-status',playerDialog).textContent='Use Open original source to watch this video.';activeVideo.hidden=true;}
}
async function handle(event){
  const target=event.target.closest('[data-pl]');if(!target)return;
  switch(target.dataset.pl){
    case'categories':manageCategories();break;
    case'new-empty':emptyCollection();break;
    case'add':addDialog();break;
    case'add-link':linkDialog();break;
    case'add-file':pickFiles();break;
    case'add-folder':pickFiles(true);break;
    case'open':opened=target.dataset.id;folder=null;query='';filters={company:'',show:'',year:''};shown=48;render();window.scrollTo(0,0);break;
    case'open-folder':folder=target.dataset.id;query='';filters={company:'',show:'',year:''};shown=48;render();break;
    case'all-videos':folder='all';query='';render();break;
    case'back-company':folder=null;query='';render();break;
    case'back':opened=null;folder=null;query='';filters={company:'',show:'',year:''};render();break;
    case'manage':manage(collections.find(company=>company.id===target.dataset.id));break;
    case'manage-current':manage(current());break;
    case'play':play(current().items.find(item=>item.id===target.dataset.id));break;
    case'play-all':{const items=visibleItems(current());if(items[0])play(items.find(item=>!item.watched)||items[0],items);break;}
    case'video-options':editVideo(current().items.find(item=>item.id===target.dataset.id));break;
    case'reconnect-file':pickFiles(false,true);break;
    case'reconnect-folder':pickFiles(true,true);break;
    case'backup':await backUp();break;
    case'restore':restore();break;
    case'filter':filterShows();break;
    case'more':shown+=48;render();break;
  }
}

window.addEventListener('pagehide',()=>saveProgress(true));

function filterShows(){const c=current();const choices=(key)=>[...new Set(c.items.map(i=>i[key]).filter(Boolean))].sort((a,b)=>String(a).localeCompare(String(b),undefined,{numeric:true}));const field=(key,label)=>`<label for="pl-filter-${key}">${label}</label><select id="pl-filter-${key}"><option value="">All ${key==='company'?'companies':label.toLowerCase()+'s'}</option>${choices(key).map(value=>`<option value="${esc(value)}" ${String(value)===filters[key]?'selected':''}>${esc(value)}</option>`).join('')}</select>`;const d=dialog('Filter shows',field('company','Company')+field('show','Show')+field('year','Year')+`<div class="pl-dialog-footer">${button('Clear filters','clear-filters')}${button('Apply filters','apply-filters','primary')}</div>`);$('[data-pl="apply-filters"]',d).onclick=()=>{filters={company:$('#pl-filter-company',d).value,show:$('#pl-filter-show',d).value,year:$('#pl-filter-year',d).value};shown=48;closeDialog();render();};$('[data-pl="clear-filters"]',d).onclick=()=>{filters={company:'',show:'',year:''};closeDialog();render();};}

function emptyCollection(){
  const d=dialog('Create a company',`<p>Your company will start with Weeklies, Specials, PPVs, Matches, Pre-Shows, DVDs and Documentaries. You can add or rename categories later.</p><form id="pl-empty-form"><label for="pl-empty-name">Company name</label><input id="pl-empty-name" required maxlength="100" placeholder="Enter a name for your company folder"><p class="pl-status" role="status"></p><div class="pl-dialog-footer">${button('Cancel','close')}<button class="primary" type="submit">Create company</button></div></form>`);
  $('#pl-empty-form',d).onsubmit=async event=>{
    event.preventDefault();const name=$('#pl-empty-name',d).value.trim();
    if(!name){$('.pl-status',d).textContent='Enter a company name.';return;}
    if(collections.some(c=>c.name.toLowerCase()===name.toLowerCase())){$('.pl-status',d).textContent='That company already exists. Open it to add videos.';return;}
    const submit=$('[type="submit"]',d);submit.disabled=true;
    try{const fresh=makeCompany(name);await store.saveMany([fresh.company],fresh.categories);await reload();opened=fresh.company.id;folder=null;query='';closeDialog();render();notify('Company created with seven category folders.');}catch(error){$('.pl-status',d).textContent=error.message;submit.disabled=false;}
  };
}
function categoryName(value,except='') {
  const name=value.trim();
  if(!name)throw Error('Enter a category name.');
  if(name.length>80)throw Error('Keep the name under 80 characters.');
  if(companyCategories().some(c=>c.id!==except&&c.name.normalize('NFKC').toLowerCase()===name.normalize('NFKC').toLowerCase()))throw Error('A category with that name already exists.');
  return name;
}

function manageCategories() {
  const d=dialog('Manage categories',`<p>Add or rename folders inside ${esc(current().name)}. Other companies are not affected.</p><form id="pl-category-form"><label for="pl-category-name">New category name</label><div class="pl-category-create"><input id="pl-category-name" maxlength="80" required placeholder="Category name"><button class="primary" type="submit">Add category</button></div><p class="pl-status" role="status"></p></form><div class="pl-category-list">${companyCategories().length?companyCategories().map(c=>`<div class="pl-category-row"><div><strong>${esc(c.name)}</strong><small>${current().items.filter(item=>item.category===c.id).length} videos</small></div><button data-category-action="rename" data-id="${esc(c.id)}" aria-label="Rename ${esc(c.name)}">Rename</button><button class="pl-danger" data-category-action="remove" data-id="${esc(c.id)}" aria-label="Remove category ${esc(c.name)}">Remove</button></div>`).join(''):'<p class="pl-help">No categories yet. Add your first one above.</p>'}</div>`);
  $('#pl-category-form',d).onsubmit=async event=>{
    event.preventDefault();const submit=$('[type="submit"]',d);submit.disabled=true;
    try {
      if(companyCategories().length>=200)throw Error('You have reached the limit of 200 categories.');
      const name=categoryName($('#pl-category-name',d).value);
      await store.saveCategory({id:crypto.randomUUID(),name,companyId:opened,key:'user:'+crypto.randomUUID(),created:Date.now()});await reload();render();manageCategories();
    } catch(error){$('.pl-status',d).textContent=error.message;submit.disabled=false;}
  };
  d.addEventListener('click',event=>{
    const target=event.target.closest('[data-category-action]');if(!target)return;
    const category=categories.find(c=>c.id===target.dataset.id);if(!category)return;
    if(target.dataset.categoryAction==='remove')confirmDialog('Remove '+category.name+'?', 'Its videos will stay under this company, in Uncategorised. Other companies are not affected.',async()=>{await store.removeCategory(category.id);await reload();});
    else renameCategory(category);
  });
}

function renameCategory(category) {
  const d=dialog('Rename category',`<form id="pl-rename-category"><label for="pl-renamed-category">Category name</label><input id="pl-renamed-category" value="${esc(category.name)}" required maxlength="80"><p class="pl-status" role="status"></p><div class="pl-dialog-footer">${button('Cancel','close')}<button class="primary" type="submit">Save changes</button></div></form>`);
  $('#pl-rename-category',d).onsubmit=async event=>{
    event.preventDefault();const submit=$('[type="submit"]',d);submit.disabled=true;
    try {const name=categoryName($('#pl-renamed-category',d).value,category.id);await store.saveCategory({...category,name});await reload();closeDialog();render();notify('Category renamed.');}
    catch(error){$('.pl-status',d).textContent=error.message;submit.disabled=false;}
  };
}

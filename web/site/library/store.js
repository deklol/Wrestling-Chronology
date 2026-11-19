const NAME = 'wc-player-personal-library';
let opening;
function database() {
  return opening ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(NAME, 2);
    req.onupgradeneeded = () => {
      for (const name of ['collections','categories']) if (!req.result.objectStoreNames.contains(name)) req.result.createObjectStore(name, {keyPath:'id'});
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(Error('Your browser could not open local storage.'));
  });
}
async function transaction(mode, action, name='collections') {
  const db = await database();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(name, mode), result = action(tx.objectStore(name));
    tx.oncomplete = () => resolve(result?.result);
    tx.onerror = tx.onabort = () => reject(Error('Your library could not be saved. Check free space on this device.'));
  });
}
export const all = () => transaction('readonly', store => store.getAll());
export const save = collection => transaction('readwrite', store => store.put(collection));
export async function remove(id) {
  const db=await database();
  return new Promise((resolve,reject)=>{
    const tx=db.transaction(['collections','categories'],'readwrite');
    tx.objectStore('collections').delete(id);
    const request=tx.objectStore('categories').getAll();
    request.onsuccess=()=>{for(const category of request.result)if(category.companyId===id)tx.objectStore('categories').delete(category.id);};
    tx.oncomplete=resolve;
    tx.onerror=tx.onabort=()=>reject(Error('The company could not be removed.'));
  });
}
export const categories = () => transaction('readonly', store => store.getAll(), 'categories');
export const saveCategory = category => transaction('readwrite', store => store.put(category), 'categories');
export async function saveThumbnail(id,thumbnail){
  const db=await database();
  return new Promise((resolve,reject)=>{
    const tx=db.transaction('collections','readwrite'),table=tx.objectStore('collections'),request=table.getAll();
    request.onsuccess=()=>{for(const company of request.result){let changed=false;const items=company.items.map(item=>{if(item.id!==id||item.thumbnail)return item;changed=true;return {...item,thumbnail};});if(changed)table.put({...company,items});}};
    tx.oncomplete=resolve;tx.onerror=tx.onabort=()=>reject(Error('Thumbnail could not be saved.'));
  });
}
export async function saveMany(collections, categories=[]) {
  const db = await database();
  return new Promise((resolve,reject)=>{
    const tx=db.transaction(['collections','categories'],'readwrite');
    for (const collection of collections) tx.objectStore('collections').put(collection);
    for (const category of categories) tx.objectStore('categories').put(category);
    tx.oncomplete=resolve;
    tx.onerror=tx.onabort=()=>reject(Error('Your library could not be saved.'));
  });
}
export async function removeCategory(id) {
  const db=await database();
  return new Promise((resolve,reject)=>{
    const tx=db.transaction(['collections','categories'],'readwrite');
    tx.objectStore('categories').delete(id);
    const request=tx.objectStore('collections').getAll();
    request.onsuccess=()=>{for(const collection of request.result){let changed=false;const items=collection.items.map(item=>{if(item.category!==id)return item;changed=true;return {...item,category:''};});if(changed)tx.objectStore('collections').put({...collection,items});}};
    tx.oncomplete=resolve;
    tx.onerror=tx.onabort=()=>reject(Error('The category could not be removed.'));
  });
}
export function validateBackup(data) {
  if (data?.type !== 'wc-personal-library' || data.version !== 1 || !Array.isArray(data.collections) || data.collections.length > 500) throw Error('This is not a My Library backup file.');
  let count = 0;
  return data.collections.map(c => {
    if (typeof c.id !== 'string' || !c.id || c.id.length > 120 || typeof c.name !== 'string' || !Array.isArray(c.items)) throw Error('This backup contains an invalid collection.');
    count += c.items.length;
    if (count > 50000) throw Error('This backup is too large to import.');
    const items = c.items.map(item => {
      if (typeof item.id !== 'string' || !item.id || typeof item.title !== 'string' || !['local','archive','youtube','dailymotion','okru'].includes(item.provider)) throw Error('This backup contains an invalid video.');
      const url = value => {if (!value) return '';try {const u = new URL(value);return u.protocol === 'https:' ? u.href : '';} catch {return '';}};
      const thumbnail=value=>typeof value==='string'&&/^data:image\/(?:webp|png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(value)&&value.length<150000?value:url(value);
      return {id:item.id.slice(0,500),title:item.title.slice(0,500),provider:item.provider,url:url(item.url),mediaUrl:url(item.mediaUrl),thumbnail:thumbnail(item.thumbnail),duration:Math.max(0,Number(item.duration)||0),date:/^\d{4}-\d{2}-\d{2}$/.test(item.date)?item.date:'',path:String(item.path||'').slice(0,1000),size:Number(item.size)||0,modified:Number(item.modified)||0,position:Math.max(0,Number(item.position)||0),watched:item.watched===true,expandable:item.expandable===true,originalTitle:String(item.originalTitle||item.title).slice(0,1000),company:String(item.company||'').slice(0,100),category:String(item.category||'').slice(0,180),show:String(item.show||'').slice(0,100),kind:String(item.kind||'Video').slice(0,40),year:Number(item.year)||0,season:Number(item.season)||0,episode:Number(item.episode)||0,dateAmbiguous:item.dateAmbiguous===true};
    });
    return {id:c.id,name:c.name.slice(0,100),foldersInitialized:c.foldersInitialized===true,provider:items[0]?.provider||'local',created:Number(c.created)||Date.now(),updated:Date.now(),custom:c.custom!==false,thumbnail:typeof c.thumbnail==='string'&&/^\/assets\/[a-z0-9_.-]+$/.test(c.thumbnail)?c.thumbnail:'',items};
  });
}
export function validateCategories(data) {
  if(!data.categories)return [];
  if(!Array.isArray(data.categories)||data.categories.length>10000)throw Error('This backup contains too many categories.');
  return data.categories.map(category=>{
    if(typeof category.id!=='string'||!category.id||category.id.length>120||typeof category.name!=='string'||!category.name.trim())throw Error('This backup contains an invalid category.');
    return {id:category.id,name:category.name.trim().slice(0,80),companyId:String(category.companyId||'').slice(0,120),key:String(category.key||'').slice(0,100),created:Number(category.created)||Date.now()};
  });
}

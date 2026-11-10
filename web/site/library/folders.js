export const DEFAULT_CATEGORIES=['Weeklies','Specials','PPVs','Matches','Pre-Shows','DVDs','Documentaries'];
export const keyOf=name=>name.toLowerCase().replace(/[^a-z0-9]/g,'');

export function categoryFor(item) {
  if(item.kind==='Documentary')return 'Documentaries';
  if(/\bdvd\b|\bdisc\s*\d/i.test(item.originalTitle||item.title||''))return 'DVDs';
  if(item.season||item.episode)return 'Weeklies';
  return '';
}

export function makeCompany(name,details={}) {
  const company={id:crypto.randomUUID(),name,provider:'local',created:Date.now(),updated:Date.now(),items:[],foldersInitialized:true,custom:true,...details};
  return {company,categories:DEFAULT_CATEGORIES.map(label=>({id:company.id+':'+keyOf(label),name:label,key:keyOf(label),companyId:company.id,created:Date.now()}))};
}

export function categoryId(categories,companyId,name) {
  const key=keyOf(name||'');
  return categories.find(category=>category.companyId===companyId&&(category.key===key||category.name.toLowerCase()===String(name).toLowerCase()))?.id||'';
}

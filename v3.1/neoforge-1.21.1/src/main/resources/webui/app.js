(() => {
  'use strict';

  const q = (s, root = document) => root.querySelector(s);
  const qa = (s, root = document) => Array.from(root.querySelectorAll(s));
  const app = q('#app');
  const mapStage = q('#mapStage');
  const canvas = q('#mapCanvas');
  const ctx = canvas.getContext('2d', {alpha:false});
  const markerLayer = q('#markerLayer');
  const playerEl = q('.player');
  const playerRingEl = q('.player-ring');
  const overlay = q('#overlay');
  const settingsModal = q('#settingsModal');
  const editorModal = q('#editorModal');
  const aboutModal = q('#aboutModal');
  const layersPanel = q('#layersPanel');
  const markersPanel = q('#markersPanel');
  const mapContext = q('#mapContext');
  const markerContext = q('#markerContext');
  const dropdown = q('#dropdown');

  const token = new URLSearchParams(location.search).get('token') || '';
  const LOCAL_KEY = 'cartograph-v31-webui-settings-v1';
  const VIEW_KEY = 'cartograph-v31-webui-view-v1';
  const TILE_SIZE = 16;
  const MIN_ZOOM = 0.0625;
  const MAX_ZOOM = 64;
  const MAX_TILE_REQUESTS = 24;

  const defaults = {
    showCoords:true, playerRing:true, labels:true, custom:true, structures:true,
    grid:false, dragMap:true, playerFixed:true, playerIcon:'arrow',
    playerSize:24, playerColor:'#FFE14E', markerScale:35,
    markerDefaultIcon:'pin', markerDefaultColor:'#FFD34E',
    markerDefaultSize:22, hideLabelsLowZoom:true, avoidLabels:true,
    debug:false, structureFilters:{}
  };

  let settings = loadJson(LOCAL_KEY, defaults);
  settings = Object.assign({}, defaults, settings);
  settings.structureFilters = settings.structureFilters || {};

  let serverState = null;
  let currentDimension = '';
  let lastWorldKey = '';
  let view = {centerX:0, centerZ:0, zoom:2};
  let viewInitialized = false;
  let dragging = false;
  let dragX = 0, dragY = 0;
  let contextPoint = null;
  let selectedMarker = null;
  let editorMode = 'add';
  let currentEditorIcon = settings.markerDefaultIcon;
  let renderQueued = false;
  let viewportTimer = 0;
  let cursorTimer = 0;
  let stateTimer = 0;
  let toastTimer = 0;
  let lastPointKey = '';

  const tileCache = new Map();
  const tileQueue = [];
  const tileQueued = new Set();
  let activeTileRequests = 0;

  function clone(v){ return JSON.parse(JSON.stringify(v)); }
  function loadJson(key, fallback){
    try{
      const v = JSON.parse(localStorage.getItem(key) || 'null');
      return v && typeof v === 'object' ? v : clone(fallback);
    }catch(_){ return clone(fallback); }
  }
  function saveSettings(){ localStorage.setItem(LOCAL_KEY, JSON.stringify(settings)); }
  function worldKey(s){ return String(s && s.worldId || '') + '|' + String(s && s.dimension || ''); }
  function saveView(){
    if(!serverState)return;
    localStorage.setItem(VIEW_KEY, JSON.stringify({
      worldKey:worldKey(serverState), centerX:view.centerX, centerZ:view.centerZ, zoom:view.zoom
    }));
  }

  async function api(path, options){
    options = options || {};
    const headers = new Headers(options.headers || {});
    if(token)headers.set('X-Cartograph-Token', token);
    if(options.body && !headers.has('Content-Type'))headers.set('Content-Type','application/json');
    const response = await fetch(path, Object.assign({}, options, {headers:headers, cache:'no-store'}));
    if(!response.ok && response.status !== 204 && response.status !== 304){
      const message = await response.text().catch(()=>'');
      throw new Error(String(response.status) + ' ' + (message || response.statusText));
    }
    return response;
  }

  function escapeHtml(v){
    return String(v == null ? '' : v).replace(/[&<>"']/g, c => ({
      '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
    })[c]);
  }
  function colorInt(v){ return parseInt(String(v || '#FFFFFF').replace('#',''),16) & 0xffffff; }
  function toast(text){
    const el=q('#toast'); if(!el)return;
    clearTimeout(toastTimer); el.textContent=text; el.classList.add('show');
    toastTimer=setTimeout(()=>el.classList.remove('show'),1800);
  }
  function setConnection(on){ app.dataset.connection=on?'online':'offline'; }

  function iconSymbol(raw){
    const i=String(raw||'pin').toLowerCase();
    const m={
      pin:'i-pin',home:'i-home',camp:'i-home',mine:'i-mine',ship:'i-ship',
      shipwreck:'i-shipwreck',mountain:'i-mountain',mountain_range:'i-mountain',
      temple:'i-temple',village:'i-village',mansion:'i-mansion',
      desert_pyramid:'i-desert-pyramid',jungle_temple:'i-jungle-temple',
      swamp_hut:'i-swamp-hut',igloo:'i-igloo',ocean_monument:'i-ocean-monument',
      ruined_portal:'i-ruined-portal',portal:'i-portal',ocean_ruin:'i-ocean-ruin',
      buried_treasure:'i-buried-treasure',trail_ruins:'i-trail-ruins',
      trial_chambers:'i-trial-chambers',ancient_city:'i-ancient-city',
      stronghold:'i-stronghold',nether_fortress:'i-nether-fortress',
      bastion_remnant:'i-bastion-remnant',nether_fossil:'i-nether-fossil',
      end_city:'i-end-city',outpost:'i-outpost',city:'i-city',
      fortress:'i-fortress',cave:'i-cave',water:'i-water',lake:'i-water',river:'i-water'
    };
    return m[i] || 'i-pin';
  }
  function iconLabel(raw){
    const i=String(raw||'pin').toLowerCase();
    const m={pin:'定位点',home:'营地',camp:'营地',mine:'矿井',ship:'船 / 码头',
      mountain:'山峰',temple:'神殿',village:'村庄',mansion:'林地府邸',
      outpost:'前哨站',portal:'传送门',city:'城市',fortress:'要塞',
      cave:'洞穴',water:'水域'};
    return m[i] || i.replaceAll('_',' ');
  }

  function stageSize(){
    const r=mapStage.getBoundingClientRect();
    return {width:Math.max(1,r.width),height:Math.max(1,r.height),rect:r};
  }
  function resizeCanvas(){
    const s=stageSize(), dpr=Math.max(1,window.devicePixelRatio||1);
    canvas.width=Math.max(1,Math.round(s.width*dpr));
    canvas.height=Math.max(1,Math.round(s.height*dpr));
    canvas.style.width=s.width+'px'; canvas.style.height=s.height+'px';
    ctx.setTransform(dpr,0,0,dpr,0,0);
    scheduleRender(); sendViewportSoon();
  }
  function resizeTransform(){
    const s=stageSize(), dpr=Math.max(1,window.devicePixelRatio||1);
    const w=Math.max(1,Math.round(s.width*dpr)),h=Math.max(1,Math.round(s.height*dpr));
    if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}
    ctx.setTransform(dpr,0,0,dpr,0,0);
  }
  function worldToScreen(x,z){
    const s=stageSize();
    return {x:s.width*.5+(x-view.centerX)*view.zoom,y:s.height*.5+(z-view.centerZ)*view.zoom};
  }
  function screenToWorld(clientX,clientY){
    const s=stageSize(),sx=clientX-s.rect.left,sy=clientY-s.rect.top;
    return {x:view.centerX+(sx-s.width*.5)/view.zoom,z:view.centerZ+(sy-s.height*.5)/view.zoom,sx:sx,sy:sy};
  }
  function setZoom(next,clientX,clientY){
    next=Math.max(MIN_ZOOM,Math.min(MAX_ZOOM,next));
    if(Math.abs(next-view.zoom)<1e-9)return;
    const s=stageSize();
    let sx=s.width*.5,sy=s.height*.5;
    if(clientX!=null&&clientY!=null){sx=clientX-s.rect.left;sy=clientY-s.rect.top;}
    const wx=view.centerX+(sx-s.width*.5)/view.zoom;
    const wz=view.centerZ+(sy-s.height*.5)/view.zoom;
    view.centerX=wx-(sx-s.width*.5)/next; view.centerZ=wz-(sy-s.height*.5)/next; view.zoom=next;
    saveView(); scheduleRender(); sendViewportSoon();
  }
  function centerPlayer(){
    const p=serverState&&serverState.player;if(!p||!p.present)return;
    view.centerX=p.x;view.centerZ=p.z;saveView();scheduleRender();sendViewportSoon();toast('已回到玩家位置');
  }

  function scheduleRender(){
    if(renderQueued)return;renderQueued=true;
    requestAnimationFrame(()=>{renderQueued=false;renderMap();});
  }
  function renderMap(){
    resizeTransform();
    const s=stageSize();
    ctx.save();ctx.fillStyle='#17383c';ctx.fillRect(0,0,s.width,s.height);
    ctx.imageSmoothingEnabled=view.zoom<1;
    if(serverState&&serverState.connected&&currentDimension){
      const hx=s.width/(2*view.zoom),hz=s.height/(2*view.zoom);
      const minX=Math.floor((view.centerX-hx)/16)-1,maxX=Math.floor((view.centerX+hx)/16)+1;
      const minZ=Math.floor((view.centerZ-hz)/16)-1,maxZ=Math.floor((view.centerZ+hz)/16)+1;
      const now=Date.now();
      for(let cz=minZ;cz<=maxZ;cz++)for(let cx=minX;cx<=maxX;cx++){
        const key=tileKey(cx,cz),tile=tileCache.get(key);
        const x=s.width*.5+(cx*16-view.centerX)*view.zoom;
        const y=s.height*.5+(cz*16-view.centerZ)*view.zoom;
        const size=16*view.zoom;
        if(tile&&tile.canvas)ctx.drawImage(tile.canvas,x,y,size,size);
        if(!tile || (now>=(tile.retryAt||0) && (!tile.checkedAt||now-tile.checkedAt>2500)))enqueueTile(cx,cz);
      }
    }
    ctx.restore();renderPlayer();renderMarkers();renderHud();
  }

  function tileKey(x,z){return currentDimension+'|'+x+'|'+z;}
  function enqueueTile(cx,cz){
    const key=tileKey(cx,cz);if(tileQueued.has(key))return;
    tileQueued.add(key);tileQueue.push({cx:cx,cz:cz,key:key});pumpTiles();
  }
  function pumpTiles(){
    while(activeTileRequests<MAX_TILE_REQUESTS&&tileQueue.length){
      const job=tileQueue.shift();tileQueued.delete(job.key);activeTileRequests++;
      fetchTile(job).finally(()=>{activeTileRequests--;pumpTiles();});
    }
  }
  async function fetchTile(job){
    const old=tileCache.get(job.key),headers={};if(old&&old.etag)headers['If-None-Match']=old.etag;
    try{
      const r=await api('/api/v1/tile?x='+job.cx+'&z='+job.cz,{headers:headers});
      const now=Date.now();
      if(r.status===304&&old){old.checkedAt=now;return;}
      if(r.status===204){tileCache.set(job.key,Object.assign({},old||{},{checkedAt:now,retryAt:now+800}));return;}
      const bytes=new Uint8Array(await r.arrayBuffer());if(bytes.length!==768)return;
      const image=new ImageData(16,16);
      for(let i=0;i<256;i++){image.data[i*4]=bytes[i*3];image.data[i*4+1]=bytes[i*3+1];image.data[i*4+2]=bytes[i*3+2];image.data[i*4+3]=255;}
      const c=document.createElement('canvas');c.width=16;c.height=16;c.getContext('2d').putImageData(image,0,0);
      tileCache.set(job.key,{canvas:c,etag:r.headers.get('ETag')||'',checkedAt:now,retryAt:now+2500});scheduleRender();
    }catch(_){
      const now=Date.now();tileCache.set(job.key,Object.assign({},old||{},{checkedAt:now,retryAt:now+1500}));
    }
  }

  function renderPlayer(){
    const p=serverState&&serverState.player;
    if(!p||!p.present){playerEl.style.display='none';playerRingEl.style.display='none';return;}
    const pos=worldToScreen(p.x,p.z),s=stageSize();
    const visible=pos.x>-100&&pos.x<s.width+100&&pos.y>-100&&pos.y<s.height+100;
    playerEl.style.display=visible?'':'none';playerRingEl.style.display=visible&&settings.playerRing?'':'none';
    playerEl.style.left=pos.x+'px';playerEl.style.top=pos.y+'px';playerEl.style.color=settings.playerColor;
    playerEl.style.transform='translate(-50%,-50%) rotate('+(180+Number(p.yaw||0))+'deg)';
    const use=q('.player use');if(use)use.setAttribute('href',settings.playerIcon==='cross'?'#i-cross':settings.playerIcon==='dot'?'#i-dot':'#i-player');
    const icon=q('.player .icon');
    const size=settings.playerFixed?settings.playerSize:Math.max(8,Math.min(72,settings.playerSize*Math.pow(Math.max(.15,Math.min(4,view.zoom)),.35)));
    if(icon){icon.style.width=size+'px';icon.style.height=size+'px';icon.classList.toggle('solid',settings.playerIcon!=='cross');}
    const ring=Math.max(64,size*3.2);playerRingEl.style.left=pos.x+'px';playerRingEl.style.top=pos.y+'px';playerRingEl.style.width=ring+'px';playerRingEl.style.height=ring+'px';
  }
  function renderMarkers(){
    const markers=serverState&&serverState.markers||[],s=stageSize();markerLayer.innerHTML='';
    const showLabels=settings.labels&&!(settings.hideLabelsLowZoom&&view.zoom<.25);
    const factor=Math.pow(Math.max(.15,Math.min(4,view.zoom)),.2*(settings.markerScale/100));
    markers.forEach(marker=>{
      if(marker.kind==='structure'){
        if(!settings.structures)return;
        if(marker.preset&&settings.structureFilters[marker.preset]===false)return;
      }else if(!settings.custom)return;
      const pos=worldToScreen(marker.x,marker.z);if(pos.x<-80||pos.x>s.width+80||pos.y<-80||pos.y>s.height+80)return;
      const el=document.createElement('div');el.className='map-marker '+(marker.kind==='structure'?'structure':'custom');
      el.dataset.markerId=marker.id;el.style.left=pos.x+'px';el.style.top=pos.y+'px';
      el.style.color=marker.accentColor||(marker.kind==='structure'?'#FFFFFF':settings.markerDefaultColor);
      const size=Math.max(15,Math.min(48,(marker.kind==='structure'?27:settings.markerDefaultSize)*factor));
      el.innerHTML='<svg class="glyph icon" style="width:'+size+'px;height:'+size+'px"><use href="#'+iconSymbol(marker.icon)+'"/></svg>'+
        '<div class="marker-label" style="'+(showLabels?'':'display:none')+'">'+escapeHtml(marker.name||iconLabel(marker.icon))+'</div>';
      el.addEventListener('contextmenu',e=>{e.preventDefault();e.stopPropagation();selectedMarker=marker;openMarkerContext(marker,e.clientX,e.clientY);});
      el.addEventListener('dblclick',e=>{e.preventDefault();openEditor(marker);});
      markerLayer.appendChild(el);
    });
  }
  function renderHud(){
    q('#zoomText').textContent=Math.round(view.zoom*100)+'%';
    const blocks=145/Math.max(view.zoom,.0001);q('#scaleText').textContent=blocks>=1000?(blocks/1000).toFixed(1)+' km':Math.round(blocks)+' m';
    app.dataset.grid=settings.grid?'on':'off';app.dataset.labels=settings.labels?'on':'off';
    app.dataset.structures=settings.structures?'on':'off';app.dataset.custom=settings.custom?'on':'off';app.dataset.playerRing=settings.playerRing?'on':'off';
    q('.coord-box').style.display=settings.showCoords?'':'none';q('#debugHud').style.display=settings.debug?'block':'none';
    if(settings.debug)q('#debugHud').innerHTML='zoom='+view.zoom.toFixed(3)+'<br>center='+Math.round(view.centerX)+','+Math.round(view.centerZ)+'<br>tiles='+tileCache.size+'<br>pending='+(serverState&&serverState.runtime?serverState.runtime.pendingCount:0);
  }

  function sendViewportSoon(){clearTimeout(viewportTimer);viewportTimer=setTimeout(sendViewport,80);}
  async function sendViewport(){
    if(!serverState||!serverState.connected||!viewInitialized)return;const s=stageSize();
    try{await api('/api/v1/viewport',{method:'POST',body:JSON.stringify({centerX:view.centerX,centerZ:view.centerZ,zoom:view.zoom,width:Math.round(s.width),height:Math.round(s.height)})});}catch(_){}
  }
  function initView(next){
    const saved=loadJson(VIEW_KEY,{}),same=saved.worldKey&&saved.worldKey===worldKey(next);
    if(same&&Number.isFinite(saved.centerX)&&Number.isFinite(saved.centerZ))view={centerX:saved.centerX,centerZ:saved.centerZ,zoom:Math.max(MIN_ZOOM,Math.min(MAX_ZOOM,Number(saved.zoom)||2))};
    else if(next.player&&next.player.present)view={centerX:next.player.x,centerZ:next.player.z,zoom:2};
    viewInitialized=true;
  }
  async function pollState(){
    try{
      const r=await api('/api/v1/state');const next=await r.json();setConnection(!!next.connected);serverState=next;currentDimension=next.dimension||'';
      const wk=worldKey(next);if(wk!==lastWorldKey){lastWorldKey=wk;tileCache.clear();tileQueue.length=0;tileQueued.clear();initView(next);}else if(!viewInitialized)initView(next);
      renderStatus();renderLayerList();renderMarkerList();renderStructureSettings();syncRuntimeSettings();scheduleRender();sendViewportSoon();
    }catch(_){setConnection(false);}
    clearTimeout(stateTimer);stateTimer=setTimeout(pollState,500);
  }
  async function pollStateOnce(){
    const r=await api('/api/v1/state');serverState=await r.json();currentDimension=serverState.dimension||'';renderStatus();renderLayerList();renderMarkerList();renderStructureSettings();scheduleRender();
  }
  function renderStatus(){
    const markers=serverState&&serverState.markers||[],structures=markers.filter(m=>m.kind==='structure').length,custom=markers.length-structures;
    q('#mapDataStatus').textContent='结构 '+structures+' · 标点 '+custom+' · 缓存 '+(serverState&&serverState.runtime?serverState.runtime.cachedCount:0);
  }
  function renderLayerList(){
    const markers=serverState&&serverState.markers||[],structures=markers.filter(m=>m.kind==='structure'),custom=markers.filter(m=>m.kind!=='structure');
    const cc=q('#layersPanel .layer-row .count');if(cc)cc.textContent=custom.length;q('#structureTotalCount').textContent=structures.length;
    const groups=new Map();structures.forEach(m=>{const k=m.preset||m.icon||'other';if(!groups.has(k))groups.set(k,{key:k,count:0,label:m.name||k});groups.get(k).count++;});
    const list=q('#structureLayerList');list.innerHTML='';
    Array.from(groups.values()).sort((a,b)=>a.label.localeCompare(b.label,'zh-CN')).forEach(g=>{
      const on=settings.structureFilters[g.key]!==false,row=document.createElement('div');row.className='layer-row indent';
      row.innerHTML='<div class="checkbox '+(on?'on':'')+'"><svg class="icon"><use href="#i-check"/></svg></div><svg class="icon layer-icon"><use href="#'+iconSymbol(g.key)+'"/></svg><div class="name">'+escapeHtml(g.label)+'</div><div class="count">'+g.count+'</div>';
      row.querySelector('.checkbox').onclick=()=>{settings.structureFilters[g.key]=!on;saveSettings();renderLayerList();scheduleRender();};list.appendChild(row);
    });
  }
  function renderStructureSettings(){
    const list=q('#structureList');if(!list)return;list.innerHTML='';
    const presets=serverState&&serverState.structurePresets||[];
    presets.forEach(p=>{
      const row=document.createElement('div');row.className='structure-row';
      row.innerHTML='<div>'+escapeHtml(p.label)+'</div>'+
        '<div class="structure-select" style="cursor:default"><div class="left"><svg class="icon"><use href="#'+iconSymbol(p.icon)+'"/></svg><span>'+escapeHtml(iconLabel(p.icon))+'</span></div></div>'+
        '<div class="structure-color"><span class="swatch" style="background:'+escapeHtml(p.accentColor)+'"></span></div>';
      list.appendChild(row);
    });
  }
  function renderMarkerList(){
    const all=(serverState&&serverState.markers||[]).filter(m=>m.kind!=='structure'||m.modifiedAuto);q('#markerCountBadge').textContent=all.length;
    const list=q('#markerList');list.innerHTML='';if(!all.length){list.innerHTML='<div class="small" style="padding:14px 4px">暂无自定义标点</div>';return;}
    all.forEach(m=>{const row=document.createElement('div');row.className='marker-row';
      row.innerHTML='<svg class="icon mi"><use href="#'+iconSymbol(m.icon)+'"/></svg><div class="info"><div class="title">'+escapeHtml(m.name)+'</div><div class="meta">X '+m.x+' · Y '+m.y+' · Z '+m.z+(m.modifiedAuto?' · 自动→自定义':'')+'</div><div class="marker-description">'+escapeHtml(m.description||'')+'</div></div><div class="row-actions"><button class="mini" title="编辑"><svg class="icon"><use href="#i-edit"/></svg></button></div>';
      row.querySelector('button').onclick=()=>openEditor(m);list.appendChild(row);});
  }
  function syncRuntimeSettings(){
    const r=serverState&&serverState.runtime;if(!r)return;q('#requestRange').value=r.requestBatchSize;q('#requestValue').textContent=String(r.requestBatchSize);
    q('#scanRange').value=r.liveScanIntervalTicks;q('#scanValue').textContent=r.liveScanIntervalTicks+' tick / '+(r.liveScanIntervalTicks/20).toFixed(2)+' s';
  }

  async function queryPoint(x,z){const r=await api('/api/v1/point?x='+Math.round(x)+'&z='+Math.round(z));return r.json();}
  function updateCursor(clientX,clientY){
    const p=screenToWorld(clientX,clientY),x=Math.round(p.x),z=Math.round(p.z),key=x+'|'+z;if(key===lastPointKey)return;lastPointKey=key;
    q('#cursorCoords').textContent='X '+x+'　Z '+z;clearTimeout(cursorTimer);cursorTimer=setTimeout(async()=>{try{const info=await queryPoint(x,z);q('#cursorCoords').textContent='X '+info.x+'　Y '+info.y+'　Z '+info.z;q('#cursorBiome').textContent=String(info.biome||'群系：未加载').replace(/^群系：/,'');}catch(_){}},120);
  }
  function positionMenu(menu,cx,cy){
    menu.style.display='block';const host=menu.offsetParent||app,hr=host.getBoundingClientRect(),r=menu.getBoundingClientRect();
    let left=cx-hr.left+8,top=cy-hr.top+8;if(left+r.width>hr.width-8)left=cx-hr.left-r.width-8;if(top+r.height>hr.height-8)top=cy-hr.top-r.height-8;
    menu.style.left=Math.max(8,left)+'px';menu.style.top=Math.max(8,top)+'px';
  }
  function closeContexts(){mapContext.style.display='none';markerContext.style.display='none';}
  async function openMapContext(cx,cy){
    closeContexts();const p=screenToWorld(cx,cy),x=Math.round(p.x),z=Math.round(p.z);contextPoint={x:x,z:z,y:serverState&&serverState.player?serverState.player.y:64};
    q('#mapContext .ctx-meta span').textContent='X '+x+', Z '+z;positionMenu(mapContext,cx,cy);
    try{const info=await queryPoint(x,z);if(contextPoint&&contextPoint.x===x&&contextPoint.z===z){contextPoint=info;q('#mapContext .ctx-meta span').textContent='X '+info.x+', Y '+info.y+', Z '+info.z;}}catch(_){}
  }
  function openMarkerContext(m,cx,cy){
    closeContexts();selectedMarker=m;q('#markerContextMeta').textContent=m.name+' · X '+m.x+' Y '+m.y+' Z '+m.z;
    q('#ctxDeleteText').textContent=m.modifiedAuto?'重置为自动':'删除';q('#ctxDelete').classList.toggle('danger',!m.modifiedAuto);positionMenu(markerContext,cx,cy);
  }

  function hidePanels(){layersPanel.style.display='none';markersPanel.style.display='none';qa('.nav-btn').forEach(b=>b.classList.toggle('active',b.dataset.nav==='map'));}
  function openPanel(name){closeContexts();const panel=name==='layers'?layersPanel:markersPanel,other=name==='layers'?markersPanel:layersPanel;other.style.display='none';panel.style.display=panel.style.display==='block'?'none':'block';qa('.nav-btn').forEach(b=>b.classList.toggle('active',b.dataset.nav===name&&panel.style.display==='block'));if(panel.style.display!=='block')q('[data-nav="map"]').classList.add('active');}
  function openModal(modal){hidePanels();closeContexts();overlay.style.display='block';modal.style.display='block';app.classList.add('modal-open');}
  function closeModal(){overlay.style.display='none';[settingsModal,editorModal,aboutModal].forEach(m=>m.style.display='none');app.classList.remove('modal-open');dropdown.classList.remove('open');}
  function activateSettingsPage(page){qa('.settings-nav-item').forEach(el=>el.classList.toggle('active',el.dataset.page===page));qa('[data-setting-page]').forEach(el=>el.classList.toggle('active',el.dataset.settingPage===page));}
  function openSettings(page){renderSettings();activateSettingsPage(page||'general');openModal(settingsModal);}
  function syncSwitches(){qa('[data-switch][data-setting]').forEach(el=>{const k=el.dataset.setting;if(Object.prototype.hasOwnProperty.call(settings,k))el.classList.toggle('on',!!settings[k]);});}
  function renderSettings(){
    syncSwitches();q('#playerSize').value=settings.playerSize;q('#playerSizeValue').textContent=settings.playerSize+' px';q('#playerColor').value=settings.playerColor;q('#playerSwatch').style.background=settings.playerColor;q('#playerColorText').textContent=settings.playerColor.toUpperCase();
    q('#markerScaleRange').value=settings.markerScale;q('#markerScaleValue').textContent=settings.markerScale+'%';q('#markerDefaultColor').value=settings.markerDefaultColor;q('#markerDefaultSwatch').style.background=settings.markerDefaultColor;q('#markerDefaultColorText').textContent=settings.markerDefaultColor.toUpperCase();
    q('#markerDefaultSize').value=settings.markerDefaultSize;q('#markerDefaultSizeValue').textContent=settings.markerDefaultSize+' px';updateIconSelect(q('#markerDefaultIconSelect'),settings.markerDefaultIcon);
    qa('[data-player-icon]').forEach(el=>el.classList.toggle('active',el.dataset.playerIcon===settings.playerIcon));
  }
  function updateIconSelect(el,icon){const left=q('.left',el);if(!left)return;left.innerHTML='<svg class="icon"><use href="#'+iconSymbol(icon)+'"/></svg><span>'+escapeHtml(iconLabel(icon))+'</span>';el.dataset.value=icon;}
  function openIconDropdown(anchor,current,onSelect){
    const icons=['pin','home','mine','ship','mountain','temple','village','mansion','outpost','portal','city','fortress','cave','water'];
    dropdown.innerHTML=icons.map(i=>'<div class="dropdown-item '+(i===current?'active':'')+'" data-icon="'+i+'"><svg class="icon"><use href="#'+iconSymbol(i)+'"/></svg>'+escapeHtml(iconLabel(i))+'</div>').join('');
    const r=anchor.getBoundingClientRect();dropdown.style.left=Math.max(8,Math.min(innerWidth-220,r.left))+'px';dropdown.style.top=Math.min(innerHeight-290,r.bottom+4)+'px';dropdown.classList.add('open');
    qa('.dropdown-item',dropdown).forEach(it=>it.onclick=()=>{dropdown.classList.remove('open');onSelect(it.dataset.icon);});
  }

  function openEditor(marker,point){
    selectedMarker=marker||null;editorMode=marker?'edit':'add';const src=marker||point||{x:Math.round(view.centerX),y:serverState&&serverState.player?serverState.player.y:64,z:Math.round(view.centerZ)};
    q('#editorTitle').textContent=marker?'编辑标点':'添加标点';q('#markerName').value=marker?marker.name:'新标点';q('#markerDescription').value=marker&&marker.description||'';
    q('#markerX').value=src.x;q('#markerY').value=src.y;q('#markerZ').value=src.z;currentEditorIcon=marker&&marker.icon||settings.markerDefaultIcon;updateIconSelect(q('#markerIconSelect'),currentEditorIcon);
    const color=marker&&marker.accentColor||settings.markerDefaultColor;q('#markerColor').value=color;q('#markerSwatch').style.background=color;q('#markerColorText').textContent=color.toUpperCase();
    q('#deleteMarkerButton').style.visibility=marker?'visible':'hidden';q('#deleteMarkerButtonText').textContent=marker&&marker.modifiedAuto?'重置为自动':'删除';q('#deleteMarkerButton').classList.toggle('danger',!(marker&&marker.modifiedAuto));openModal(editorModal);
  }
  async function saveEditor(){
    const name=q('#markerName').value.trim(),x=Number(q('#markerX').value),y=Number(q('#markerY').value),z=Number(q('#markerZ').value);if(!name){toast('请输入标点名称');return;}if(![x,y,z].every(Number.isFinite)){toast('请输入有效坐标');return;}
    const payload={name:name,icon:currentEditorIcon,primaryColor:0xffffff,accentColor:colorInt(q('#markerColor').value),lineColor:0x202020,description:q('#markerDescription').value.trim(),x:Math.round(x),y:Math.round(y),z:Math.round(z)};
    try{if(editorMode==='edit'&&selectedMarker)await api('/api/v1/markers/'+selectedMarker.id,{method:'PUT',body:JSON.stringify(payload)});else await api('/api/v1/markers',{method:'POST',body:JSON.stringify(payload)});closeModal();toast(editorMode==='edit'?'标点已保存':'标点已添加');await pollStateOnce();}catch(e){toast('保存失败：'+e.message);}
  }
  async function deleteOrReset(){
    if(!selectedMarker)return;try{if(selectedMarker.modifiedAuto){await api('/api/v1/markers/'+selectedMarker.id+'/reset',{method:'POST'});toast('已重置为自动结构');}else{await api('/api/v1/markers/'+selectedMarker.id,{method:'DELETE'});toast('已删除');}closeModal();closeContexts();await pollStateOnce();}catch(e){toast('操作失败：'+e.message);}
  }
  async function teleport(x,z){try{await api('/api/v1/teleport',{method:'POST',body:JSON.stringify({x:Math.round(x),z:Math.round(z)})});toast('已请求传送至 '+Math.round(x)+', '+Math.round(z));}catch(e){toast('传送失败：'+e.message);}}

  function enablePanelDrag(panel){
    const head=q('.panel-head',panel);if(!head)return;let moving=false,dx=0,dy=0;
    head.addEventListener('pointerdown',e=>{if(e.target.closest('button'))return;moving=true;const pr=panel.getBoundingClientRect(),sr=mapStage.getBoundingClientRect();dx=e.clientX-pr.left;dy=e.clientY-pr.top;head.setPointerCapture(e.pointerId);panel.style.left=(pr.left-sr.left)+'px';panel.style.top=(pr.top-sr.top)+'px';});
    head.addEventListener('pointermove',e=>{if(!moving)return;const sr=mapStage.getBoundingClientRect(),left=Math.max(8,Math.min(mapStage.clientWidth-panel.offsetWidth-8,e.clientX-sr.left-dx)),top=Math.max(8,Math.min(mapStage.clientHeight-panel.offsetHeight-8,e.clientY-sr.top-dy));panel.style.left=left+'px';panel.style.top=top+'px';});
    head.addEventListener('pointerup',()=>moving=false);head.addEventListener('pointercancel',()=>moving=false);
  }

  function patchCopy(){
    const v=q('.about-version');if(v)v.textContent='Cartograph V3.1 · WebUI';
    const n=q('.about-note');if(n)n.textContent='此页面就是正式 WebUI 前端。世界采集、缓存和动作由 Minecraft Java 后端提供，界面由浏览器 HTML / CSS / SVG / Canvas 渲染。';
    qa('.about-grid .k').forEach(k=>{if(k.textContent.trim()==='缓存策略'&&k.nextElementSibling)k.nextElementSibling.textContent='只持久化地表事实；WebUI 按主题派生成图';});
    const rgb=qa('.form-row .label').find(el=>el.textContent.trim()==='已渲染 RGB 缓存');if(rgb){rgb.textContent='地表事实缓存';if(rgb.nextElementSibling)rgb.nextElementSibling.textContent='只保存世界事实；卫星成图由 WebUI 临时生成';const sw=rgb.parentElement.querySelector('[data-switch]');if(sw)sw.style.display='none';}
    const pre=q('#prewarmRange');if(pre)pre.closest('.performance-row').style.display='none';
    const key=q('[data-shortcut="map"]');if(key){const t=key.closest('.form-row').querySelector('.shortcut span');if(t)t.textContent='在 Minecraft 中按 M 打开 WebUI';}
    qa('[data-marker-pane="trash"]').forEach(el=>el.style.display='none');qa('[data-marker-pane-content="trash"]').forEach(el=>el.style.display='none');q('#homeBtn').style.display='none';
  }

  function bind(){
    qa('.nav-btn').forEach(b=>b.onclick=()=>{const n=b.dataset.nav;if(n==='map')hidePanels();else if(n==='layers'||n==='markers')openPanel(n);else if(n==='settings')openSettings();else if(n==='about')openModal(aboutModal);});
    qa('[data-panel-close]').forEach(b=>b.onclick=hidePanels);qa('[data-about-close]').forEach(b=>b.onclick=closeModal);qa('[data-editor-close]').forEach(b=>b.onclick=closeModal);qa('[data-modal-close]').forEach(b=>b.onclick=closeModal);
    qa('.settings-nav-item').forEach(el=>el.onclick=()=>activateSettingsPage(el.dataset.page));qa('.subtab').forEach(tab=>tab.onclick=()=>{qa('.subtab').forEach(x=>x.classList.remove('active'));tab.classList.add('active');qa('[data-subpage]').forEach(x=>x.classList.toggle('hidden',x.dataset.subpage!==tab.dataset.sub));});
    qa('[data-switch][data-setting]').forEach(sw=>sw.onclick=()=>{const k=sw.dataset.setting;if(!Object.prototype.hasOwnProperty.call(settings,k))return;settings[k]=!settings[k];saveSettings();syncSwitches();scheduleRender();});
    qa('[data-player-icon]').forEach(b=>b.onclick=()=>{settings.playerIcon=b.dataset.playerIcon;saveSettings();renderSettings();scheduleRender();});
    q('#playerSize').oninput=e=>{settings.playerSize=Number(e.target.value);saveSettings();renderSettings();scheduleRender();};q('#playerColor').oninput=e=>{settings.playerColor=e.target.value.toUpperCase();saveSettings();renderSettings();scheduleRender();};
    q('#markerScaleRange').oninput=e=>{settings.markerScale=Number(e.target.value);saveSettings();renderSettings();scheduleRender();};q('#markerDefaultColor').oninput=e=>{settings.markerDefaultColor=e.target.value.toUpperCase();saveSettings();renderSettings();};
    q('#markerDefaultSize').oninput=e=>{settings.markerDefaultSize=Number(e.target.value);saveSettings();renderSettings();scheduleRender();};q('#markerDefaultIconSelect').onclick=()=>openIconDropdown(q('#markerDefaultIconSelect'),settings.markerDefaultIcon,i=>{settings.markerDefaultIcon=i;saveSettings();renderSettings();});
    q('#markerIconSelect').onclick=()=>openIconDropdown(q('#markerIconSelect'),currentEditorIcon,i=>{currentEditorIcon=i;updateIconSelect(q('#markerIconSelect'),i);});q('#markerColor').oninput=e=>{q('#markerSwatch').style.background=e.target.value;q('#markerColorText').textContent=e.target.value.toUpperCase();};
    let rt=0;const sendRuntime=()=>{clearTimeout(rt);rt=setTimeout(async()=>{try{await api('/api/v1/runtime-settings',{method:'POST',body:JSON.stringify({requestBatchSize:Number(q('#requestRange').value),liveScanIntervalTicks:Number(q('#scanRange').value)})});}catch(_){}},180);};
    q('#requestRange').oninput=e=>{q('#requestValue').textContent=e.target.value;sendRuntime();};q('#scanRange').oninput=e=>{const v=Number(e.target.value);q('#scanValue').textContent=v+' tick / '+(v/20).toFixed(2)+' s';sendRuntime();};
    q('#zoomIn').onclick=()=>setZoom(view.zoom*1.25);q('#zoomOut').onclick=()=>setZoom(view.zoom/1.25);q('#locateBtn').onclick=centerPlayer;
    mapStage.addEventListener('wheel',e=>{if(e.target.closest('.floating-panel,.context-menu,.right-controls,.coord-box,.scale-box'))return;e.preventDefault();closeContexts();setZoom(e.deltaY<0?view.zoom*1.12:view.zoom/1.12,e.clientX,e.clientY);},{passive:false});
    mapStage.addEventListener('pointerdown',e=>{if(e.button!==0||!settings.dragMap||e.target.closest('.floating-panel,.context-menu,.right-controls,.map-marker'))return;closeContexts();dragging=true;dragX=e.clientX;dragY=e.clientY;mapStage.classList.add('dragging');mapStage.setPointerCapture(e.pointerId);});
    mapStage.addEventListener('pointermove',e=>{updateCursor(e.clientX,e.clientY);if(!dragging)return;const dx=e.clientX-dragX,dy=e.clientY-dragY;dragX=e.clientX;dragY=e.clientY;view.centerX-=dx/view.zoom;view.centerZ-=dy/view.zoom;scheduleRender();sendViewportSoon();});
    mapStage.addEventListener('pointerup',()=>{if(dragging){dragging=false;mapStage.classList.remove('dragging');saveView();sendViewportSoon();}});
    mapStage.addEventListener('contextmenu',e=>{if(e.target.closest('.floating-panel,.context-menu,.right-controls,.map-marker'))return;e.preventDefault();openMapContext(e.clientX,e.clientY);});
    q('#ctxAdd').onclick=()=>openEditor(null,contextPoint);q('#ctxTeleport').onclick=()=>{if(contextPoint)teleport(contextPoint.x,contextPoint.z);closeContexts();};q('#ctxCopy').onclick=async()=>{if(!contextPoint)return;const t=contextPoint.x+', '+(contextPoint.y==null?'':contextPoint.y)+', '+contextPoint.z;try{await navigator.clipboard.writeText(t);}catch(_){}toast('已复制坐标：'+t);closeContexts();};
    q('#markerTeleport').onclick=()=>{if(selectedMarker)teleport(selectedMarker.x,selectedMarker.z);closeContexts();};q('#ctxEdit').onclick=()=>{if(selectedMarker)openEditor(selectedMarker);};q('#ctxDelete').onclick=deleteOrReset;
    q('#addMarkerButton').onclick=async()=>{const x=Math.round(view.centerX),z=Math.round(view.centerZ);let p={x:x,y:serverState&&serverState.player?serverState.player.y:64,z:z};try{p=await queryPoint(x,z);}catch(_){}openEditor(null,p);};
    q('#saveMarkerButton').onclick=saveEditor;q('#deleteMarkerButton').onclick=deleteOrReset;q('#structureExpand').onclick=()=>q('#structureGroup').classList.toggle('expanded');
    qa('.checkbox[data-toggle]').forEach(cb=>cb.onclick=()=>{const k=cb.dataset.toggle;settings[k]=!settings[k];saveSettings();renderLayerList();syncSwitches();scheduleRender();});
    q('#recolorCache').onclick=()=>{tileCache.clear();scheduleRender();toast('浏览器成图缓存已清除，将从世界事实重新着色');};q('#clearGpuCache').onclick=()=>{tileCache.clear();scheduleRender();toast('浏览器地图纹理缓存已清除');};q('#refreshAll').onclick=()=>{tileCache.clear();sendViewportSoon();scheduleRender();toast('当前视野将重新读取世界事实');};
    q('.window-close').onclick=()=>{window.close();setTimeout(()=>toast('可直接关闭此浏览器标签页'),80);};
    document.addEventListener('pointerdown',e=>{if(dropdown.classList.contains('open')&&!e.target.closest('#dropdown,.select,.structure-select'))dropdown.classList.remove('open');if(!e.target.closest('.context-menu,.map-marker'))closeContexts();});
    document.addEventListener('keydown',e=>{if(e.key==='Escape'){if(dropdown.classList.contains('open'))dropdown.classList.remove('open');else if(settingsModal.style.display==='block'||editorModal.style.display==='block'||aboutModal.style.display==='block')closeModal();else closeContexts();}if((e.key==='r'||e.key==='R')&&!e.ctrlKey&&!e.altKey)centerPlayer();});
    enablePanelDrag(layersPanel);enablePanelDrag(markersPanel);
  }

  new ResizeObserver(resizeCanvas).observe(mapStage);
  patchCopy();bind();renderSettings();renderHud();resizeCanvas();setConnection(false);app.dataset.webui='loaded';pollState();
})();
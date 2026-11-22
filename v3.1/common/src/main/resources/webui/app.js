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
  const REGION_CHUNKS = 32;
  const REGION_PIXELS = TILE_SIZE * REGION_CHUNKS;
  const MAX_REGION_CACHE = 48;
  const MIN_ZOOM = 0.0625;
  const MAX_ZOOM = 64;
  const DRAW_TERRAIN = 1;
  const DRAW_PLAYER = 2;
  const DRAW_MARKERS = 4;
  const DRAW_HUD = 8;
  const DRAW_ALL = DRAW_TERRAIN | DRAW_PLAYER | DRAW_MARKERS | DRAW_HUD;
  const HIDDEN_BY_DEFAULT = new Set([
    'mineshaft','trial_chambers','ancient_city','stronghold','buried_treasure'
  ]);
  const DRAWING_MODES = Object.freeze({
    satellite:{id:'satellite',label:'卫星'},
    hand_drawn:{id:'hand_drawn',label:'手绘'}
  });
  const VISUAL_STYLES = Object.freeze({
    modern:{id:'modern',label:'现代',layout:'modern-atlas',iconFamily:'modern',density:'comfortable',typography:'sans'},
    ancient:{id:'ancient',label:'古式',layout:'ancient-atlas',iconFamily:'engraved',density:'comfortable',typography:'serif'},
    anime:{id:'anime',label:'动漫',layout:'modern-atlas',iconFamily:'modern',density:'comfortable',typography:'sans'}
  });
  const MAP_RENDERERS = Object.freeze({
    satellite:renderMapTerrain,
    hand_drawn:renderMapTerrain
  });

  const defaults = {
    showCoords:true, playerRing:true, labels:true, custom:true, structures:true,
    grid:false, dragMap:true, playerFixed:true, playerIcon:'arrow',
    playerSize:24, playerColor:'#FFE14E', markerScale:35,
    markerDefaultIcon:'pin', markerDefaultColor:'#FFD34E',
    markerDefaultSize:22, hideLabelsLowZoom:true, avoidLabels:true,
    drawingMode:'satellite', visualStyle:'modern', debug:false, structureFilters:{}
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
  let queuedDraw = 0;
  let viewportTimer = 0;
  let cursorTimer = 0;
  let cursorTarget = null;
  let cursorRequestInFlight = false;
  let toastTimer = 0;
  let socket = null;
  let socketRetryMs = 1000;
  let socketReconnectTimer = 0;
  let socketRequestId = 0;
  let lastViewportSignature = '';
  let viewportRequestInFlight = false;
  let viewportRequestQueued = false;
  const pendingSocketRequests = new Map();
  let markerUiSignature = null;
  let presetUiSignature = null;
  const structurePresetChanges = new Map();
  let resetStructurePresets = false;
  let applyingStructurePresets = false;

  const tileCache = new Map();
  const regionCache = new Map();
  const markerNodes = new Map();
  let regionClock = 0;

  function clone(v){ return JSON.parse(JSON.stringify(v)); }
  function loadJson(key, fallback){
    try{
      const v = JSON.parse(localStorage.getItem(key) || 'null');
      return v && typeof v === 'object' ? v : clone(fallback);
    }catch(_){ return clone(fallback); }
  }
  function saveJson(key,value){
    try{localStorage.setItem(key,JSON.stringify(value));}
    catch(_){/* The current session still uses the in-memory state. */}
  }
  function saveSettings(){ saveJson(LOCAL_KEY,settings); }
  function currentDrawingMode(){return DRAWING_MODES[settings.drawingMode]||DRAWING_MODES.satellite;}
  function currentVisualStyle(){return VISUAL_STYLES[settings.visualStyle]||VISUAL_STYLES.modern;}
  function presentationLabel(){
    return currentDrawingMode().label+' · '+currentVisualStyle().label;
  }
  function applyPresentation(){
    const mode=currentDrawingMode(),style=currentVisualStyle();
    settings.drawingMode=mode.id;
    settings.visualStyle=style.id;
    app.dataset.theme=style.id;
    app.dataset.themeLayout=style.layout;
    app.dataset.themeIcons=style.iconFamily;
    app.dataset.themeDensity=style.density;
    app.dataset.themeTypography=style.typography;
    app.dataset.mapRenderer=mode.id;
    app.dataset.drawingMode=mode.id;
    app.dataset.visualStyle=style.id;
    document.documentElement.dataset.cartographTheme=style.id;
    applyThemeUiIcons();
    const label=presentationLabel();
    const activeThemeLabel=q('#activeThemeLabel');if(activeThemeLabel)activeThemeLabel.textContent=label;
    const interfaceThemeValue=q('#interfaceThemeValue');if(interfaceThemeValue)interfaceThemeValue.textContent=label;
    const aboutTheme=q('#aboutThemeValue');if(aboutTheme)aboutTheme.textContent=label;
    const aboutRenderer=q('#aboutThemeRenderer');if(aboutRenderer)aboutRenderer.textContent=mode.label+'数据 · '+style.label+'风格';
  }
  async function selectDrawingMode(id){
    if(!DRAWING_MODES[id])return;
    const previous=settings.drawingMode;
    settings.drawingMode=id;saveSettings();applyPresentation();renderSettings();refreshStaticUi(true);scheduleRender(DRAW_ALL);
    if(socket&&socket.readyState===WebSocket.OPEN){
      try{await wsRequest('runtime_settings',{payload:{drawingMode:id}});refreshPresentationTerrain();}
      catch(_){
        settings.drawingMode=previous;saveSettings();applyPresentation();renderSettings();refreshStaticUi(true);scheduleRender(DRAW_ALL);
        toast('绘图方式保存失败');return;
      }
    }
    toast('绘图方式：'+currentDrawingMode().label);
  }
  async function selectVisualStyle(id){
    if(!VISUAL_STYLES[id])return;
    const previous=settings.visualStyle;
    settings.visualStyle=id;saveSettings();applyPresentation();renderSettings();refreshStaticUi(true);scheduleRender(DRAW_ALL);
    if(socket&&socket.readyState===WebSocket.OPEN){
      try{await wsRequest('runtime_settings',{payload:{visualStyle:id}});refreshPresentationTerrain();}
      catch(_){
        settings.visualStyle=previous;saveSettings();applyPresentation();renderSettings();refreshStaticUi(true);scheduleRender(DRAW_ALL);
        toast('绘图风格保存失败');return;
      }
    }
    toast('绘图风格：'+currentVisualStyle().label);
  }
  function refreshPresentationTerrain(){
    regionCache.clear();tileCache.clear();lastViewportSignature='';
    scheduleRender(DRAW_TERRAIN);
    if(socket&&socket.readyState===WebSocket.OPEN)sendViewportSoon();
  }
  function worldKey(s){ return String(s && s.worldId || '') + '|' + String(s && s.dimension || ''); }
  function saveView(){
    if(!serverState)return;
    saveJson(VIEW_KEY,{
      worldKey:worldKey(serverState), centerX:view.centerX, centerZ:view.centerZ, zoom:view.zoom
    });
  }

  function connectSocket(){
    if(!token||!window.WebSocket)return;
    clearTimeout(socketReconnectTimer);
    const url=new URL('/api/v1/ws',location.href);
    url.protocol=location.protocol==='https:'?'wss:':'ws:';
    url.searchParams.set('token',token);
    const current=new WebSocket(url);
    current.binaryType='arraybuffer';
    socket=current;
    current.onopen=()=>{if(socket!==current)return;socketRetryMs=1000;lastViewportSignature='';setConnection(true);};
    current.onmessage=event=>{
      if(socket!==current)return;
      if(event.data instanceof ArrayBuffer){applyTileBatch(event.data);return;}
      if(typeof event.data!=='string')return;
      try{handleSocketMessage(JSON.parse(event.data));}catch(_){}
    };
    current.onclose=()=>{
      if(socket!==current)return;
      socket=null;setConnection(false);
      cancelCursorLookup();
      for(const [id,p] of pendingSocketRequests){clearTimeout(p.timer);p.reject(new Error('WebSocket disconnected'));pendingSocketRequests.delete(id);}
      socketReconnectTimer=setTimeout(connectSocket,socketRetryMs);
      socketRetryMs=Math.min(10000,socketRetryMs*2);
    };
    current.onerror=()=>{setConnection(false);};
  }

  function handleSocketMessage(message){
    if(!message||typeof message!=='object')return;
    if(message.type==='state'){applyServerState(message.data);return;}
    if(message.type==='live'){applyLiveState(message.data);return;}
    if(message.type==='markers'&&serverState){
      serverState.markers=message.data&&message.data.markers||[];
      serverState.structurePresets=message.data&&message.data.structurePresets||[];
      refreshStaticUi(false);renderStatus();scheduleRender(DRAW_MARKERS);return;
    }
    if(message.type==='runtime'&&serverState){
      serverState.runtime=Object.assign({},serverState.runtime||{},message.data||{});
      syncRuntimeSettings();renderStatus();
      if(settings.debug)scheduleRender(DRAW_HUD);
      return;
    }
    if(message.type==='response'){
      const id=String(message.id==null?'':message.id),pending=pendingSocketRequests.get(id);
      if(!pending)return;
      pendingSocketRequests.delete(id);clearTimeout(pending.timer);
      if(message.ok)pending.resolve(message.data||{});
      else pending.reject(new Error(message.error||'WebSocket request failed'));
      return;
    }
    if(message.type==='error')toast(message.error||'WebSocket error');
  }

  function applyServerState(next){
    if(!next)return;
    const nextWorldKey=worldKey(next),worldChanged=nextWorldKey!==lastWorldKey;
    serverState=next;currentDimension=next.dimension||'';
    setConnection(!!next.connected);
    if(worldChanged){
      cancelCursorLookup();
      lastWorldKey=nextWorldKey;tileCache.clear();regionCache.clear();
      markerUiSignature=null;presetUiSignature=null;lastViewportSignature='';
      viewInitialized=false;
    }
    if(!viewInitialized)initView(next);
    renderStatus();refreshStaticUi(worldChanged);syncRuntimeSettings();scheduleRender();
    sendViewportSoon();
  }

  function applyLiveState(live){
    if(!live||!serverState)return;
    const nextWorldKey=worldKey(live);
    if(lastWorldKey&&nextWorldKey!==lastWorldKey){
      wsRequest('request_state').catch(()=>{});
      return;
    }
    const wasConnected=!!serverState.connected;
    const previousPlayer=serverState.player;
    const previousRuntime=serverState.runtime;
    serverState.connected=!!live.connected;
    serverState.dimension=live.dimension||serverState.dimension;
    serverState.worldId=live.worldId||serverState.worldId;
    currentDimension=live.dimension||currentDimension;
    setConnection(serverState.connected);
    if(live.player)serverState.player=live.player;
    if(live.runtime)serverState.runtime={...previousRuntime,...live.runtime};

    if(live.runtime&&(previousRuntime?.drawingMode!==serverState.runtime?.drawingMode||
                     previousRuntime?.visualStyle!==serverState.runtime?.visualStyle||
                     previousRuntime?.frontendTarget!==serverState.runtime?.frontendTarget||
                     previousRuntime?.requestBatchSize!==serverState.runtime?.requestBatchSize||
                     previousRuntime?.liveScanIntervalTicks!==serverState.runtime?.liveScanIntervalTicks)){
      syncRuntimeSettings();
    }
    if(previousRuntime?.cachedCount!==serverState.runtime?.cachedCount)renderStatus();
    if(wasConnected!==serverState.connected){
      cancelCursorLookup();
      scheduleRender();
      return;
    }

    const player=live.player;
    if(player&&(player.present!==previousPlayer?.present||
               player.x!==previousPlayer?.x||player.z!==previousPlayer?.z||
               player.yaw!==previousPlayer?.yaw)){
      scheduleRender(DRAW_PLAYER);
    }
    if(settings.debug&&previousRuntime?.pendingCount!==serverState.runtime?.pendingCount){
      scheduleRender(DRAW_HUD);
    }
  }

  function wsRequest(type,fields){
    if(!socket||socket.readyState!==WebSocket.OPEN)return Promise.reject(new Error('WebSocket 尚未连接'));
    const id=String(++socketRequestId),message=Object.assign({type:type,id:id},fields||{});
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{pendingSocketRequests.delete(id);reject(new Error('WebSocket 请求超时'));},10000);
      pendingSocketRequests.set(id,{resolve:resolve,reject:reject,timer:timer});
      try{socket.send(JSON.stringify(message));}
      catch(error){clearTimeout(timer);pendingSocketRequests.delete(id);reject(error);}
    });
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

  const THEME_UI_ICON_SYMBOLS = Object.freeze({
    modern:Object.freeze({
      map:'i-theme-modern-map',markers:'i-theme-modern-pin',layers:'i-theme-modern-layers',
      settings:'i-theme-modern-settings',about:'i-theme-modern-info',locate:'i-theme-modern-locate',
      home:'i-theme-modern-home'
    }),
    engraved:Object.freeze({
      map:'i-theme-engraved-map',markers:'i-theme-engraved-pin',layers:'i-theme-engraved-layers',
      settings:'i-theme-engraved-settings',about:'i-theme-engraved-info',locate:'i-theme-engraved-locate',
      home:'i-theme-engraved-home'
    })
  });
  const BASE_UI_ICON_SYMBOLS = Object.freeze({
    map:'i-map',markers:'i-pin',layers:'i-layers',settings:'i-settings',about:'i-info',
    locate:'i-locate',home:'i-home'
  });
  function uiIconSymbol(raw){
    const semantic=String(raw||'').toLowerCase();
    const family=currentVisualStyle().iconFamily;
    const themed=THEME_UI_ICON_SYMBOLS[family];
    return themed&&themed[semantic] ? themed[semantic] : (BASE_UI_ICON_SYMBOLS[semantic]||'i-info');
  }
  function applyThemeUiIcons(){
    qa('[data-theme-ui-icon]').forEach(use=>use.setAttribute('href','#'+uiIconSymbol(use.dataset.themeUiIcon)));
  }

  const THEME_ICON_SYMBOLS = Object.freeze({
    modern:Object.freeze({
      pin:'i-theme-modern-pin',home:'i-theme-modern-home',camp:'i-theme-modern-home',
      village:'i-theme-modern-village',mountain:'i-theme-modern-mountain',
      mountain_range:'i-theme-modern-mountain',water:'i-theme-modern-water',
      lake:'i-theme-modern-water',river:'i-theme-modern-water'
    }),
    engraved:Object.freeze({
      pin:'i-theme-engraved-pin',home:'i-theme-engraved-home',camp:'i-theme-engraved-home',
      village:'i-theme-engraved-village',mountain:'i-theme-engraved-mountain',
      mountain_range:'i-theme-engraved-mountain',water:'i-theme-engraved-water',
      lake:'i-theme-engraved-water',river:'i-theme-engraved-water'
    })
  });
  function baseIconSymbol(raw){
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
  function iconSymbol(raw){
    const semantic=String(raw||'pin').toLowerCase();
    const family=currentVisualStyle().iconFamily;
    const themed=THEME_ICON_SYMBOLS[family];
    return themed&&themed[semantic] ? themed[semantic] : baseIconSymbol(semantic);
  }
  function iconLabel(raw){
    const i=String(raw||'pin').toLowerCase();
    const m={pin:'定位点',home:'营地',camp:'营地',mine:'矿井',ship:'船 / 码头',
      mountain:'山峰',temple:'神殿',village:'村庄',mansion:'林地府邸',
      outpost:'前哨站',portal:'传送门',city:'城市',fortress:'要塞',
      cave:'洞穴',water:'水域',shipwreck:'沉船',desert_pyramid:'沙漠神殿',
      jungle_temple:'丛林神庙',swamp_hut:'沼泽小屋',igloo:'雪屋',
      ocean_monument:'海底神殿',ruined_portal:'废弃传送门',ocean_ruin:'海底废墟',
      buried_treasure:'埋藏的宝藏',trail_ruins:'古迹废墟',trial_chambers:'试炼密室',
      ancient_city:'远古城市',stronghold:'末影要塞',nether_fortress:'下界要塞',
      bastion_remnant:'堡垒遗迹',nether_fossil:'下界化石',end_city:'末地城'};
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
    for(const marker of markerNodes.values())marker._labelLayout=null;
    scheduleRender();sendViewportSoon();
  }
  function resizeTransform(s){
    const dpr=Math.max(1,window.devicePixelRatio||1);
    const w=Math.max(1,Math.round(s.width*dpr)),h=Math.max(1,Math.round(s.height*dpr));
    if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}
    ctx.setTransform(dpr,0,0,dpr,0,0);
    return dpr;
  }
  function worldToScreen(x,z,s){
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
    lastViewportSignature='';saveView();scheduleRender();sendViewportSoon();
  }
  function centerPlayer(){
    const p=serverState&&serverState.player;if(!p||!p.present)return;
    view.centerX=p.x;view.centerZ=p.z;lastViewportSignature='';saveView();scheduleRender();sendViewportSoon();toast('已回到玩家位置');
  }

  function scheduleRender(flags=DRAW_ALL){
    if(!queuedDraw)requestAnimationFrame(renderFrame);
    queuedDraw|=flags;
  }
  function renderFrame(){
    const flags=queuedDraw;
    queuedDraw=0;
    if(flags&DRAW_HUD)renderHud();
    if(!(flags&(DRAW_TERRAIN|DRAW_PLAYER|DRAW_MARKERS)))return;

    const s=stageSize();
    if(flags&DRAW_TERRAIN)renderTerrain(s);
    if(flags&DRAW_PLAYER)renderPlayer(s);
    if(flags&DRAW_MARKERS)renderMarkers(s);
  }
  function renderTerrain(s){
    const mode=currentDrawingMode();
    const renderer=MAP_RENDERERS[mode.id]||MAP_RENDERERS.satellite;
    renderer(s);
  }
  function renderMapTerrain(s){
    const dpr=resizeTransform(s),style=currentVisualStyle();
    const background=style.id==='ancient'?'#bca06f':style.id==='anime'?'#dcecf2':'#e7ece8';
    ctx.save();ctx.fillStyle=background;ctx.fillRect(0,0,s.width,s.height);
    ctx.imageSmoothingEnabled=view.zoom<1;
    if(serverState&&serverState.connected&&currentDimension){
      const hx=s.width/(2*view.zoom),hz=s.height/(2*view.zoom);
      const minX=Math.floor((view.centerX-hx)/16)-1,maxX=Math.floor((view.centerX+hx)/16)+1;
      const minZ=Math.floor((view.centerZ-hz)/16)-1,maxZ=Math.floor((view.centerZ+hz)/16)+1;
      const minRx=Math.floor(minX/REGION_CHUNKS),maxRx=Math.floor(maxX/REGION_CHUNKS);
      const minRz=Math.floor(minZ/REGION_CHUNKS),maxRz=Math.floor(maxZ/REGION_CHUNKS);
      for(let rz=minRz;rz<=maxRz;rz++){
        const top=atlasEdge(rz,view.centerZ,s.height,dpr);
        const bottom=atlasEdge(rz+1,view.centerZ,s.height,dpr);
        for(let rx=minRx;rx<=maxRx;rx++){
          const region=regionCache.get(regionKey(rx,rz));
          if(!region)continue;
          region.lastUse=++regionClock;
          const left=atlasEdge(rx,view.centerX,s.width,dpr);
          const right=atlasEdge(rx+1,view.centerX,s.width,dpr);
          ctx.drawImage(region.canvas,left,top,right-left,bottom-top);
        }
      }
    }
    if(settings.grid)drawChunkGrid(s.width,s.height);
    ctx.restore();
  }

  function atlasEdge(region,center,viewportSize,dpr){
    // Adjacent atlases share the same physical pixel edge at fractional zoom.
    const position=viewportSize/2+(region*REGION_PIXELS-center)*view.zoom;
    return Math.round(position*dpr)/dpr;
  }

  function drawChunkGrid(width,height){
    const chunkPixels=TILE_SIZE*view.zoom;
    // At low zoom use a power-of-two chunk interval to keep terrain readable.
    const spacing=chunkPixels*2**Math.max(0,Math.ceil(Math.log2(6/chunkPixels)));
    const originX=width/2-view.centerX*view.zoom;
    const originY=height/2-view.centerZ*view.zoom;

    ctx.beginPath();
    for(let x=floorMod(originX,spacing);x<width;x+=spacing){
      ctx.moveTo(x,0);
      ctx.lineTo(x,height);
    }
    for(let y=floorMod(originY,spacing);y<height;y+=spacing){
      ctx.moveTo(0,y);
      ctx.lineTo(width,y);
    }
    ctx.lineWidth=1;
    ctx.strokeStyle=getComputedStyle(app).getPropertyValue('--line').trim()||'#46555f';
    ctx.globalAlpha=.38;
    ctx.stroke();
    ctx.globalAlpha=1;
  }

  function tileKey(x,z){return currentDimension+'|'+x+'|'+z;}
  function regionKey(rx,rz){return currentDimension+'|r|'+rx+'|'+rz;}
  function floorMod(value,mod){return ((value%mod)+mod)%mod;}
  function ensureRegion(cx,cz){
    const rx=Math.floor(cx/REGION_CHUNKS),rz=Math.floor(cz/REGION_CHUNKS),key=regionKey(rx,rz);
    let region=regionCache.get(key);
    if(!region){
      const c=document.createElement('canvas');c.width=REGION_PIXELS;c.height=REGION_PIXELS;
      const rc=c.getContext('2d',{alpha:true});rc.imageSmoothingEnabled=false;
      region={key:key,rx:rx,rz:rz,canvas:c,ctx:rc,lastUse:++regionClock};
      regionCache.set(key,region);evictRegions();
    }else region.lastUse=++regionClock;
    return region;
  }
  function evictRegions(){
    while(regionCache.size>MAX_REGION_CACHE){
      let oldest=null;
      for(const region of regionCache.values())if(!oldest||region.lastUse<oldest.lastUse)oldest=region;
      if(!oldest)return;
      regionCache.delete(oldest.key);
      for(const [key,tile] of tileCache)if(tile.regionKey===oldest.key)tileCache.delete(key);
    }
  }
  function writeTileToRegion(cx,cz,bytes){
    const region=ensureRegion(cx,cz),image=new ImageData(TILE_SIZE,TILE_SIZE);
    for(let i=0;i<TILE_SIZE*TILE_SIZE;i++){
      image.data[i*4]=bytes[i*3];image.data[i*4+1]=bytes[i*3+1];image.data[i*4+2]=bytes[i*3+2];image.data[i*4+3]=255;
    }
    region.ctx.putImageData(image,floorMod(cx,REGION_CHUNKS)*TILE_SIZE,floorMod(cz,REGION_CHUNKS)*TILE_SIZE);
    return region.key;
  }
  function clearTileFromRegion(tile,cx,cz){
    if(!tile||!tile.regionKey)return false;
    const region=regionCache.get(tile.regionKey);if(!region)return false;
    region.ctx.clearRect(floorMod(cx,REGION_CHUNKS)*TILE_SIZE,floorMod(cz,REGION_CHUNKS)*TILE_SIZE,TILE_SIZE,TILE_SIZE);
    return true;
  }
  function applyTileBatch(buffer){
    if(!(buffer instanceof ArrayBuffer)||buffer.byteLength<8||!currentDimension)return;
    const batchDimension=currentDimension,viewData=new DataView(buffer),decoder=new TextDecoder();
    let offset=0;
    if(viewData.getUint32(offset)!==0x43544231)return;offset+=4;
    const count=viewData.getUint32(offset);offset+=4;if(count>512)return;
    const now=Date.now();
    let terrainChanged=false;
    for(let n=0;n<count;n++){
      if(offset+15>viewData.byteLength)return;
      const cx=viewData.getInt32(offset);offset+=4;const cz=viewData.getInt32(offset);offset+=4;
      const status=viewData.getUint8(offset);offset+=1;const tagLen=viewData.getUint16(offset);offset+=2;
      if(offset+tagLen+4>viewData.byteLength)return;
      const etag=tagLen?decoder.decode(new Uint8Array(buffer,offset,tagLen)):'';offset+=tagLen;
      const bodyLen=viewData.getUint32(offset);offset+=4;
      if(offset+bodyLen>viewData.byteLength)return;
      const key=tileKey(cx,cz),old=tileCache.get(key);
      if(status===0){
        if(clearTileFromRegion(old,cx,cz))terrainChanged=true;
        tileCache.set(key,{cx:cx,cz:cz,etag:'',checkedAt:now,missing:true});
      }else if(status===2){
        if(old){old.checkedAt=now;old.etag=etag;old.missing=false;}
        offset+=bodyLen;
      }else if(status===1){
        if(bodyLen!==TILE_SIZE*TILE_SIZE*3){offset+=bodyLen;continue;}
        const bytes=new Uint8Array(buffer,offset,bodyLen),rk=writeTileToRegion(cx,cz,bytes);offset+=bodyLen;
        tileCache.set(key,{cx:cx,cz:cz,regionKey:rk,etag:etag,checkedAt:now,missing:false});
        terrainChanged=true;
      }else offset+=bodyLen;
    }
    if(batchDimension!==currentDimension)return;
    if(terrainChanged)scheduleRender(DRAW_TERRAIN);
    if(settings.debug)scheduleRender(DRAW_HUD);
  }

  function renderPlayer(s){
    const p=serverState&&serverState.player;
    if(!p||!p.present){playerEl.style.display='none';playerRingEl.style.display='none';return;}
    const pos=worldToScreen(p.x,p.z,s);
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
  function renderMarkers(s){
    const markers=serverState?.markers||[];
    const activeIds=new Set();
    const showLabels=settings.labels&&!(settings.hideLabelsLowZoom&&view.zoom<.25);
    const avoidLabels=showLabels&&settings.avoidLabels;
    const labelCandidates=[];
    const factor=Math.pow(Math.max(.15,Math.min(4,view.zoom)),.2*(settings.markerScale/100));

    for(const marker of markers){
      activeIds.add(marker.id);
      let el=markerNodes.get(marker.id);
      const structure=marker.kind==='structure';
      const preset=structure?structureGroupKey(marker):null;
      const pos=worldToScreen(marker.x,marker.z,s);
      const inView=pos.x>=-80&&pos.x<=s.width+80&&pos.y>=-80&&pos.y<=s.height+80;
      const layerEnabled=structure?settings.structures&&isStructureLayerEnabled(preset):settings.custom;
      if(!layerEnabled||!inView){
        if(el)el.style.display='none';
        continue;
      }

      if(!el){
        el=document.createElement('div');el.dataset.markerId=marker.id;
        el.innerHTML='<svg class="glyph icon"><use/></svg><div class="marker-label"></div>';
        el._glyph=q('.glyph',el);el._use=q('use',el);el._label=q('.marker-label',el);
        el.addEventListener('contextmenu',e=>{e.preventDefault();e.stopPropagation();const m=el._marker;if(m){selectedMarker=m;openMarkerContext(m,e.clientX,e.clientY);}});
        el.addEventListener('dblclick',e=>{e.preventDefault();const m=el._marker;if(m)openEditor(m);});
        markerLayer.appendChild(el);markerNodes.set(marker.id,el);
      }
      el._marker=marker;
      el.style.display='';
      let cssClass=structure?'map-marker structure':'map-marker custom';
      if(preset==='village')cssClass+=' village';
      if(el.className!==cssClass)el.className=cssClass;
      el.style.transform='translate3d('+pos.x+'px,'+pos.y+'px,0) translate(-50%,-50%)';
      el.style.color=marker.accentColor||(structure?'#FFFFFF':settings.markerDefaultColor);
      const size=Math.max(15,Math.min(48,(structure?27:settings.markerDefaultSize)*factor));
      el._glyph.style.width=size+'px';el._glyph.style.height=size+'px';
      const href='#'+iconSymbol(marker.icon);if(el._use.getAttribute('href')!==href)el._use.setAttribute('href',href);
      const label=el._label;
      label.style.display=showLabels?'':'none';
      const name=marker.name||iconLabel(marker.icon);
      if(label.textContent!==name){
        label.textContent=name;
        el._labelLayout=null;
      }

      if(avoidLabels){
        // Align the label order with the glyph stacking order.
        const priority=structure?(preset==='village'?1:2):0;
        labelCandidates.push({id:marker.id,priority,el,pos,size});
      }else{
        label.classList.remove('occluded');
      }
    }
    for(const [id,el] of markerNodes)if(!activeIds.has(id)){el.remove();markerNodes.delete(id);}
    if(avoidLabels)avoidMarkerLabelOverlaps(labelCandidates,s.rect);
  }

  function avoidMarkerLabelOverlaps(candidates,viewport){
    const padding=2;
    const cellSize=64;
    const occupied=new Map();

    candidates.sort((a,b)=>a.priority-b.priority||a.id.localeCompare(b.id));
    // Measure only when the label or glyph size changes. Panning translates the measured box.
    const placements=candidates.map(({el,pos,size})=>{
      const label=el._label;
      const x=viewport.left+pos.x;
      const y=viewport.top+pos.y;
      if(el._labelLayout?.size!==size){
        const rect=label.getBoundingClientRect();
        el._labelLayout={
          size,offsetX:rect.left-x,offsetY:rect.top-y,width:rect.width,height:rect.height
        };
      }
      const {offsetX,offsetY,width,height}=el._labelLayout;
      const left=x+offsetX;
      const top=y+offsetY;
      return {label,bounds:{left,top,right:left+width,bottom:top+height}};
    });
    for(const {label,bounds} of placements){
      if(bounds.right<=viewport.left||bounds.left>=viewport.right||
         bounds.bottom<=viewport.top||bounds.top>=viewport.bottom){
        label.classList.add('occluded');
        continue;
      }

      const box={
        left:bounds.left-padding,right:bounds.right+padding,
        top:bounds.top-padding,bottom:bounds.bottom+padding
      };
      const cells=[];
      for(let row=Math.floor(box.top/cellSize);row<=Math.floor(box.bottom/cellSize);row++){
        for(let column=Math.floor(box.left/cellSize);column<=Math.floor(box.right/cellSize);column++){
          cells.push(column+','+row);
        }
      }
      const overlaps=cells.some(cell=>occupied.get(cell)?.some(other=>
        box.left<other.right&&box.right>other.left&&box.top<other.bottom&&box.bottom>other.top
      ));
      label.classList.toggle('occluded',overlaps);
      if(overlaps)continue;

      for(const cell of cells){
        if(!occupied.has(cell))occupied.set(cell,[]);
        occupied.get(cell).push(box);
      }
    }
  }

  function renderHud(){
    q('#zoomText').textContent=Math.round(view.zoom*100)+'%';
    const blocks=145/Math.max(view.zoom,.0001);q('#scaleText').textContent=blocks>=1000?(blocks/1000).toFixed(1)+' km':Math.round(blocks)+' m';
    app.dataset.labels=settings.labels?'on':'off';
    app.dataset.structures=settings.structures?'on':'off';app.dataset.custom=settings.custom?'on':'off';app.dataset.playerRing=settings.playerRing?'on':'off';
    q('.coord-box').style.display=settings.showCoords?'':'none';q('#debugHud').style.display=settings.debug?'block':'none';
    if(settings.debug)q('#debugHud').innerHTML='zoom='+view.zoom.toFixed(3)+'<br>center='+Math.round(view.centerX)+','+Math.round(view.centerZ)+'<br>tiles='+tileCache.size+'<br>pending='+(serverState&&serverState.runtime?serverState.runtime.pendingCount:0);
  }

  function sendViewportSoon(){clearTimeout(viewportTimer);viewportTimer=setTimeout(sendViewport,80);}
  async function sendViewport(){
    if(dragging||!serverState?.connected||!viewInitialized||!socket||socket.readyState!==WebSocket.OPEN)return;
    if(viewportRequestInFlight){viewportRequestQueued=true;return;}
    const s=stageSize(),viewport={centerX:view.centerX,centerZ:view.centerZ,zoom:view.zoom,width:Math.round(s.width),height:Math.round(s.height)};
    const signature=[viewport.centerX,viewport.centerZ,viewport.zoom,viewport.width,viewport.height].join('|');
    if(signature===lastViewportSignature)return;
    lastViewportSignature=signature;viewportRequestInFlight=true;
    try{await wsRequest('viewport',{payload:viewport});}
    catch(_){if(lastViewportSignature===signature)lastViewportSignature='';}
    finally{
      viewportRequestInFlight=false;
      if(viewportRequestQueued){viewportRequestQueued=false;lastViewportSignature='';sendViewportSoon();}
    }
  }
  function initView(next){
    const saved=loadJson(VIEW_KEY,{}),same=saved.worldKey&&saved.worldKey===worldKey(next);
    if(same&&Number.isFinite(saved.centerX)&&Number.isFinite(saved.centerZ))view={centerX:saved.centerX,centerZ:saved.centerZ,zoom:Math.max(MIN_ZOOM,Math.min(MAX_ZOOM,Number(saved.zoom)||2))};
    else if(next.player&&next.player.present)view={centerX:next.player.x,centerZ:next.player.z,zoom:2};
    viewInitialized=true;
  }
  function markerSignature(markers){
    return (markers||[]).map(m=>[
      m.id,m.kind,m.x,m.y,m.z,m.name,m.icon,m.accentColor,m.primaryColor,m.lineColor,
      m.description,m.preset,m.modifiedAuto
    ].join(':')).join('|');
  }
  function presetSignature(presets){
    return (presets||[]).map(p=>[p.id,p.label,p.icon,p.primaryColor,p.accentColor,p.lineColor].join(':')).join('|');
  }
  function refreshStaticUi(force){
    const ms=markerSignature(serverState&&serverState.markers);
    if(force||ms!==markerUiSignature){
      markerUiSignature=ms;renderLayerList();renderMarkerList();
    }
    const ps=presetSignature(serverState&&serverState.structurePresets);
    if(force||ps!==presetUiSignature){
      presetUiSignature=ps;renderStructureSettings();
    }
  }
  function renderStatus(){
    const markers=serverState&&serverState.markers||[],structures=markers.filter(m=>m.kind==='structure').length,custom=markers.length-structures;
    q('#mapDataStatus').textContent='结构 '+structures+' · 标点 '+custom+' · 缓存 '+(serverState&&serverState.runtime?serverState.runtime.cachedCount:0);
  }
  function syncLayerToggles(){
    qa('.checkbox',layersPanel).forEach(checkbox=>{
      const filter=checkbox.dataset.structureFilter;
      checkbox.checked=filter ? isStructureLayerEnabled(filter)
        : !!settings[checkbox.dataset.toggle];
    });
  }
  function isStructureLayerEnabled(preset){
    const enabled=settings.structureFilters[preset];
    return typeof enabled==='boolean'?enabled:!HIDDEN_BY_DEFAULT.has(preset);
  }
  function structureGroupKey(marker){
    return marker.preset||marker.icon||'other';
  }
  function renderLayerList(){
    const markers=serverState?.markers||[];
    const structures=markers.filter(marker=>marker.kind==='structure');
    q('#layersPanel .layer-row .count').textContent=markers.length-structures.length;
    q('#structureTotalCount').textContent=structures.length;
    const groups=new Map();
    structures.forEach(marker=>{
      const key=structureGroupKey(marker);
      if(!groups.has(key))groups.set(key,{key:key,count:0,label:marker.name||key,icon:marker.icon});
      groups.get(key).count++;
    });
    const list=q('#structureLayerList');
    list.replaceChildren();
    Array.from(groups.values()).sort((a,b)=>a.label.localeCompare(b.label,'zh-CN')).forEach(group=>{
      const row=document.createElement('div');
      row.className='layer-row indent';
      row.innerHTML='<label class="layer-toggle"><span class="layer-check">'+
        '<input type="checkbox" class="checkbox"><svg class="icon" aria-hidden="true"><use href="#i-check"/></svg></span>'+
        '<svg class="icon layer-icon" aria-hidden="true"><use href="#'+iconSymbol(group.icon)+'"/></svg>'+
        '<span class="name">'+escapeHtml(group.label)+'</span><span class="count">'+group.count+'</span></label>';
      q('.checkbox',row).dataset.structureFilter=group.key;
      list.appendChild(row);
    });
    syncLayerToggles();
  }
  function setStructurePresetChange(id,change){
    structurePresetChanges.set(id,{...structurePresetChanges.get(id),...change});
  }
  function renderStructureSettings(){
    const list=q('#structureList');
    list.replaceChildren();
    const presets=serverState?.structurePresets||[];
    const presetIcons=presets.map(preset=>preset.defaultIcon);
    q('#applyStructurePresets').disabled=!presets.length||applyingStructurePresets;
    q('#resetStructurePresets').disabled=!presets.length||applyingStructurePresets;
    for(const preset of presets){
      const change=structurePresetChanges.get(preset.id);
      const icon=change?.icon||(resetStructurePresets?preset.defaultIcon:preset.icon);
      const color=change?.colorText||(resetStructurePresets?preset.defaultAccentColor:preset.accentColor);
      const row=document.createElement('div');
      row.className='structure-row';
      row.innerHTML='<div>'+escapeHtml(preset.label)+'</div>'+
        '<button type="button" class="structure-select" aria-label="'+escapeHtml(preset.label+'图标')+'">'+
        '<span class="left"></span><svg class="icon" aria-hidden="true"><use href="#i-chevron"/></svg></button>'+
        '<div class="structure-color"><label class="swatch">'+
        '<input class="native-color" type="color" aria-label="'+escapeHtml(preset.label+'颜色')+'"></label></div>';
      const select=q('.structure-select',row);
      updateIconSelect(select,icon);
      select.disabled=applyingStructurePresets;
      select.onclick=()=>openIconDropdown(select,select.dataset.value,newIcon=>{
        setStructurePresetChange(preset.id,{icon:newIcon});
        updateIconSelect(select,newIcon);
      },presetIcons);
      const colorInput=q('input',row);
      colorInput.value=color;
      colorInput.disabled=applyingStructurePresets;
      const swatch=q('.swatch',row);
      swatch.style.background=colorInput.value;
      colorInput.oninput=()=>{
        swatch.style.background=colorInput.value;
        setStructurePresetChange(preset.id,{colorText:colorInput.value});
      };
      list.appendChild(row);
    }
  }
  async function applyStructurePresetChanges(){
    if(applyingStructurePresets)return;
    if(!resetStructurePresets&&!structurePresetChanges.size){closeModal();return;}
    const changes=Object.fromEntries(Array.from(structurePresetChanges,([id,draft])=>{
      const change={};
      if(draft.icon)change.icon=draft.icon;
      if(draft.colorText)change.color=colorInt(draft.colorText);
      return [id,change];
    }));
    applyingStructurePresets=true;
    renderStructureSettings();
    try{
      await wsRequest('structure_presets',{payload:{reset:resetStructurePresets,changes:changes}});
      closeModal();
      toast('结构预设已保存');
    }catch(error){
      toast('保存结构预设失败：'+error.message);
    }finally{
      applyingStructurePresets=false;
      renderStructureSettings();
    }
  }
  function renderMarkerList(){
    const all=(serverState&&serverState.markers||[]).filter(m=>m.kind!=='structure'||m.modifiedAuto);q('#markerCountBadge').textContent=all.length;
    const list=q('#markerList');list.innerHTML='';if(!all.length){list.innerHTML='<div class="small" style="padding:14px 4px">暂无自定义标点</div>';return;}
    all.forEach(m=>{const row=document.createElement('div');row.className='marker-row';
      row.innerHTML='<svg class="icon mi"><use href="#'+iconSymbol(m.icon)+'"/></svg><div class="info"><div class="title">'+escapeHtml(m.name)+'</div><div class="meta">X '+m.x+' · Y '+m.y+' · Z '+m.z+(m.modifiedAuto?' · 自动→自定义':'')+'</div><div class="marker-description">'+escapeHtml(m.description||'')+'</div></div><div class="row-actions"><button class="mini" title="编辑"><svg class="icon"><use href="#i-edit"/></svg></button></div>';
      row.querySelector('button').onclick=()=>openEditor(m);list.appendChild(row);});
  }
  function syncRuntimeSettings(){
    const r=serverState&&serverState.runtime;if(!r)return;
    let presentationChanged=false;
    if(r.drawingMode&&DRAWING_MODES[r.drawingMode]&&settings.drawingMode!==r.drawingMode){
      settings.drawingMode=r.drawingMode;presentationChanged=true;
    }
    if(r.visualStyle&&VISUAL_STYLES[r.visualStyle]&&settings.visualStyle!==r.visualStyle){
      settings.visualStyle=r.visualStyle;presentationChanged=true;
    }
    if(presentationChanged){
      saveSettings();applyPresentation();renderSettings();refreshStaticUi(true);refreshPresentationTerrain();scheduleRender(DRAW_ALL);
    }
    if(r.frontendTarget)q('#frontendTarget').value=r.frontendTarget;
    if(r.requestBatchSize!=null){q('#requestRange').value=r.requestBatchSize;q('#requestValue').textContent=String(r.requestBatchSize);}
    if(r.liveScanIntervalTicks!=null){q('#scanRange').value=r.liveScanIntervalTicks;q('#scanValue').textContent=r.liveScanIntervalTicks+' tick / '+(r.liveScanIntervalTicks/20).toFixed(2)+' s';}
  }

  async function queryPoint(x,z){return wsRequest('point',{x:Math.round(x),z:Math.round(z)});}
  function cancelCursorLookup(){
    clearTimeout(cursorTimer);
    cursorTimer=0;
    cursorTarget=null;
  }
  function updateCursor(clientX,clientY){
    const point=screenToWorld(clientX,clientY);
    const x=Math.round(point.x);
    const z=Math.round(point.z);
    if(cursorTarget?.x===x&&cursorTarget.z===z)return;

    clearTimeout(cursorTimer);
    cursorTimer=0;
    const target={x,z};
    cursorTarget=target;
    q('#cursorCoords').textContent='X '+x+'　Z '+z;
    q('#cursorBiome').textContent='—';
    if(dragging||!serverState?.connected)return;

    cursorTimer=setTimeout(()=>{
      cursorTimer=0;
      lookupCursor(target);
    },120);
  }
  async function lookupCursor(target){
    if(cursorRequestInFlight||dragging||cursorTarget!==target||!serverState?.connected)return;
    cursorRequestInFlight=true;
    try{
      const info=await queryPoint(target.x,target.z);
      if(cursorTarget!==target)return;
      q('#cursorCoords').textContent='X '+info.x+'　Y '+info.y+'　Z '+info.z;
      q('#cursorBiome').textContent=String(info.biome||'群系：未加载').replace(/^群系：/,'');
    }catch(_){
      if(cursorTarget===target)q('#cursorBiome').textContent='未加载';
    }finally{
      cursorRequestInFlight=false;
      // A newer hover may have finished its debounce while this request was in flight.
      if(cursorTarget&&cursorTarget!==target&&!cursorTimer)lookupCursor(cursorTarget);
    }
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
  function closeModal(){
    overlay.style.display='none';
    [settingsModal,editorModal,aboutModal].forEach(modal=>modal.style.display='none');
    app.classList.remove('modal-open');
    dropdown.classList.remove('open');
    structurePresetChanges.clear();
    resetStructurePresets=false;
  }
  function activateSettingsPage(page){qa('.settings-nav-item').forEach(el=>el.classList.toggle('active',el.dataset.page===page));qa('[data-setting-page]').forEach(el=>el.classList.toggle('active',el.dataset.settingPage===page));}
  function openSettings(page){renderSettings();renderStructureSettings();activateSettingsPage(page||'general');openModal(settingsModal);}
  function syncSwitches(){qa('[data-switch][data-setting]').forEach(el=>{const k=el.dataset.setting;if(Object.prototype.hasOwnProperty.call(settings,k))el.classList.toggle('on',!!settings[k]);});syncLayerToggles();}
  function renderSettings(){
    syncSwitches();q('#playerSize').value=settings.playerSize;q('#playerSizeValue').textContent=settings.playerSize+' px';q('#playerColor').value=settings.playerColor;q('#playerSwatch').style.background=settings.playerColor;q('#playerColorText').textContent=settings.playerColor.toUpperCase();
    q('#markerScaleRange').value=settings.markerScale;q('#markerScaleValue').textContent=settings.markerScale+'%';q('#markerDefaultColor').value=settings.markerDefaultColor;q('#markerDefaultSwatch').style.background=settings.markerDefaultColor;q('#markerDefaultColorText').textContent=settings.markerDefaultColor.toUpperCase();
    q('#markerDefaultSize').value=settings.markerDefaultSize;q('#markerDefaultSizeValue').textContent=settings.markerDefaultSize+' px';updateIconSelect(q('#markerDefaultIconSelect'),settings.markerDefaultIcon);
    qa('[data-player-icon]').forEach(el=>el.classList.toggle('active',el.dataset.playerIcon===settings.playerIcon));
    qa('[data-drawing-mode]').forEach(el=>el.classList.toggle('active',el.dataset.drawingMode===settings.drawingMode));
    qa('[data-visual-style]').forEach(el=>el.classList.toggle('active',el.dataset.visualStyle===settings.visualStyle));
  }
  function updateIconSelect(el,icon){const left=q('.left',el);if(!left)return;left.innerHTML='<svg class="icon"><use href="#'+iconSymbol(icon)+'"/></svg><span>'+escapeHtml(iconLabel(icon))+'</span>';el.dataset.value=icon;}
  function openIconDropdown(anchor,current,onSelect,extraIcons=[]){
    const icons=new Set(['pin','home','mine','ship','mountain','temple','village','mansion','outpost','portal','city','fortress','cave','water',...extraIcons]);
    dropdown.innerHTML=Array.from(icons,i=>'<div class="dropdown-item '+(i===current?'active':'')+'" data-icon="'+i+'"><svg class="icon"><use href="#'+iconSymbol(i)+'"/></svg>'+escapeHtml(iconLabel(i))+'</div>').join('');
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
    try{if(editorMode==='edit'&&selectedMarker)await wsRequest('marker_update',{markerId:selectedMarker.id,payload:payload});else await wsRequest('marker_create',{payload:payload});closeModal();toast(editorMode==='edit'?'标点已保存':'标点已添加');}catch(e){toast('保存失败：'+e.message);}
  }
  async function deleteOrReset(){
    if(!selectedMarker)return;try{if(selectedMarker.modifiedAuto){await wsRequest('marker_reset',{markerId:selectedMarker.id});toast('已重置为自动结构');}else{await wsRequest('marker_delete',{markerId:selectedMarker.id});toast('已删除');}closeModal();closeContexts();}catch(e){toast('操作失败：'+e.message);}
  }
  async function teleport(x,z){try{await wsRequest('teleport',{x:Math.round(x),z:Math.round(z)});toast('已请求传送至 '+Math.round(x)+', '+Math.round(z));}catch(e){toast('传送失败：'+e.message);}}

  function enablePanelDrag(panel){
    const head=q('.panel-head',panel);if(!head)return;let moving=false,dx=0,dy=0;
    head.addEventListener('pointerdown',e=>{if(e.target.closest('button'))return;moving=true;const pr=panel.getBoundingClientRect(),sr=mapStage.getBoundingClientRect();dx=e.clientX-pr.left;dy=e.clientY-pr.top;head.setPointerCapture(e.pointerId);panel.style.left=(pr.left-sr.left)+'px';panel.style.top=(pr.top-sr.top)+'px';});
    head.addEventListener('pointermove',e=>{if(!moving)return;const sr=mapStage.getBoundingClientRect(),left=Math.max(8,Math.min(mapStage.clientWidth-panel.offsetWidth-8,e.clientX-sr.left-dx)),top=Math.max(8,Math.min(mapStage.clientHeight-panel.offsetHeight-8,e.clientY-sr.top-dy));panel.style.left=left+'px';panel.style.top=top+'px';});
    head.addEventListener('pointerup',()=>moving=false);head.addEventListener('pointercancel',()=>moving=false);
  }

  function patchCopy(){
    const v=q('.about-version');if(v)v.textContent='Cartograph V3.1 · WebUI';
    const n=q('.about-note');if(n)n.textContent='此页面就是正式 WebUI 前端。世界采集、缓存和动作由 Minecraft Java 后端提供，界面由浏览器 HTML / CSS / SVG / Canvas 渲染。';
    qa('.about-grid .k').forEach(k=>{if(k.textContent.trim()==='缓存策略'&&k.nextElementSibling)k.nextElementSibling.textContent='只持久化世界事实；按绘图方式派生地图数据，再由绘图风格成图';});
    const rgb=qa('.form-row .label').find(el=>el.textContent.trim()==='已渲染 RGB 缓存');if(rgb){rgb.textContent='地表事实缓存';if(rgb.nextElementSibling)rgb.nextElementSibling.textContent='只保存世界事实；地图结构与风格结果均为临时派生';const sw=rgb.parentElement.querySelector('[data-switch]');if(sw)sw.style.display='none';}
    const pre=q('#prewarmRange');if(pre)pre.closest('.performance-row').style.display='none';
    const key=q('[data-shortcut="map"]');if(key){const t=key.closest('.form-row').querySelector('.shortcut span');if(t)t.textContent='在 Minecraft 中按 M 打开 WebUI';}
    qa('[data-marker-pane="trash"]').forEach(el=>el.style.display='none');qa('[data-marker-pane-content="trash"]').forEach(el=>el.style.display='none');q('#homeBtn').style.display='none';
  }

  function bind(){
    qa('.nav-btn').forEach(b=>b.onclick=()=>{const n=b.dataset.nav;if(n==='map')hidePanels();else if(n==='layers'||n==='markers')openPanel(n);else if(n==='settings')openSettings();else if(n==='about')openModal(aboutModal);});
    qa('[data-panel-close]').forEach(b=>b.onclick=hidePanels);qa('[data-about-close]').forEach(b=>b.onclick=closeModal);qa('[data-editor-close]').forEach(b=>b.onclick=closeModal);qa('[data-modal-close]').forEach(b=>b.onclick=closeModal);
    qa('.settings-nav-item').forEach(el=>el.onclick=()=>activateSettingsPage(el.dataset.page));qa('.subtab').forEach(tab=>tab.onclick=()=>{qa('.subtab').forEach(x=>x.classList.remove('active'));tab.classList.add('active');qa('[data-subpage]').forEach(x=>x.classList.toggle('hidden',x.dataset.subpage!==tab.dataset.sub));});
    qa('[data-switch][data-setting]').forEach(sw=>sw.onclick=()=>{const k=sw.dataset.setting;if(!Object.prototype.hasOwnProperty.call(settings,k))return;settings[k]=!settings[k];syncSwitches();scheduleRender();saveSettings();});
    qa('[data-drawing-mode]').forEach(card=>card.onclick=()=>selectDrawingMode(card.dataset.drawingMode));
    qa('[data-visual-style]').forEach(card=>card.onclick=()=>selectVisualStyle(card.dataset.visualStyle));
    qa('[data-player-icon]').forEach(b=>b.onclick=()=>{settings.playerIcon=b.dataset.playerIcon;saveSettings();renderSettings();scheduleRender();});
    q('#playerSize').oninput=e=>{settings.playerSize=Number(e.target.value);saveSettings();renderSettings();scheduleRender();};q('#playerColor').oninput=e=>{settings.playerColor=e.target.value.toUpperCase();saveSettings();renderSettings();scheduleRender();};
    q('#markerScaleRange').oninput=e=>{settings.markerScale=Number(e.target.value);saveSettings();renderSettings();scheduleRender();};q('#markerDefaultColor').oninput=e=>{settings.markerDefaultColor=e.target.value.toUpperCase();saveSettings();renderSettings();};
    q('#markerDefaultSize').oninput=e=>{settings.markerDefaultSize=Number(e.target.value);saveSettings();renderSettings();scheduleRender();};q('#markerDefaultIconSelect').onclick=()=>openIconDropdown(q('#markerDefaultIconSelect'),settings.markerDefaultIcon,i=>{settings.markerDefaultIcon=i;saveSettings();renderSettings();});
    q('#markerIconSelect').onclick=()=>openIconDropdown(q('#markerIconSelect'),currentEditorIcon,i=>{currentEditorIcon=i;updateIconSelect(q('#markerIconSelect'),i);});q('#markerColor').oninput=e=>{q('#markerSwatch').style.background=e.target.value;q('#markerColorText').textContent=e.target.value.toUpperCase();};
    q('#frontendTarget').onchange=async event=>{
      try{await wsRequest('runtime_settings',{payload:{frontendTarget:event.target.value}});}
      catch(_){syncRuntimeSettings();toast('打开位置保存失败');}
    };
    let rt=0;const sendRuntime=()=>{clearTimeout(rt);rt=setTimeout(()=>{wsRequest('runtime_settings',{payload:{requestBatchSize:Number(q('#requestRange').value),liveScanIntervalTicks:Number(q('#scanRange').value)}}).catch(()=>{});},180);};
    q('#requestRange').oninput=e=>{q('#requestValue').textContent=e.target.value;sendRuntime();};q('#scanRange').oninput=e=>{const v=Number(e.target.value);q('#scanValue').textContent=v+' tick / '+(v/20).toFixed(2)+' s';sendRuntime();};
    q('#zoomIn').onclick=()=>setZoom(view.zoom*1.25);q('#zoomOut').onclick=()=>setZoom(view.zoom/1.25);q('#locateBtn').onclick=centerPlayer;
    mapStage.addEventListener('wheel',e=>{if(e.target.closest('.floating-panel,.context-menu,.right-controls,.coord-box,.scale-box'))return;e.preventDefault();closeContexts();setZoom(e.deltaY<0?view.zoom*1.12:view.zoom/1.12,e.clientX,e.clientY);},{passive:false});
    mapStage.addEventListener('pointerdown',e=>{
      if(e.button!==0||!settings.dragMap||e.target.closest('.floating-panel,.context-menu,.right-controls,.map-marker,.coord-box,.scale-box'))return;
      closeContexts();
      cancelCursorLookup();
      dragging=true;
      dragX=e.clientX;
      dragY=e.clientY;
      updateCursor(dragX,dragY);
      mapStage.classList.add('dragging');
      mapStage.setPointerCapture(e.pointerId);
    });
    mapStage.addEventListener('pointermove',e=>{
      if(!dragging){
        if(e.target.closest('.floating-panel,.context-menu,.right-controls,.coord-box,.scale-box')){
          cancelCursorLookup();
        }else{
          updateCursor(e.clientX,e.clientY);
        }
        return;
      }
      const dx=e.clientX-dragX;
      const dy=e.clientY-dragY;
      dragX=e.clientX;
      dragY=e.clientY;
      view.centerX-=dx/view.zoom;
      view.centerZ-=dy/view.zoom;
      scheduleRender();
    });
    const finishDrag=e=>{
      if(!dragging)return;
      dragging=false;
      mapStage.classList.remove('dragging');
      saveView();
      sendViewportSoon();
      cancelCursorLookup();
      if(e.type==='pointerup')updateCursor(e.clientX,e.clientY);
    };
    mapStage.addEventListener('pointerup',finishDrag);
    mapStage.addEventListener('pointercancel',finishDrag);
    mapStage.addEventListener('lostpointercapture',finishDrag);
    mapStage.addEventListener('pointerleave',()=>{if(!dragging)cancelCursorLookup();});
    mapStage.addEventListener('contextmenu',e=>{if(e.target.closest('.floating-panel,.context-menu,.right-controls,.map-marker'))return;e.preventDefault();openMapContext(e.clientX,e.clientY);});
    q('#ctxAdd').onclick=()=>openEditor(null,contextPoint);q('#ctxTeleport').onclick=()=>{if(contextPoint)teleport(contextPoint.x,contextPoint.z);closeContexts();};q('#ctxCopy').onclick=async()=>{if(!contextPoint)return;const t=contextPoint.x+', '+(contextPoint.y==null?'':contextPoint.y)+', '+contextPoint.z;try{await navigator.clipboard.writeText(t);}catch(_){}toast('已复制坐标：'+t);closeContexts();};
    q('#markerTeleport').onclick=()=>{if(selectedMarker)teleport(selectedMarker.x,selectedMarker.z);closeContexts();};q('#ctxEdit').onclick=()=>{if(selectedMarker)openEditor(selectedMarker);};q('#ctxDelete').onclick=deleteOrReset;
    q('#addMarkerButton').onclick=async()=>{const x=Math.round(view.centerX),z=Math.round(view.centerZ);let p={x:x,y:serverState&&serverState.player?serverState.player.y:64,z:z};try{p=await queryPoint(x,z);}catch(_){}openEditor(null,p);};
    q('#saveMarkerButton').onclick=saveEditor;q('#deleteMarkerButton').onclick=deleteOrReset;q('#structureExpand').onclick=()=>q('#structureGroup').classList.toggle('expanded');
    layersPanel.addEventListener('change',event=>{
      const checkbox=event.target;
      if(!checkbox.matches('input.checkbox'))return;
      if(checkbox.dataset.structureFilter){
        settings.structureFilters[checkbox.dataset.structureFilter]=checkbox.checked;
      }else{
        settings[checkbox.dataset.toggle]=checkbox.checked;
      }
      syncSwitches();
      scheduleRender();
      saveSettings();
    });
    q('#applyStructurePresets').onclick=applyStructurePresetChanges;
    q('#resetStructurePresets').onclick=()=>{
      structurePresetChanges.clear();
      resetStructurePresets=true;
      dropdown.classList.remove('open');
      renderStructureSettings();
    };
    q('#recolorCache').onclick=()=>{tileCache.clear();lastViewportSignature='';sendViewportSoon();scheduleRender();toast('浏览器成图缓存已清除，将从世界事实重新着色');};q('#clearGpuCache').onclick=()=>{tileCache.clear();lastViewportSignature='';sendViewportSoon();scheduleRender();toast('浏览器地图纹理缓存已清除');};q('#refreshAll').onclick=()=>{tileCache.clear();lastViewportSignature='';sendViewportSoon();scheduleRender();toast('当前视野将重新读取世界事实');};
    q('.window-close').onclick=()=>{window.close();setTimeout(()=>toast('可直接关闭此浏览器标签页'),80);};
    document.addEventListener('pointerdown',e=>{if(dropdown.classList.contains('open')&&!e.target.closest('#dropdown,.select,.structure-select'))dropdown.classList.remove('open');if(!e.target.closest('.context-menu,.map-marker'))closeContexts();});
    document.addEventListener('keydown',e=>{if(e.key==='Escape'){if(dropdown.classList.contains('open'))dropdown.classList.remove('open');else if(settingsModal.style.display==='block'||editorModal.style.display==='block'||aboutModal.style.display==='block')closeModal();else closeContexts();}if((e.key==='r'||e.key==='R')&&!e.ctrlKey&&!e.altKey)centerPlayer();});
    enablePanelDrag(layersPanel);enablePanelDrag(markersPanel);
  }

  new ResizeObserver(resizeCanvas).observe(mapStage);
  patchCopy();applyPresentation();bind();renderSettings();renderHud();resizeCanvas();setConnection(false);app.dataset.webui='loaded';connectSocket();
  window.addEventListener('beforeunload',()=>{if(socket)socket.close(1000,'page unload');});
})();
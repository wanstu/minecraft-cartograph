$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $root 'v3.1/common/src/main/resources/webui'
$indexPath = Join-Path $webRoot 'index.html'
$appPath = Join-Path $webRoot 'app.js'

function Find-Chromium {
  foreach ($name in @('google-chrome','chrome','chromium','chromium-browser','msedge')) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
  }
  foreach ($path in @(
    'C:\Program Files\Google\Chrome\Application\chrome.exe',
    'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
    'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
    'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    '/usr/bin/google-chrome','/usr/bin/chromium','/usr/bin/chromium-browser'
  )) {
    if (Test-Path $path) { return $path }
  }
  throw 'Chrome/Chromium/Edge not found'
}

$html = Get-Content -Raw -Encoding UTF8 $indexPath
$app = Get-Content -Raw -Encoding UTF8 $appPath

$browserTest = @'
(() => {
  const failures = [];
  const assert = (condition,message) => { if(!condition) failures.push(message); };
  const approx = (actual,expected,tolerance,label) => {
    if(Math.abs(actual-expected)>tolerance)failures.push(label+': expected '+expected+'±'+tolerance+', got '+actual);
  };
  const overlaps = (a,b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
  const rect = el => {
    const r=el.getBoundingClientRect();
    return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};
  };
  const labels={satellite:'卫星',hand_drawn:'手绘'};
  const styleLabels={modern:'现代',ancient:'古式',anime:'动漫'};

  const choose = (mode,style) => {
    document.querySelector('[data-nav="settings"]').click();
    document.querySelector('[data-sub="theme"]').click();

    const modeCard=document.querySelector('[data-drawing-mode="'+mode+'"]');
    const styleCard=document.querySelector('[data-visual-style="'+style+'"]');
    assert(!!modeCard,'missing drawing mode card: '+mode);
    assert(!!styleCard,'missing visual style card: '+style);
    if(modeCard)modeCard.click();
    if(styleCard)styleCard.click();

    const modal=document.querySelector('#settingsModal');
    const modalRect=rect(modal);
    assert(getComputedStyle(modal).display!=='none',mode+'+'+style+': settings modal closed during switch');
    assert(modalRect.left>=-.5&&modalRect.top>=-.5,mode+'+'+style+': modal starts outside viewport');
    assert(modalRect.right<=innerWidth+.5&&modalRect.bottom<=innerHeight+.5,mode+'+'+style+': modal extends outside viewport');

    const footerBg=getComputedStyle(modal.querySelector('.settings-footer')).backgroundColor;
    const stored=(()=>{
      try{return JSON.parse(localStorage.getItem('cartograph-v31-webui-settings-v1')||'{}');}
      catch(_){return {storageError:true};}
    })();

    const app=document.querySelector('#app');
    const combination=labels[mode]+' · '+styleLabels[style];
    const result={
      key:mode+'_'+style,
      mode,
      style,
      theme:app.dataset.theme,
      renderer:app.dataset.mapRenderer,
      drawingDataset:app.dataset.drawingMode,
      styleDataset:app.dataset.visualStyle,
      layout:app.dataset.themeLayout,
      icons:app.dataset.themeIcons,
      density:app.dataset.themeDensity,
      typography:app.dataset.themeTypography,
      activeLabel:document.querySelector('#activeThemeLabel').textContent.trim(),
      settingsLabel:document.querySelector('#interfaceThemeValue').textContent.trim(),
      aboutLabel:document.querySelector('#aboutThemeValue').textContent.trim(),
      storedMode:stored.drawingMode,
      storedStyle:stored.visualStyle,
      modeActive:modeCard&&modeCard.classList.contains('active'),
      styleActive:styleCard&&styleCard.classList.contains('active'),
      footerBg,
      fontFamily:getComputedStyle(app).fontFamily,
      topbar:rect(document.querySelector('.topbar')),
      stage:rect(document.querySelector('#mapStage'))
    };

    assert(result.theme===style,result.key+': CSS style identity mismatch');
    assert(result.renderer===mode,result.key+': map renderer identity mismatch');
    assert(result.drawingDataset===mode,result.key+': drawing-mode dataset mismatch');
    assert(result.styleDataset===style,result.key+': visual-style dataset mismatch');
    assert(result.storedMode===mode,result.key+': drawing mode not persisted');
    assert(result.storedStyle===style,result.key+': visual style not persisted');
    assert(result.modeActive,result.key+': drawing mode card not active');
    assert(result.styleActive,result.key+': visual style card not active');
    for(const label of [result.activeLabel,result.settingsLabel,result.aboutLabel]){
      assert(label===combination,result.key+': combination label mismatch: '+label);
    }

    modal.querySelector('[data-modal-close]').click();
    const layersNav=document.querySelector('[data-nav="layers"]');
    const layers=document.querySelector('#layersPanel');
    if(getComputedStyle(layers).display==='none')layersNav.click();
    result.panel=rect(layers);
    result.panelMaxHeight=parseFloat(getComputedStyle(layers).maxHeight)||0;
    result.panelHeadHeight=rect(layers.querySelector('.panel-head')).height;
    const controls=rect(document.querySelector('.right-controls'));
    const coord=rect(document.querySelector('.coord-box'));
    const scale=rect(document.querySelector('.scale-box'));
    assert(result.panel.top>=result.stage.top-.5,result.key+': layers panel above map stage');
    assert(result.panel.bottom<=result.stage.bottom+.5,result.key+': layers panel below map stage');
    assert(!overlaps(result.panel,controls),result.key+': layers overlaps controls');
    assert(!overlaps(result.panel,coord),result.key+': layers overlaps coordinate box');
    assert(!overlaps(result.panel,scale),result.key+': layers overlaps scale box');

    const appStyle=getComputedStyle(app);
    const floatTop=parseFloat(appStyle.getPropertyValue('--theme-float-top'))||0;
    const floatBottom=parseFloat(appStyle.getPropertyValue('--theme-float-bottom'))||0;
    approx(result.panelMaxHeight,result.stage.height-floatTop-floatBottom,1.5,result.key+' panel max-height');

    const markersNav=document.querySelector('[data-nav="markers"]');
    const markers=document.querySelector('#markersPanel');
    if(getComputedStyle(markers).display==='none')markersNav.click();
    const markerRect=rect(markers);
    assert(markerRect.top>=result.stage.top-.5,result.key+': markers panel above map stage');
    assert(markerRect.bottom<=result.stage.bottom+.5,result.key+': markers panel below map stage');
    assert(!overlaps(markerRect,controls),result.key+': markers overlaps controls');
    result.markersPanel=markerRect;

    return result;
  };

  try{
    const combinations={};
    for(const style of ['modern','ancient','anime']){
      for(const mode of ['satellite','hand_drawn']){
        const item=choose(mode,style);
        combinations[item.key]=item;

        if(style==='ancient'){
          const inset=innerWidth<=520?{top:66,side:6,bottom:6}:innerWidth<=900?{top:78,side:10,bottom:10}:{top:88,side:18,bottom:18};
          approx(item.stage.top,inset.top,1.5,item.key+' ancient stage top');
          approx(item.stage.left,inset.side,1.5,item.key+' ancient stage left');
          approx(innerWidth-item.stage.right,inset.side,1.5,item.key+' ancient right inset');
          approx(innerHeight-item.stage.bottom,inset.bottom,1.5,item.key+' ancient bottom inset');
          assert(item.layout==='ancient-atlas',item.key+': ancient layout mismatch');
          assert(item.icons==='engraved',item.key+': ancient icon family mismatch');
          assert(item.typography==='serif',item.key+': ancient typography mismatch');
          assert(/Georgia|serif/i.test(item.fontFamily),item.key+': ancient font stack missing');
          assert(item.topbar.bottom<item.stage.top,item.key+': ancient toolbar overlaps map frame');
        }else{
          approx(item.stage.top,0,1,item.key+' map stage top');
          approx(item.stage.left,0,1,item.key+' map stage left');
          assert(item.layout==='modern-atlas',item.key+': floating layout mismatch');
          assert(item.icons==='modern',item.key+': modern icon family mismatch');
          const expectedTop=innerWidth<=520?6:innerWidth<=900?10:20;
          approx(item.topbar.top,expectedTop,1.5,item.key+' toolbar vertical position');
          assert(!overlaps(item.topbar,item.panel),item.key+': floating toolbar overlaps left panel');
        }
      }
    }

    const m=combinations.satellite_modern,a=combinations.satellite_ancient,n=combinations.satellite_anime;
    assert(m.footerBg!==a.footerBg,'modern and ancient footer surfaces collapsed together');
    assert(m.footerBg!==n.footerBg,'modern and anime footer surfaces collapsed together');
    assert(a.footerBg!==n.footerBg,'ancient and anime footer surfaces collapsed together');

    document.body.dataset.themeBrowserTest=encodeURIComponent(JSON.stringify({
      ok:failures.length===0,
      failures,
      viewport:{width:innerWidth,height:innerHeight},
      combinations
    }));
  }catch(error){
    document.body.dataset.themeBrowserTest=encodeURIComponent(JSON.stringify({
      ok:false,failures:[String(error&&error.stack||error)]
    }));
  }
})();
'@

$needle = '<script src="/app.js"></script>'
if (-not $html.Contains($needle)) { throw 'WebUI app script tag not found' }
$nl = [Environment]::NewLine
$replacement = '<script>' + $nl + $app + $nl + '</script>' + $nl + '<script>' + $nl + $browserTest + $nl + '</script>'
$harness = $html.Replace($needle, $replacement)

$tmp = Join-Path ([IO.Path]::GetTempPath()) ('cartograph-v31-presentation-browser-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
  $harnessPath = Join-Path $tmp 'index.html'
  [IO.File]::WriteAllText($harnessPath, $harness, [Text.UTF8Encoding]::new($false))
  $browser = Find-Chromium
  $uri = [Uri]::new($harnessPath).AbsoluteUri
  $viewports = @(
    @{ Width = 1440; Height = 900 },
    @{ Width = 820; Height = 720 },
    @{ Width = 500; Height = 700 }
  )

  foreach ($viewport in $viewports) {
    $profile = Join-Path $tmp ('profile-' + $viewport.Width + 'x' + $viewport.Height)
    New-Item -ItemType Directory -Path $profile | Out-Null
    $args = @(
      '--headless=new','--no-sandbox','--disable-gpu','--disable-dev-shm-usage',
      '--no-first-run','--disable-background-networking',
      ('--window-size=' + $viewport.Width + ',' + $viewport.Height),
      "--user-data-dir=$profile",'--dump-dom',$uri
    )
    $dump = & $browser @args 2>$null | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Headless browser exited with code $LASTEXITCODE" }

    $match = [regex]::Match($dump, 'data-theme-browser-test="([^"]+)"')
    if (-not $match.Success) { throw 'Browser test result marker was not emitted' }

    $encoded = [System.Net.WebUtility]::HtmlDecode($match.Groups[1].Value)
    $json = [Uri]::UnescapeDataString($encoded)
    $result = $json | ConvertFrom-Json

    if (-not $result.ok) {
      foreach ($failure in $result.failures) {
        Write-Error ("{0}x{1}: {2}" -f $result.viewport.width,$result.viewport.height,$failure)
      }
      exit 1
    }

    Write-Host ("Cartograph V3.1 presentation browser smoke: OK ({0}x{1})" -f $result.viewport.width,$result.viewport.height)
    foreach ($key in @('satellite_modern','hand_drawn_modern','satellite_ancient','hand_drawn_ancient','satellite_anime','hand_drawn_anime')) {
      $item = $result.combinations.$key
      Write-Host ("  {0}: layout={1}, stage=({2},{3}) {4}x{5}" -f
        $key,$item.layout,[math]::Round($item.stage.left),[math]::Round($item.stage.top),
        [math]::Round($item.stage.width),[math]::Round($item.stage.height))
    }
  }
}
finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

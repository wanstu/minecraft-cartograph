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
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser'
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
  const approx = (actual, expected, tolerance, label) => {
    if (Math.abs(actual - expected) > tolerance) {
      failures.push(label + ': expected ' + expected + '±' + tolerance + ', got ' + actual);
    }
  };
  const assert = (condition, message) => { if (!condition) failures.push(message); };
  const overlaps = (a,b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
  const rect = el => {
    const r = el.getBoundingClientRect();
    return {left:r.left, top:r.top, right:r.right, bottom:r.bottom, width:r.width, height:r.height};
  };
  const choose = id => {
    const settingsButton = document.querySelector('[data-nav="settings"]');
    settingsButton.click();
    const themeTab = document.querySelector('[data-sub="theme"]');
    themeTab.click();

    const card = document.querySelector('[data-theme-choice="' + id + '"]');
    assert(!!card, 'missing theme card: ' + id);
    assert(card && !card.closest('[data-subpage="theme"]').classList.contains('hidden'), id + ': theme card is not visible in settings flow');
    if (card) card.click();

    const settingsModal = document.querySelector('#settingsModal');
    const settingsRect = rect(settingsModal);
    const footerBg = getComputedStyle(settingsModal.querySelector('.settings-footer')).backgroundColor;
    assert(getComputedStyle(settingsModal).display !== 'none', id + ': settings modal closed during theme switch');
    assert(settingsRect.left >= -0.5 && settingsRect.top >= -0.5, id + ': settings modal starts outside viewport');
    assert(settingsRect.right <= innerWidth + 0.5 && settingsRect.bottom <= innerHeight + 0.5, id + ': settings modal extends outside viewport');

    settingsModal.querySelector('[data-modal-close]').click();

    const layersNav = document.querySelector('[data-nav="layers"]');
    const layersPanel = document.querySelector('#layersPanel');
    if (getComputedStyle(layersPanel).display === 'none') layersNav.click();

    const app = document.querySelector('#app');
    const stage = document.querySelector('#mapStage');
    const topbar = document.querySelector('.topbar');
    const panel = document.querySelector('#layersPanel');
    const rightControls = document.querySelector('.right-controls');
    const coordBox = document.querySelector('.coord-box');
    const scaleBox = document.querySelector('.scale-box');
    const activeCard = document.querySelector('[data-theme-choice="' + id + '"]');
    const stored = (() => {
      try { return JSON.parse(localStorage.getItem('cartograph-v31-webui-settings-v1') || '{}').frontendTheme || ''; }
      catch (_) { return '__storage_error__'; }
    })();
    const result = {
      id,
      theme: app.dataset.theme,
      layout: app.dataset.themeLayout,
      icons: app.dataset.themeIcons,
      density: app.dataset.themeDensity,
      typography: app.dataset.themeTypography,
      renderer: app.dataset.mapRenderer,
      uiIcons: {
        map: document.querySelector('[data-nav="map"] [data-theme-ui-icon]').getAttribute('href'),
        markers: document.querySelector('[data-nav="markers"] [data-theme-ui-icon]').getAttribute('href'),
        layers: document.querySelector('[data-nav="layers"] [data-theme-ui-icon]').getAttribute('href'),
        settings: document.querySelector('[data-nav="settings"] [data-theme-ui-icon]').getAttribute('href'),
        about: document.querySelector('[data-nav="about"] [data-theme-ui-icon]').getAttribute('href'),
        locate: document.querySelector('#locateBtn [data-theme-ui-icon]').getAttribute('href'),
        home: document.querySelector('#homeBtn [data-theme-ui-icon]').getAttribute('href')
      },
      activeLabel: document.querySelector('#activeThemeLabel').textContent.trim(),
      settingsLabel: document.querySelector('#interfaceThemeValue').textContent.trim(),
      aboutLabel: document.querySelector('#aboutThemeValue').textContent.trim(),
      stored,
      settingsModal: settingsRect,
      footerBg,
      stage: rect(stage),
      topbar: rect(topbar),
      panel: rect(panel),
      panelHeadHeight: rect(panel.querySelector('.panel-head')).height,
      rightControls: rect(rightControls),
      coordBox: rect(coordBox),
      scaleBox: rect(scaleBox),
      panelDisplay: getComputedStyle(panel).display,
      panelMaxHeight: parseFloat(getComputedStyle(panel).maxHeight) || 0,
      cardActive: activeCard.classList.contains('active'),
      fontFamily: getComputedStyle(app).fontFamily
    };

    assert(result.theme === id, id + ': data-theme mismatch');
    assert(result.renderer === id, id + ': active map renderer does not match theme: ' + result.renderer);
    assert(result.stored === id, id + ': theme was not persisted');
    assert(result.cardActive, id + ': selected card is not active');
    assert(result.panelDisplay !== 'none', id + ': layers panel did not open');
    assert(result.panel.top >= result.stage.top - 0.5, id + ': panel starts above map viewport');
    assert(result.panel.bottom <= result.stage.bottom + 0.5, id + ': panel extends below map viewport');
    const appStyle = getComputedStyle(app);
    const floatTop = parseFloat(appStyle.getPropertyValue('--theme-float-top')) || 0;
    const floatBottom = parseFloat(appStyle.getPropertyValue('--theme-float-bottom')) || 0;
    approx(result.panelMaxHeight, result.stage.height - floatTop - floatBottom, 1.5, id + ' panel max-height');
    assert(!overlaps(result.panel, result.rightControls), id + ': panel overlaps zoom/player controls');
    assert(!overlaps(result.panel, result.coordBox), id + ': panel overlaps coordinate box');
    assert(!overlaps(result.panel, result.scaleBox), id + ': panel overlaps scale box');

    const markersNav = document.querySelector('[data-nav="markers"]');
    const markersPanel = document.querySelector('#markersPanel');
    if (getComputedStyle(markersPanel).display === 'none') markersNav.click();
    const markerRect = rect(markersPanel);
    const markerMaxHeight = parseFloat(getComputedStyle(markersPanel).maxHeight) || 0;
    assert(getComputedStyle(markersPanel).display !== 'none', id + ': markers panel did not open');
    assert(markerRect.top >= result.stage.top - 0.5, id + ': markers panel starts above map viewport');
    assert(markerRect.bottom <= result.stage.bottom + 0.5, id + ': markers panel extends below map viewport');
    approx(markerMaxHeight, result.stage.height - floatTop - floatBottom, 1.5, id + ' markers panel max-height');
    assert(!overlaps(markerRect, result.rightControls), id + ': markers panel overlaps zoom/player controls');
    assert(!overlaps(markerRect, result.coordBox), id + ': markers panel overlaps coordinate box');
    assert(!overlaps(markerRect, result.scaleBox), id + ': markers panel overlaps scale box');
    result.markersPanel = markerRect;
    result.markersPanelMaxHeight = markerMaxHeight;

    return result;
  };

  try {
    const satellite = choose('satellite');
    approx(satellite.stage.top, 62, 1.5, 'satellite stage top');
    approx(satellite.stage.left, 0, 1, 'satellite stage left');
    assert(satellite.layout === 'technical', 'satellite layout mismatch');
    assert(satellite.density === 'compact', 'satellite density mismatch');

    const modern = choose('modern');
    approx(modern.stage.top, 0, 1, 'modern stage top');
    approx(modern.stage.left, 0, 1, 'modern stage left');
    assert(modern.layout === 'modern-atlas', 'modern layout mismatch');
    assert(modern.icons === 'modern', 'modern icon family mismatch');
    assert(!overlaps(modern.topbar, modern.panel), 'modern floating toolbar overlaps left panel');
    const expectedModernTop = innerWidth <= 520 ? 6 : innerWidth <= 900 ? 10 : 20;
    approx(modern.topbar.top, expectedModernTop, 1.5, 'modern toolbar vertical position');

    const ancient = choose('ancient');
    const ancientInsets = innerWidth <= 520 ? {top:66,side:6,bottom:6} : innerWidth <= 900 ? {top:78,side:10,bottom:10} : {top:88,side:18,bottom:18};
    approx(ancient.stage.top, ancientInsets.top, 1.5, 'ancient stage top');
    approx(ancient.stage.left, ancientInsets.side, 1.5, 'ancient stage left');
    approx(innerWidth - ancient.stage.right, ancientInsets.side, 1.5, 'ancient stage right inset');
    approx(innerHeight - ancient.stage.bottom, ancientInsets.bottom, 1.5, 'ancient stage bottom inset');
    assert(ancient.layout === 'ancient-atlas', 'ancient layout mismatch');
    assert(ancient.icons === 'engraved', 'ancient icon family mismatch');
    assert(ancient.typography === 'serif', 'ancient typography mismatch');
    assert(/Georgia|serif/i.test(ancient.fontFamily), 'ancient serif font stack is not active');
    assert(ancient.topbar.bottom < ancient.stage.top, 'ancient topbar overlaps framed map viewport');

    const expectedLabels = {
      satellite: '原生 · 卫星',
      modern: '手绘 · 现代',
      ancient: '手绘 · 古式'
    };
    const expectedUiIcons = {
      satellite:{map:'#i-map',markers:'#i-pin',layers:'#i-layers',settings:'#i-settings',about:'#i-info',locate:'#i-locate',home:'#i-home'},
      modern:{map:'#i-theme-modern-map',markers:'#i-theme-modern-pin',layers:'#i-theme-modern-layers',settings:'#i-theme-modern-settings',about:'#i-theme-modern-info',locate:'#i-theme-modern-locate',home:'#i-theme-modern-home'},
      ancient:{map:'#i-theme-engraved-map',markers:'#i-theme-engraved-pin',layers:'#i-theme-engraved-layers',settings:'#i-theme-engraved-settings',about:'#i-theme-engraved-info',locate:'#i-theme-engraved-locate',home:'#i-theme-engraved-home'}
    };
    for (const item of [satellite, modern, ancient]) {
      for (const label of [item.activeLabel, item.settingsLabel, item.aboutLabel]) {
        assert(label === expectedLabels[item.id], item.id + ': dynamic theme label mismatch: ' + label);
      }
      for (const [semantic,href] of Object.entries(expectedUiIcons[item.id])) {
        assert(item.uiIcons[semantic] === href, item.id + ': UI icon family mismatch for ' + semantic + ': ' + item.uiIcons[semantic]);
      }
    }
    assert(modern.panelHeadHeight >= satellite.panelHeadHeight + 1.5, 'modern comfortable density is not applied to panel headers');
    assert(ancient.panelHeadHeight >= satellite.panelHeadHeight + 1.5, 'ancient comfortable density is not applied to panel headers');
    assert(/Segoe UI|sans-serif/i.test(modern.fontFamily), 'modern UI font stack is not active');
    assert(satellite.footerBg !== modern.footerBg, 'modern settings footer still uses satellite surface');
    assert(satellite.footerBg !== ancient.footerBg, 'ancient settings footer still uses satellite surface');
    assert(modern.footerBg !== ancient.footerBg, 'modern and ancient settings footer surfaces collapsed together');

    document.body.dataset.themeBrowserTest = encodeURIComponent(JSON.stringify({
      ok: failures.length === 0,
      failures,
      viewport: {width:innerWidth,height:innerHeight},
      themes: {satellite,modern,ancient}
    }));
  } catch (error) {
    document.body.dataset.themeBrowserTest = encodeURIComponent(JSON.stringify({
      ok:false,
      failures:[String(error && error.stack || error)]
    }));
  }
})();
'@

$needle = '<script src="/app.js"></script>'
if (-not $html.Contains($needle)) { throw 'WebUI app script tag not found' }
$nl = [Environment]::NewLine
$replacement = '<script>' + $nl + $app + $nl + '</script>' + $nl + '<script>' + $nl + $browserTest + $nl + '</script>'
$harness = $html.Replace($needle, $replacement)

$tmp = Join-Path ([IO.Path]::GetTempPath()) ('cartograph-v31-theme-browser-' + [guid]::NewGuid().ToString('N'))
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
      '--headless=new',
      '--no-sandbox',
      '--disable-gpu',
      '--disable-dev-shm-usage',
      '--no-first-run',
      '--disable-background-networking',
      ('--window-size=' + $viewport.Width + ',' + $viewport.Height),
      "--user-data-dir=$profile",
      '--dump-dom',
      $uri
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

    Write-Host ("Cartograph V3.1 theme browser smoke: OK ({0}x{1})" -f $result.viewport.width,$result.viewport.height)
    foreach ($id in @('satellite','modern','ancient')) {
      $t = $result.themes.$id
      Write-Host ("  {0}: layout={1}, stage=({2},{3}) {4}x{5}, panelMax={6}" -f
        $id,$t.layout,[math]::Round($t.stage.left),[math]::Round($t.stage.top),
        [math]::Round($t.stage.width),[math]::Round($t.stage.height),[math]::Round($t.panelMaxHeight))
    }
  }
}
finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

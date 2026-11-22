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
  const rect = el => {
    const r = el.getBoundingClientRect();
    return {left:r.left, top:r.top, right:r.right, bottom:r.bottom, width:r.width, height:r.height};
  };
  const choose = id => {
    const card = document.querySelector('[data-theme-choice="' + id + '"]');
    assert(!!card, 'missing theme card: ' + id);
    if (card) card.click();
    const layersNav = document.querySelector('[data-nav="layers"]');
    const layersPanel = document.querySelector('#layersPanel');
    if (getComputedStyle(layersPanel).display === 'none') layersNav.click();

    const app = document.querySelector('#app');
    const stage = document.querySelector('#mapStage');
    const topbar = document.querySelector('.topbar');
    const panel = document.querySelector('#layersPanel');
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
      renderer: app.dataset.style,
      activeLabel: document.querySelector('#activeThemeLabel').textContent.trim(),
      settingsLabel: document.querySelector('#interfaceThemeValue').textContent.trim(),
      aboutLabel: document.querySelector('#aboutThemeValue').textContent.trim(),
      stored,
      stage: rect(stage),
      topbar: rect(topbar),
      panel: rect(panel),
      panelDisplay: getComputedStyle(panel).display,
      panelMaxHeight: parseFloat(getComputedStyle(panel).maxHeight) || 0,
      cardActive: activeCard.classList.contains('active'),
      fontFamily: getComputedStyle(app).fontFamily
    };

    assert(result.theme === id, id + ': data-theme mismatch');
    assert(result.renderer === 'satellite', id + ': renderer fallback changed unexpectedly');
    assert(result.stored === id, id + ': theme was not persisted');
    assert(result.cardActive, id + ': selected card is not active');
    assert(result.panelDisplay !== 'none', id + ': layers panel did not open');
    assert(result.panel.top >= result.stage.top - 0.5, id + ': panel starts above map viewport');
    assert(result.panel.bottom <= result.stage.bottom + 0.5, id + ': panel extends below map viewport');

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
    assert(modern.topbar.left > modern.panel.right + 20, 'modern floating toolbar overlaps left panel');
    assert(modern.topbar.top >= 18 && modern.topbar.top <= 22, 'modern toolbar vertical position mismatch');

    const ancient = choose('ancient');
    approx(ancient.stage.top, 88, 1.5, 'ancient stage top');
    approx(ancient.stage.left, 18, 1.5, 'ancient stage left');
    approx(innerWidth - ancient.stage.right, 18, 1.5, 'ancient stage right inset');
    approx(innerHeight - ancient.stage.bottom, 18, 1.5, 'ancient stage bottom inset');
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
    for (const item of [satellite, modern, ancient]) {
      for (const label of [item.activeLabel, item.settingsLabel, item.aboutLabel]) {
        assert(label === expectedLabels[item.id], item.id + ': dynamic theme label mismatch: ' + label);
      }
    }

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
  $profile = Join-Path $tmp 'profile'
  New-Item -ItemType Directory -Path $profile | Out-Null

  $browser = Find-Chromium
  $uri = [Uri]::new($harnessPath).AbsoluteUri
  $args = @(
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    '--disable-dev-shm-usage',
    '--no-first-run',
    '--disable-background-networking',
    '--window-size=1440,900',
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
    foreach ($failure in $result.failures) { Write-Error $failure }
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
finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

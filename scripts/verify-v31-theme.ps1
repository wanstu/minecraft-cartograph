$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$appPath = Join-Path $root 'v3.1/common/src/main/resources/webui/app.js'
$htmlPath = Join-Path $root 'v3.1/common/src/main/resources/webui/index.html'
$settingsPath = Join-Path $root 'v3.1/common/src/main/java/com/wanstu/cartographv3/CartographSettings.java'
$themePath = Join-Path $root 'v3.1/common/src/main/java/com/wanstu/cartographv3/CartographFrontendTheme.java'
$serverPath = Join-Path $root 'v3.1/common/src/main/java/com/wanstu/cartographv3/CartographWebUiServer.java'
$dialogsPath = Join-Path $root 'v3.1/common/src/main/java/com/wanstu/cartographv3/InGameMapDialogs.java'
$nativeUiPath = Join-Path $root 'v3.1/common/src/main/java/com/wanstu/cartographv3/InGameMapUi.java'
$nativeTilesPath = Join-Path $root 'v3.1/common/src/main/java/com/wanstu/cartographv3/InGameMapTiles.java'
$themeStylePath = Join-Path $root 'v3.1/common/src/main/java/com/wanstu/cartographv3/FrontendThemeStyle.java'

$app = Get-Content -Raw -Encoding UTF8 $appPath
$html = Get-Content -Raw -Encoding UTF8 $htmlPath
$sharedSettings = Get-Content -Raw -Encoding UTF8 $settingsPath
$sharedTheme = Get-Content -Raw -Encoding UTF8 $themePath
$server = Get-Content -Raw -Encoding UTF8 $serverPath
$dialogs = Get-Content -Raw -Encoding UTF8 $dialogsPath
$nativeUi = Get-Content -Raw -Encoding UTF8 $nativeUiPath
$nativeTiles = Get-Content -Raw -Encoding UTF8 $nativeTilesPath
$themeStyle = Get-Content -Raw -Encoding UTF8 $themeStylePath
$errors = [System.Collections.Generic.List[string]]::new()

function Require-Contains([string]$text,[string]$needle,[string]$message) {
  if (-not $text.Contains($needle)) { $script:errors.Add($message) }
}

$themeRows = @(
  "satellite:{id:'satellite',label:'原生 · 卫星',layout:'technical',iconFamily:'technical',density:'compact',typography:'sans',mapRenderer:'satellite',mapReady:true}",
  "modern:{id:'modern',label:'手绘 · 现代',layout:'modern-atlas',iconFamily:'modern',density:'comfortable',typography:'sans',mapRenderer:'modern',mapReady:true}",
  "ancient:{id:'ancient',label:'手绘 · 古式',layout:'ancient-atlas',iconFamily:'engraved',density:'comfortable',typography:'serif',mapRenderer:'ancient',mapReady:true}"
)
foreach ($row in $themeRows) { Require-Contains $app $row "theme registry contract missing or changed: $row" }

foreach ($id in 'satellite','modern','ancient') {
  $needle = 'data-theme-choice="' + $id + '"'
  Require-Contains $html $needle "theme selector card missing: $id"
}
foreach ($hook in @(
  'data-theme-layout="modern-atlas"',
  'data-theme-layout="ancient-atlas"',
  'data-theme-icons="modern"',
  'data-theme-icons="engraved"',
  '--theme-switch-off:',
  '--theme-switch-on:',
  '--theme-warning-bg:',
  '--theme-warning-text:',
  '--theme-scroll-track:',
  '--theme-scroll-thumb:',
  '--theme-dirty:',
  '--theme-offline-bg:',
  '--theme-offline-border:',
  '--theme-offline-text:',
  'theme-preview-toolbar',
  'theme-preview-panel',
  'theme-preview-controls',
  '--theme-map-vertical-inset:',
  '--theme-float-top:',
  '--theme-float-bottom:',
  '--theme-panel-head-height:',
  '--theme-marker-chrome-height:',
  '--theme-close-color:',
  '--theme-close-hover:',
  '--theme-overlay-bg:',
  '--theme-focus:',
  '--theme-footer-bg:'
)) { Require-Contains $html $hook "theme CSS hook missing: $hook" }

foreach ($hook in @(
  'app.dataset.theme=',
  'app.dataset.themeLayout=',
  'app.dataset.themeIcons=',
  'app.dataset.themeDensity=',
  'app.dataset.themeTypography=',
  'app.dataset.mapRenderer='
)) { Require-Contains $app $hook "applyFrontendTheme hook missing: $hook" }

Require-Contains $app 'const THEME_UI_ICON_SYMBOLS = Object.freeze({' 'theme UI icon registry missing'
Require-Contains $app 'function applyThemeUiIcons()' 'theme UI icon application hook missing'
Require-Contains $html 'data-theme-ui-icon="map"' 'theme-aware UI icon markup missing'
Require-Contains $app 'const MAP_RENDERERS = Object.freeze({' 'map renderer registry missing'
Require-Contains $app 'satellite:renderThemeTerrain' 'satellite renderer registry entry missing'
Require-Contains $app 'modern:renderThemeTerrain' 'modern renderer registry entry missing'
Require-Contains $app 'ancient:renderThemeTerrain' 'ancient renderer registry entry missing'
Require-Contains $app 'function refreshThemeTerrain()' 'theme terrain refresh hook missing'
Require-Contains $html 'id="interfaceThemeValue"' 'dynamic current-theme field missing'
Require-Contains $app "interfaceThemeValue.textContent=theme.label" 'dynamic current-theme field is not wired'

foreach ($token in @(
  'SATELLITE("satellite", "原生 · 卫星")',
  'MODERN("modern", "手绘 · 现代")',
  'ANCIENT("ancient", "手绘 · 古式")',
  'CartographFrontendTheme next()',
  'static CartographFrontendTheme parse(String raw)'
)) { Require-Contains $sharedTheme $token "shared frontend-theme enum is incomplete: $token" }
foreach ($token in @(
  'private static CartographFrontendTheme frontendTheme = CartographFrontendTheme.SATELLITE;',
  'static synchronized CartographFrontendTheme frontendTheme()',
  'static synchronized void setFrontendTheme(CartographFrontendTheme value)',
  'p.setProperty("frontendTheme", frontendTheme.id())'
)) { Require-Contains $sharedSettings $token "shared frontend-theme persistence is incomplete: $token" }
foreach ($token in @(
  'String frontendTheme) {}',
  '"frontendTheme", CartographSettings.frontendTheme().id()',
  'CartographSettings.setFrontendTheme(',
  'CartographFrontendTheme.parse(settings.frontendTheme())'
)) { Require-Contains $server $token "WebUI runtime theme bridge is incomplete: $token" }
foreach ($token in @(
  'action(ui, row, "前端主题", theme.label()',
  'CartographSettings.setFrontendTheme(theme.next())',
  'CartographSettings.frontendTheme().label()'
)) { Require-Contains $dialogs $token "native theme selector is incomplete: $token" }
foreach ($token in @(
  "wsRequest('runtime_settings',{payload:{frontendTheme:id}})",
  'r.frontendTheme&&FRONTEND_THEMES[r.frontendTheme]',
  'settings.frontendTheme=r.frontendTheme',
  'refreshThemeTerrain()'
)) { Require-Contains $app $token "WebUI shared-theme synchronization is incomplete: $token" }

foreach ($token in @(
  'record Palette(',
  'case SATELLITE -> SATELLITE;',
  'case MODERN -> MODERN;',
  'case ANCIENT -> ANCIENT;',
  'static long renderSignature()',
  'static int[] renderPixels(SurfaceTile tile, int stride)',
  'CartographSettings.frontendTheme()',
  'SatelliteSurfaceRenderer.renderPixels(tile, stride)'
)) { Require-Contains $themeStyle $token "shared native/map theme style is incomplete: $token" }
foreach ($token in @(
  'FrontendThemeStyle.Palette palette = FrontendThemeStyle.palette();',
  'BACKGROUND = palette.background();',
  'PANEL = palette.panel();',
  'MARKER_LABEL = palette.markerLabel();'
)) { Require-Contains $nativeUi $token "native UI theme palette is incomplete: $token" }
foreach ($token in @(
  'FrontendThemeStyle.renderSignature()',
  'FrontendThemeStyle.renderPixels(tile, stride)'
)) { Require-Contains $nativeTiles $token "native themed terrain renderer is incomplete: $token" }
foreach ($token in @(
  'FrontendThemeStyle.renderSignature()',
  'FrontendThemeStyle.render(facts).rgb()'
)) { Require-Contains $server $token "WebUI themed tile renderer is incomplete: $token" }

if ($html.Contains('深色（当前唯一主题）')) { $errors.Add('stale single-theme copy remains in WebUI') }

$match = [regex]::Match($app, 'function applyFrontendTheme\(\)\{(?<body>[\s\S]*?)function selectFrontendTheme')
if (-not $match.Success) {
  $errors.Add('cannot locate applyFrontendTheme boundary')
} else {
  $body = $match.Groups['body'].Value
  foreach ($token in @('connectSocket(','wsRequest(','tileCache.clear(','regionCache.clear(')) {
    if ($body.Contains($token)) { $errors.Add("theme application must remain presentation-only: found $token") }
  }
}

if ($errors.Count -gt 0) {
  foreach ($error in $errors) { Write-Error $error }
  exit 1
}

Write-Host 'Cartograph V3.1 frontend theme contract: OK'

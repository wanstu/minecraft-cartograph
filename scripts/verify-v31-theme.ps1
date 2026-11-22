$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$appPath = Join-Path $root 'v3.1/common/src/main/resources/webui/app.js'
$htmlPath = Join-Path $root 'v3.1/common/src/main/resources/webui/index.html'

$app = Get-Content -Raw -Encoding UTF8 $appPath
$html = Get-Content -Raw -Encoding UTF8 $htmlPath
$errors = [System.Collections.Generic.List[string]]::new()

function Require-Contains([string]$text,[string]$needle,[string]$message) {
  if (-not $text.Contains($needle)) { $script:errors.Add($message) }
}

$themeRows = @(
  "satellite:{id:'satellite',label:'原生 · 卫星',layout:'technical',iconFamily:'technical',density:'compact',typography:'sans',mapRenderer:'satellite',mapReady:true}",
  "modern:{id:'modern',label:'手绘 · 现代',layout:'modern-atlas',iconFamily:'modern',density:'comfortable',typography:'sans',mapRenderer:'satellite',mapReady:false}",
  "ancient:{id:'ancient',label:'手绘 · 古式',layout:'ancient-atlas',iconFamily:'engraved',density:'comfortable',typography:'serif',mapRenderer:'satellite',mapReady:false}"
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
  '--theme-focus:'
)) { Require-Contains $html $hook "theme CSS hook missing: $hook" }

foreach ($hook in @(
  'app.dataset.theme=',
  'app.dataset.themeLayout=',
  'app.dataset.themeIcons=',
  'app.dataset.themeDensity=',
  'app.dataset.themeTypography=',
  'app.dataset.mapRenderer='
)) { Require-Contains $app $hook "applyFrontendTheme hook missing: $hook" }

Require-Contains $app 'const MAP_RENDERERS = Object.freeze({' 'map renderer registry missing'
Require-Contains $app 'satellite:renderSatelliteTerrain' 'satellite renderer registry entry missing'
Require-Contains $html 'id="interfaceThemeValue"' 'dynamic current-theme field missing'
Require-Contains $app "interfaceThemeValue.textContent=theme.label" 'dynamic current-theme field is not wired'

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

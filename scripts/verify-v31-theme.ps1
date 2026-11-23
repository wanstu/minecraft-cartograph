$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$javaRoot = Join-Path $root 'v3.1/common/src/main/java/com/wanstu/cartographv3'
$app = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'v3.1/common/src/main/resources/webui/app.js')
$html = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'v3.1/common/src/main/resources/webui/index.html')
$settings = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'CartographSettings.java')
$mode = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'MapDrawingMode.java')
$registry = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'MapVisualStyleRegistry.java')
$processor = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'MapVisualStyleProcessor.java')
$processors = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'MapVisualStyleProcessors.java')
$satelliteData = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'SatelliteMapData.java')
$handData = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'HandDrawnMapData.java')
$renderer = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'MapPresentationRenderer.java')
$server = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'CartographWebUiServer.java')
$dialogs = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'InGameMapDialogs.java')
$nativeUi = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'InGameMapUi.java')
$nativeTiles = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'InGameMapTiles.java')
$renderedAtlasStore = Get-Content -Raw -Encoding UTF8 (Join-Path $javaRoot 'PersistentRenderedAtlasStore.java')
$errors = [System.Collections.Generic.List[string]]::new()

function Require-Contains([string]$text,[string]$needle,[string]$message) {
  if (-not $text.Contains($needle)) { $script:errors.Add($message) }
}
function Require-Absent([string]$text,[string]$needle,[string]$message) {
  if ($text.Contains($needle)) { $script:errors.Add($message) }
}

foreach ($token in @(
  'SATELLITE("satellite", "卫星")',
  'HAND_DRAWN("hand_drawn", "手绘")',
  'return this == SATELLITE ? HAND_DRAWN : SATELLITE;'
)) { Require-Contains $mode $token "drawing-mode contract missing: $token" }
Require-Absent $mode 'ANCIENT' 'drawing mode must stay closed to satellite/hand-drawn'
Require-Absent $mode 'ANIME' 'drawing mode must stay closed to satellite/hand-drawn'
Require-Absent $mode 'MODERN' 'drawing mode must stay closed to satellite/hand-drawn'

foreach ($token in @(
  '"modern", "现代"',
  '"ancient", "古式"',
  '"anime", "动漫"',
  'MapVisualStyleProcessors.MODERN',
  'MapVisualStyleProcessors.ANCIENT',
  'MapVisualStyleProcessors.ANIME',
  'private static void register(Style style)'
)) { Require-Contains $registry $token "visual-style registry contract missing: $token" }

foreach ($token in @(
  'int[] renderSatellite(SatelliteMapData data, int stride);',
  'int[] renderHandDrawn(HandDrawnMapData data, int stride);'
)) { Require-Contains $processor $token "visual-style processor dual-entry contract missing: $token" }

foreach ($token in @(
  'class Modern implements MapVisualStyleProcessor',
  'class Ancient implements MapVisualStyleProcessor',
  'class Anime implements MapVisualStyleProcessor',
  'renderSatellite(SatelliteMapData data, int stride)',
  'renderHandDrawn(HandDrawnMapData data, int stride)'
)) { Require-Contains $processors $token "built-in visual-style processor incomplete: $token" }

foreach ($token in @(
  'record SatelliteMapData(SurfaceTile facts)',
  'SatelliteSurfaceRenderer.renderPixels(facts, stride)'
)) { Require-Contains $satelliteData $token "satellite data wrapper incomplete: $token" }

foreach ($token in @(
  'record HandDrawnMapData(',
  'static final int CELL_SIZE = 4;',
  'static final int GRID = SurfaceTile.SIZE / CELL_SIZE;',
  'private enum TerrainClass',
  'TerrainClass.VEGETATION',
  'static final int WATER_THRESHOLD = 19;',
  'classes[cell] = waterPct >= WATER_THRESHOLD',
  'depth[cell] = waterCount == 0',
  'depth = smoothWaterDepth(depth, water);',
  'int waterDepthAtPixel(int localX, int localZ)',
  'int shorelineStrengthAtPixel(int localX, int localZ, int radius)',
  'Arrays.sort(heights, 0, heightCount);',
  'relief[cell] = heights[heightCount / 2];',
  'classes = smoothClasses(classes, water);',
  'relief = smoothRelief(relief);',
  'range = smoothRanges(range);',
  'static HandDrawnMapData from(SurfaceTile tile)',
  'int[] abstractPixels(int stride)',
  'interpolateColor(localX, localZ)'
)) { Require-Contains $handData $token "hand-drawn data wrapper incomplete: $token" }

foreach ($token in @(
  'applyHandDrawnWaterDepth(color, depth, style)',
  'applyHandDrawnShoreline(color, shoreline, style)',
  'HandDrawnMapData.WATER_THRESHOLD',
  'if (water) {',
  'private static int applyHandDrawnWaterDepth',
  'private static int applyHandDrawnShoreline'
)) { Require-Contains $processors $token "hand-drawn water presentation incomplete: $token" }

foreach ($token in @(
  'private static final long PIPELINE_VERSION = 4L;',
  'MapVisualStyleProcessor processor = CartographSettings.visualStyle().processor();',
  'switch (CartographSettings.drawingMode())',
  'case SATELLITE -> processor.renderSatellite(SatelliteMapData.from(tile), stride);',
  'case HAND_DRAWN -> processor.renderHandDrawn(HandDrawnMapData.from(tile), stride);'
)) { Require-Contains $renderer $token "presentation renderer composition missing: $token" }

foreach ($token in @(
  'private static final int SCHEMA = 3;',
  'private static MapDrawingMode drawingMode = MapDrawingMode.SATELLITE;',
  'private static String visualStyleId = MapVisualStyleRegistry.defaultStyle().id();',
  'static synchronized MapDrawingMode drawingMode()',
  'static synchronized MapVisualStyleRegistry.Style visualStyle()',
  'p.setProperty("drawingMode", drawingMode.id())',
  'p.setProperty("visualStyle", visualStyleId)'
)) { Require-Contains $settings $token "shared presentation persistence incomplete: $token" }
Require-Absent $settings 'frontendTheme' 'legacy frontendTheme persistence must be removed'

foreach ($token in @(
  'String drawingMode,',
  'String visualStyle) {}',
  '"drawingMode", CartographSettings.drawingMode().id()',
  '"visualStyle", CartographSettings.visualStyle().id()',
  'CartographSettings.setDrawingMode(',
  'MapDrawingMode.parse(settings.drawingMode())',
  'CartographSettings.setVisualStyle(settings.visualStyle())',
  'MapPresentationRenderer.renderSignature()',
  'MapPresentationRenderer.render(facts).rgb()'
)) { Require-Contains $server $token "runtime presentation bridge incomplete: $token" }

foreach ($token in @(
  'action(ui, row, "绘图方式", mode.label()',
  'CartographSettings.setDrawingMode(mode.next())',
  'action(ui, row, "绘图风格", style.label()',
  'MapVisualStyleRegistry.next(style.id()).id()'
)) { Require-Contains $dialogs $token "native drawing/style selector incomplete: $token" }

foreach ($token in @(
  'MapVisualStyleProcessor.Palette palette = MapPresentationRenderer.palette();',
  'MapPresentationRenderer.renderSignature()',
  'MapPresentationRenderer.renderPixels(tile, stride)'
)) {
  if (-not ($nativeUi.Contains($token) -or $nativeTiles.Contains($token))) {
    $errors.Add("native presentation integration missing: $token")
  }
}

foreach ($token in @(
  'private record TextureKey(Region region, int stride, long signature) {}',
  'private long targetSignature;',
  'frame = List.of();',
  'targetSignature = appearance;',
  'findDisplayAtlas(',
  'targetSignature, keep',
  'PersistentRenderedAtlasStore.load(',
  'PersistentRenderedAtlasStore.save(',
  'long fingerprint = tile.fingerprint64();',
  'if (atlas.fingerprints[index] == fingerprint) continue;',
  'Never keep drawing the previous presentation',
  'Never cross signatures here'
)) { Require-Contains $nativeTiles $token "native rendered-atlas cache contract missing: $token" }
Require-Absent $nativeTiles 'private long displaySignature;' 'previous presentation must never remain display-authoritative'
Require-Absent $nativeTiles 'displaySignature != targetSignature' 'cross-style display fallback must not return'
Require-Absent $nativeTiles 'appearance != signature) {' 'legacy style-switch close path must not return'
foreach ($token in @(
  'final class PersistentRenderedAtlasStore',
  'record Snapshot(long[] fingerprints, int[] pixels)',
  'cartograph-v31-rendered-atlas-v1',
  'static Optional<Snapshot> load(',
  'static void save(',
  'StandardCopyOption.ATOMIC_MOVE'
)) { Require-Contains $renderedAtlasStore $token "persistent rendered-atlas store incomplete: $token" }

foreach ($token in @(
  "const DRAWING_MODES = Object.freeze({",
  "satellite:{id:'satellite',label:'卫星'}",
  "hand_drawn:{id:'hand_drawn',label:'手绘'}",
  "const VISUAL_STYLES = Object.freeze({",
  "modern:{id:'modern',label:'现代'",
  "ancient:{id:'ancient',label:'古式'",
  "anime:{id:'anime',label:'动漫'",
  "drawingMode:'satellite', visualStyle:'modern'",
  'function applyPresentation()',
  'async function selectDrawingMode(id)',
  'async function selectVisualStyle(id)',
  'function refreshPresentationTerrain()',
  "wsRequest('runtime_settings',{payload:{drawingMode:id}})",
  "wsRequest('runtime_settings',{payload:{visualStyle:id}})",
  'app.dataset.mapRenderer=mode.id'
)) { Require-Contains $app $token "WebUI drawing/style runtime incomplete: $token" }

foreach ($id in 'satellite','hand_drawn') {
  Require-Contains $html ('data-drawing-mode="' + $id + '"') "drawing-mode card missing: $id"
}
foreach ($id in 'modern','ancient','anime') {
  Require-Contains $html ('data-visual-style="' + $id + '"') "visual-style card missing: $id"
  Require-Contains $html ('.app[data-theme="' + $id + '"]') "visual-style CSS missing: $id"
}

foreach ($token in @('data-theme-choice','frontendTheme','CartographFrontendTheme','FrontendThemeStyle')) {
  Require-Absent $app $token "legacy combined-theme token remains in app.js: $token"
}
Require-Absent $html 'data-theme-choice' 'legacy combined-theme selector remains in WebUI'

$match = [regex]::Match($app, 'function applyPresentation\(\)\{(?<body>[\s\S]*?)async function selectDrawingMode')
if (-not $match.Success) {
  $errors.Add('cannot locate applyPresentation boundary')
} else {
  $body = $match.Groups['body'].Value
  foreach ($token in @('connectSocket(','wsRequest(','tileCache.clear(','regionCache.clear(')) {
    if ($body.Contains($token)) { $errors.Add("presentation application must remain side-effect-light: found $token") }
  }
}

if ($errors.Count -gt 0) {
  foreach ($error in $errors) { Write-Error $error }
  exit 1
}

Write-Host 'Cartograph V3.1 drawing-mode / visual-style contract: OK'

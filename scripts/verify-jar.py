#!/usr/bin/env python3
"""Validate the actual built mod, not just a renamed ZIP or empty adapter."""
import pathlib
import re
import sys
import zipfile

if len(sys.argv) != 4:
    sys.exit("usage: verify-jar.py <jar> <loader> <version>")

jar, loader, version = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
pattern = rf"Cartograph-{re.escape(version)}-mc[^/]+-{re.escape(loader)}\.jar"
if not re.fullmatch(pattern, jar.name):
    sys.exit(f"unexpected jar filename: {jar.name}")
if not jar.is_file() or jar.stat().st_size < 10_000:
    sys.exit(f"missing/empty release jar: {jar}")

with zipfile.ZipFile(jar) as z:
    names = set(z.namelist())
    metadata = "META-INF/neoforge.mods.toml" if loader == "neoforge" else "fabric.mod.json"
    common = [
        "com/wanstu/cartograph/core/SurveyLedger.class",
        "com/wanstu/cartograph/core/AtlasPage.class",
        "com/wanstu/cartograph/core/SpiralCoverage.class",
        "com/wanstu/cartograph/world/ExistingRegionIndex.class",
        "com/wanstu/cartograph/world/McaChunkReader.class",
    ]
    adapter = [
        "com/wanstu/cartograph/CartographMod.class",
        "com/wanstu/cartograph/AtlasState.class",
        "com/wanstu/cartograph/AtlasWorldData.class",
        "com/wanstu/cartograph/MapNetwork.class",
        "com/wanstu/cartograph/AtlasMapServer.class",
        "com/wanstu/cartograph/AtlasScreen.class",
        "com/wanstu/cartograph/AtlasActions.class",
        "com/wanstu/cartograph/AtlasExports.class",
        "com/wanstu/cartograph/SurfaceSampler.class",
        "com/wanstu/cartograph/SurfaceStructure.class",
        "com/wanstu/cartograph/AtlasMarker.class",
    ]
    resources = [
        "assets/cartograph/lang/en_us.json",
        "assets/cartograph/lang/zh_cn.json",
        "assets/cartograph/models/item/atlas.json",
        "data/cartograph/recipe/atlas.json",
        "data/cartograph/recipe/expand_atlas.json",
    ]
    required = [metadata] + common + (adapter + resources if loader == "neoforge" else [])
    missing = [file for file in required if file not in names]
    if missing:
        sys.exit("Missing JAR members: " + ", ".join(missing))
    if loader == "neoforge":
        import json
        for path in resources:
            json.loads(z.read(path).decode("utf-8"))
        modinfo = z.read(metadata).decode("utf-8")
        if 'modId="cartograph"' not in modinfo and "modId = \"cartograph\"" not in modinfo:
            sys.exit("NeoForge metadata does not declare cartograph")
        for cls in adapter:
            if z.getinfo(cls).file_size == 0:
                sys.exit(f"Empty game class in JAR: {cls}")
print(f"PASS release jar: {jar.name} ({jar.stat().st_size} bytes, validated classes/resources)")

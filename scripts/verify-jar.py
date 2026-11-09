#!/usr/bin/env python3
import pathlib, sys, zipfile
if len(sys.argv) != 4:
    sys.exit("usage: verify-jar.py <jar> <loader> <version>")
jar, loader, version = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
required_name = f"Cartograph-{version}-mc"
if not jar.name.startswith(required_name) or not jar.name.endswith(f"-{loader}.jar"):
    sys.exit(f"unexpected jar filename {jar.name}, expected Cartograph-{version}-mc<VERSION>-{loader}.jar")
if not jar.is_file() or jar.stat().st_size < 1000:
    sys.exit(f"missing/empty release jar: {jar}")
with zipfile.ZipFile(jar) as z:
    names = set(z.namelist())
    metadata = "META-INF/neoforge.mods.toml" if loader == "neoforge" else "fabric.mod.json"
    required = [metadata,
                "com/wanstu/cartograph/core/SurveyLedger.class",
                "com/wanstu/cartograph/core/AtlasPage.class",
                "com/wanstu/cartograph/world/ExistingRegionIndex.class"]
    for item in required:
        if item not in names: sys.exit(f"missing entry in jar: {item}")
print(f"PASS release jar: {jar.name} ({jar.stat().st_size} bytes)")

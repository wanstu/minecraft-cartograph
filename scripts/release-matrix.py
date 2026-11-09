#!/usr/bin/env python3
import argparse, json, pathlib, sys
root = pathlib.Path(__file__).resolve().parent.parent
spec = json.loads((root / "config/targets.json").read_text(encoding="utf-8"))
entries = spec["targets"]
parser = argparse.ArgumentParser()
parser.add_argument("--json", action="store_true", help="emit implemented GitHub Actions strategy matrix")
parser.add_argument("--all", action="store_true", help="show planned as well as implemented")
parser.add_argument("--ensure-release-ready", action="store_true", help="reject publication until functionality is accepted")
args = parser.parse_args()
if args.ensure_release_ready and not spec.get("release_ready", False):
    sys.exit("Cartograph is not approved for publishing: config/targets.json release_ready=false")
implemented = [item for item in entries if item["status"] == "implemented"]
for item in implemented:
    directory = root / item["adapter"]
    if not (directory / "gradlew").exists() or not (directory / "build.gradle").exists():
        sys.exit(f"Refusing to advertise unfinished adapter: {directory}")
if args.json:
    print(json.dumps({"include": implemented}, separators=(",", ":")))
else:
    for item in entries:
        if args.all or item["status"] == "implemented":
            print(f"{item['minecraft']:7} {item['loader']:9} Java {item['java']:2} {item['status']:12} {item['adapter']}")

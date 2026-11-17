#!/usr/bin/env python3
"""Validate the distribution JAR built by the V3.1 NeoForge project."""

import argparse
from pathlib import Path
import tomllib
from zipfile import ZipFile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    expected_name = f"Cartograph-V3.1-{args.version}-mc1.21.1-neoforge.jar"
    require(args.jar.name == expected_name, f"Expected artifact {expected_name}")

    with ZipFile(args.jar) as archive:
        invalid = archive.testzip()
        require(invalid is None, f"Invalid JAR entry: {invalid}")
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        mods = metadata.get("mods", [])
        require(
            len(mods) == 1
            and mods[0].get("modId") == "cartograph_v3"
            and mods[0].get("version") == args.version,
            "The JAR must contain one Cartograph V3 mod with the expected version",
        )
        require(metadata.get("modLoader") == "javafml", "Unexpected mod loader")
        dependencies = {
            item["modId"]: item
            for item in metadata.get("dependencies", {}).get("cartograph_v3", [])
        }
        require(
            dependencies.get("minecraft", {}).get("versionRange") == "[1.21.1]"
            and dependencies.get("neoforge", {}).get("type") == "required",
            "Expected dependencies on Minecraft 1.21.1 and NeoForge",
        )
        names = set(archive.namelist())
        for entry in (
            "com/wanstu/cartographv3/CartographV3Mod.class",
            "com/wanstu/cartographv3/CartographV3Client.class",
            "com/wanstu/cartographv3/MapProtocol.class",
            "webui/index.html",
        ):
            require(entry in names, f"The JAR is missing {entry}")
        require(
            not any(
                name.startswith(
                    ("com/wanstu/cartograph/", "com/wanstu/cartographv2/", "assets/cartograph/", "assets/cartograph_v2/")
                )
                for name in names
            ),
            "The V3.1 distribution contains legacy Cartograph entries",
        )

    print(f"Verified {args.jar.name}")


if __name__ == "__main__":
    main()

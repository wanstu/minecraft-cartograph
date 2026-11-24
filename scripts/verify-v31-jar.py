#!/usr/bin/env python3
"""Validate a distribution JAR built by a V3.1 loader project."""

import argparse
import json
from pathlib import Path
import tomllib
from zipfile import ZipFile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def verify_neoforge(archive, names, version, minecraft):
    require("fabric.mod.json" not in names, "Unexpected Fabric metadata")
    metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
    mods = metadata.get("mods", [])
    require(
        len(mods) == 1
        and mods[0].get("modId") == "cartograph_v3"
        and mods[0].get("version") == version,
        "The JAR must contain one Cartograph V3 mod with the expected version",
    )
    require(metadata.get("modLoader") == "javafml", "Unexpected mod loader")
    require("cartograph_v3.client.mixins.json" in {item.get("config") for item in metadata.get("mixins", [])},
            "Missing the client rendering mixin registration")
    dependencies = {
        item["modId"]: item
        for item in metadata.get("dependencies", {}).get("cartograph_v3", [])
    }
    require(
        dependencies.get("minecraft", {}).get("versionRange") == f"[{minecraft}]"
        and dependencies.get("neoforge", {}).get("type") == "required",
        f"Expected dependencies on Minecraft {minecraft} and NeoForge",
    )
    return ("CartographV3Mod", "CartographV3Client")


def verify_fabric(archive, names, version, minecraft):
    require("META-INF/neoforge.mods.toml" not in names, "Unexpected NeoForge metadata")
    metadata = json.loads(archive.read("fabric.mod.json"))
    require(
        metadata.get("id") == "cartograph_v3" and metadata.get("version") == version,
        "Expected the Cartograph V3 mod ID and version",
    )
    dependencies = metadata.get("depends", {})
    require(
        dependencies.get("minecraft") == minecraft
        and dependencies.get("java") == (">=25" if minecraft == "26.3" else ">=21")
        and all(dependencies.get(loader) for loader in ("fabricloader", "fabric-api")),
        f"Expected dependencies on Minecraft {minecraft}, Java, Fabric Loader and Fabric API",
    )
    entrypoints = metadata.get("entrypoints", {})
    require(
        entrypoints.get("main") == ["com.wanstu.cartographv3.FabricCartograph"]
        and entrypoints.get("client") == ["com.wanstu.cartographv3.FabricCartographClient"],
        "Expected the Fabric server and client entrypoints",
    )
    require(
        "cartograph_v3.mixins.json" in metadata.get("mixins", [])
        and "cartograph_v3.mixins.json" in names,
        "Missing the Fabric chunk save mixin config",
    )
    require("cartograph_v3.client.mixins.json" in metadata.get("mixins", []),
            "Missing the Fabric client rendering mixin registration")
    return ("FabricCartograph", "FabricCartographClient", "mixin/ChunkMapMixin")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("version")
    parser.add_argument("--loader", choices=("neoforge", "fabric"), default="neoforge")
    parser.add_argument("--minecraft", choices=("1.21.1", "26.3"), default="1.21.1")
    args = parser.parse_args()
    expected_name = f"Cartograph-V3.1-{args.version}-mc{args.minecraft}-{args.loader}.jar"
    require(args.jar.name == expected_name, f"Expected artifact {expected_name}")

    with ZipFile(args.jar) as archive:
        invalid = archive.testzip()
        require(invalid is None, f"Invalid JAR entry: {invalid}")
        names = set(archive.namelist())
        verify_loader = verify_fabric if args.loader == "fabric" else verify_neoforge
        loader_classes = verify_loader(archive, names, args.version, args.minecraft)
        for entry in (
            *(f"com/wanstu/cartographv3/{name}.class" for name in loader_classes),
            "com/wanstu/cartographv3/MapProtocol.class",
            "com/wanstu/cartographv3/InGameMapScreen.class",
            "assets/cartograph_v3/textures/gui/map-icons.png",
            "webui/index.html",
            "webui/app.js",
            "com/wanstu/cartographv3/MapWorldRenderPolicy.class",
            "com/wanstu/cartographv3/clientmixin/MapWorldRenderMixin.class",
            "cartograph_v3.client.mixins.json",
        ):
            require(entry in names, f"The JAR is missing {entry}")
        client_mixins = json.loads(archive.read("cartograph_v3.client.mixins.json"))
        require(client_mixins.get("client") == ["MapWorldRenderMixin"] and not client_mixins.get("mixins"),
                "Rendering mixins must only load on the client")
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

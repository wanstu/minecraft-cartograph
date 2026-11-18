# Cartograph V3.1 / 地图册

Minecraft 的游戏内地图。进入世界后会在后台收集真实地表数据，已经生成并保存的区块可以补画，未生成的区块保持空白。地图支持原生界面和独立浏览器界面。

## 下载与安装

从 [GitHub Releases](https://github.com/wanstu/minecraft-cartograph/releases) 选择和实例加载器对应的 JAR，放入 `mods/` 目录。需要 Minecraft 1.21.1 和 Java 21。

| 加载器 | 文件 | 依赖 |
|---|---|---|
| NeoForge | `Cartograph-V3.1-<版本>-mc1.21.1-neoforge.jar` | NeoForge 21.1.265 或更高的 1.21.1 版本 |
| Fabric | `Cartograph-V3.1-<版本>-mc1.21.1-fabric.jar` | Fabric Loader 和 Fabric API |

已有的 `v0.3.1` 正式发行包适用于 NeoForge。每个发行版本支持的加载器以附件为准。联机时，客户端和服务器安装一致的 Minecraft 版本、加载器和 Cartograph 版本。

## 使用地图

1. 在世界中按 **M** 打开原生地图。拖动平移，滚轮缩放。
2. 在「图层」切换自然结构、自定义标点和区块网格。右键地图可创建标点。
3. 「设置 → 显示」可调节字号和 UI 缩放倍率。浏览器地图入口在「设置 → 通用」。

地表缓存按存档和维度保存。大范围缩小时会切换到低分辨率概览，放大后逐步恢复地表细节。

## 构建

```bash
(cd v3.1/neoforge-1.21.1 && bash ./gradlew --no-daemon check build)
(cd v3.1/fabric-1.21.1 && bash ./gradlew --no-daemon check build)
```

产物分别在两个模块的 `build/libs/`。两边共用 `v3.1/common-1.21.1` 的地图实现和界面。发布步骤见 [GitHub 发布说明](docs/GITHUB_RELEASE.md)。

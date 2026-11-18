# Cartograph V3.1 / 地图册

Minecraft 的游戏内地图。进入世界后会在后台收集真实地表数据，已经生成并保存的区块可以补画，未生成的区块保持空白。地图支持原生界面和独立浏览器界面。

## 下载与安装

从 [GitHub Releases](https://github.com/wanstu/minecraft-cartograph/releases) 选择与游戏版本和加载器一致的 JAR，放入 `mods/` 目录。

| Minecraft | Java | NeoForge | Fabric |
|---|---|---|---|
| 1.21.1 | 21 | 21.1.265 或更高的 1.21.1 版本 | Fabric Loader 和 Fabric API |
| 26.3 | 25 | 26.3.0.40-beta 或更高的 26.3 版本 | Fabric Loader 0.19.5 或更高版本及 26.3 Fabric API |

发行附件的命名是 `Cartograph-V3.1-<版本>-mc<Minecraft 版本>-<加载器>.jar`。已有的 `v0.3.1` 正式发行包适用于 1.21.1 NeoForge。联机时，客户端和服务器安装一致的 Minecraft 版本、加载器和 Cartograph 版本。

## 使用地图

1. 在世界中按 **M** 打开原生地图。拖动平移，滚轮缩放。
2. 在「图层」切换自然结构、自定义标点和区块网格。右键地图可创建标点。
3. 「设置 → 显示」可调节字号和 UI 缩放倍率。浏览器地图入口在「设置 → 通用」。

地表缓存按游戏版本、存档和维度保存。大范围缩小时会切换到低分辨率概览，放大后逐步恢复地表细节。

## 构建

使用对应 JDK 在 `v3.1/<加载器>-<Minecraft 版本>` 运行 `bash ./gradlew --no-daemon check build`。JAR 位于模块的 `build/libs/`。构建 26.3 NeoForge 时，Gradle 还需能找到供 NeoForm 使用的 JDK 21。

各目标共用 `v3.1/common` 的地图实现和界面。与 Minecraft 接口相关的代码放在 `v3.1/common-1.21.1` 和 `v3.1/common-26.3`。发布步骤见 [GitHub 发布说明](docs/GITHUB_RELEASE.md)。

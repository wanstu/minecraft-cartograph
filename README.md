# Cartograph V3.1 / 地图册

Minecraft 的游戏内地图。进入世界后会在后台收集真实地表数据，已经生成并保存的区块可以补画，未生成的区块保持空白。地图支持原生界面和独立浏览器界面。

## 下载与安装

从 [GitHub Releases](https://github.com/wanstu/minecraft-cartograph/releases/latest) 下载 `Cartograph-V3.1-<版本>-mc1.21.1-neoforge.jar` 放入实例的 `mods/` 目录。

| 项目 | 要求 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.265 或更高的 1.21.1 版本 |
| Java | 21 |

联机时，客户端和服务器安装同一版本的 JAR。

## 使用地图

1. 在世界中按 **M** 打开原生地图。拖动平移，滚轮缩放。
2. 在「图层」切换自然结构、自定义标点和区块网格。右键地图可创建标点。
3. 「设置 → 显示」可调节字号和 UI 缩放倍率。浏览器地图入口在「设置 → 通用」。

地表缓存按存档和维度保存。大范围缩小时会切换到低分辨率概览，放大后逐步恢复地表细节。

## 构建

```bash
cd v3.1/neoforge-1.21.1
bash ./gradlew --no-daemon check build
```

构建产物在 `v3.1/neoforge-1.21.1/build/libs/`。发布步骤见 [GitHub 发布说明](docs/GITHUB_RELEASE.md)。

# Cartograph / 绘图师

A survival atlas for Minecraft that maps **only actually generated / saved terrain**. No seed prediction, no forced chunk generation, and no underground radar.

适用于喜欢建设而不希望为了绘图遍历整张地图的玩家。已经生成、保存的区块不需要玩家亲自探索；未生成的区块保持空白。

## 当前状态 / Status

**开发测试版，仅 Minecraft 1.21.1 NeoForge 适配器已实现并完成编译、服务端启动测试；尚无 Minecraft 客户端实机验收。** 不要当作已经验收完成的正式发布版。

| Feature / 功能 | Development status |
|---|---|
| 地图册合成：空地图 + 皮革 + 羽毛 | 已实现 |
| 普通工作台用地图册 + 空地图扩页 | 已实现 |
| 每张 128×128 / 只读已生成 FULL 区块 | 已实现 |
| 未装墨水不绘图，首绘扣费、后续免费刷新 | 已实现，需实机回归 |
| 黑白、三原色补色、独立资源与指南针 | 已实现，需实机回归 |
| 连续大地图平移/缩放及手持小地图 | 已编译，需客户端验收 |
| 地图册界面：通用耗材选择入口/指南针入口 | 已编译，需客户端验收 |
| 自定义标点、图标/颜色/名称、发光/编辑/删除 | 已编译，需客户端验收 |
| 从已测绘页拓印原版地图，消耗 1 空地图 | 已编译，需客户端验收 |
| 地表真实道路、原版铁轨、Create 轨道方块 | 已实现基础识别；Create 曲线需后续验收 |
| 自动标记经地表建筑方块核验的结构 | 已实现部分原版地表结构；非全覆盖 |
| 三个资源 0–10× 消耗倍率，服务端管理员保存 | 已编译，需实机验收 |
| Fabric、其它 Minecraft 版本 | 计划中，未编译、未发布 |

## 快速使用（1.21.1 NeoForge 开发版本）

1. 用原版 **空地图 + 皮革 + 羽毛** 无序合成地图册。
2. 主手拿地图册右键，或地图册在背包时按 **M**，打开 Cartograph。
3. 大地图完整铺满窗口，没有固定侧栏及底部边框。点击顶部「图册」打开资源悬浮窗，从通用耗材入口投入墨囊、发光墨囊或三原色；指南针有独立非耗材入口。必须先投入墨囊才能测绘。
4. 在原版工作台用 **地图册 + 空地图** 扩充页数。每页固定原版 0 级地图大小（128×128 方块）。
5. 大地图左键拖动平移、滚轮缩放。右键地表已测绘区域创建标点或**取出地图**（需要另消耗一张空地图）；右键标点进行编辑/删除。装了指南针才显示自己的位置。
6. 顶部「标点」显示已加载地图页中的标点（点击定位、点击编辑），「图层」切换地表结构、道路、铁路和自定义标点显示，「信息」独立弹出数据说明，「居中」回到玩家当前位置。「设置」是独立弹窗，管理员/单人世界主可调整三个资源的 0–10× 消耗倍率（步长 0.1）及各材料填充量；其他玩家只读。
7. 彩色需要红、黄、蓝各一份染料。**后续变动自动更新，不再重复扣材料**。未生成/地下地形不提前绘制。

临时兼容交互：主手地图册 + 副手耗材右键同样能补充；潜行+空副手右键可以取回指南针。图册 UI 为主要入口。

### 最新的实机反馈修复

**大地图界面不一致、卡顿/操作无确认、旧区块/新区块不显示**已进行针对性修复和真实存档读取检查。界面不再被 Minecraft GUI Scale 重复放大；操作有服务端结果提示；测绘支持 8/32/64 区块进度显示、服务端每 tick 两个区块的分批扫描以及已保存 FULL 地表缓存。完整验收详情和明确的地图容量/墨水限制见 [docs/CLIENT_UI_SURVEY_FIX.md](docs/CLIENT_UI_SURVEY_FIX.md)。

### 大地图界面高保真参考

设计原型：[preview/cartograph-fullscreen-hifi.html](preview/cartograph-fullscreen-hifi.html)，可直接用浏览器打开。Minecraft UI 已按原型重做为地图满屏、顶部 7 个悬浮工具、多个独立浮窗和居中弹窗；Java 原生字体及物品图标会与网页不同，客户端视觉和操作尚需实机验收。

## Build / 验证

需要 Java 21。构建会使用官方 NeoForge ModDevGradle 的 1.21.1 工程。

```bash
bash scripts/verify-common.sh
cd java/adapters/1.21.1-neoforge
./gradlew --no-daemon check build
```

产物：`java/adapters/1.21.1-neoforge/build/libs/Cartograph-0.1.0-mc1.21.1-neoforge.jar`

服务器运行所需文件必须是相同版本，客户端和服务器双方都需安装 Cartograph。不要把这个 JAR 当作 Fabric 模组。

## 多版本 CI / 发布

**下载开发测试版**：[GitHub Releases · development](https://github.com/wanstu/minecraft-cartograph/releases/tag/development)。
每次 `main` 推送通过 CI 后，自动更新这个明确标记为 **Prerelease（开发预览）** 的附件 JAR 和 `SHA256SUMS.txt`。请在正式使用前备份世界。也可以从 [GitHub Actions](https://github.com/wanstu/minecraft-cartograph/actions/workflows/release.yml) 下载构建产物。

- `java/common`（Java 17）保存地图坐标/实际 MCA 数据读取/免费刷新账本等公共逻辑。
- `java/adapters/<minecraft>-<loader>` 每个版本独立编译为对应单 JAR，避免虚假的跨版本兼容。
- `config/targets.json` 的 `status=implemented` 才加入 GitHub Actions 构建矩阵。目前唯一已实现目标是 **1.21.1 NeoForge**；其余八个是 `planned`。
- `.github/workflows/release.yml`：main 推送及 PR 自动进行公共测试、构建、JAR 结构校验并上传 Action artifact；tag `v<version>` 可在正式放行后形成 Release JAR 和 SHA256SUMS。
- **`release_ready=false`**：在客户端验收、存档复测完成前，禁止创建 **正式稳定版**；开发预览版 `development` 独立标记为 Prerelease，仍可供测试下载。

详细说明：[真实测绘验收与限制](docs/SURVEY_PIPELINE.md) · [正式机制契约](docs/MECHANICS_V1.md) · [多版本设计](docs/MULTI_VERSION.md) · [发布与 CI 操作](docs/GITHUB_RELEASE.md)。

## 尚未实现/验收

- Create 曲线轨道连线的精细还原、更多自然地表结构；不支持地下道路/铁路。
- 客户端 GUI 实际交互、双人联机、复制物品余额防重复计费、异常关服/恢复后的计费一致性实测尚未全部验收。
- 额外 Fabric/NeoForge 版本适配，以及 Modrinth/CurseForge 发布。

Cartograph 的代码为独立实现；[Map Atlases](https://github.com/Pepperoni-Jabroni/MapAtlases) 仅作交互参考，不复制其 GPL-3.0 源码。

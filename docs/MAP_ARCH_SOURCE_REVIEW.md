# Cartograph：开源地图源码对照与架构重审

2026-11-09 · dadm · 研究记录，仅审阅，不修改游戏功能或发布 JAR。

## 实际阅读的源码与许可

1. **FTB Chunks** `FTBTeam/FTB-Chunks`，审阅 commit `83bac199cc462c8e0cce8f8af9049c451fd958d9`，**All Rights Reserved（源码可查看但不可复制）**。
   - [MapManager.java](https://github.com/FTBTeam/FTB-Chunks/blob/83bac199cc462c8e0cce8f8af9049c451fd958d9/common/src/main/java/dev/ftb/mods/ftbchunks/client/map/MapManager.java)
   - [ChunkUpdateTask.java](https://github.com/FTBTeam/FTB-Chunks/blob/83bac199cc462c8e0cce8f8af9049c451fd958d9/common/src/main/java/dev/ftb/mods/ftbchunks/client/map/ChunkUpdateTask.java)
   - [MapRegion.java](https://github.com/FTBTeam/FTB-Chunks/blob/83bac199cc462c8e0cce8f8af9049c451fd958d9/common/src/main/java/dev/ftb/mods/ftbchunks/client/map/MapRegion.java)
   - [MapRegionTexture.java](https://github.com/FTBTeam/FTB-Chunks/blob/83bac199cc462c8e0cce8f8af9049c451fd958d9/common/src/main/java/dev/ftb/mods/ftbchunks/client/map/MapRegionTexture.java)
   - [RenderMapImageTask.java](https://github.com/FTBTeam/FTB-Chunks/blob/83bac199cc462c8e0cce8f8af9049c451fd958d9/common/src/main/java/dev/ftb/mods/ftbchunks/client/map/RenderMapImageTask.java)
   - [RegionMapPanel.java](https://github.com/FTBTeam/FTB-Chunks/blob/83bac199cc462c8e0cce8f8af9049c451fd958d9/common/src/main/java/dev/ftb/mods/ftbchunks/client/gui/map/RegionMapPanel.java)
   - **事实**：为 512×512 地图区建立独立的区域数据对象、位置/地表高度/生物群系/水与植被颜色数据；异步加载并制作 NativeImage，主线程只上传/替换 DynamicTexture；触碰视野中的纹理更新访问时间，长时间不用的区域释放内存；持久化任务有背压。大地图用区域纹理拼接、独立平移缩放，地图图标是另一层。版本是较新 Minecraft 版本，不能直接用于 1.21.1。

2. **BlueMap** `BlueMap-Minecraft/BlueMap`，审阅 commit `7d80c0f04beee8d3d1b9fa5543c7d65ffa1515ca`，**MIT**。
   - [MapUpdateService.java](https://github.com/BlueMap-Minecraft/BlueMap/blob/7d80c0f04beee8d3d1b9fa5543c7d65ffa1515ca/common/src/main/java/de/bluecolored/bluemap/common/plugin/MapUpdateService.java)
   - [WorldRegionUpdateTask.java](https://github.com/BlueMap-Minecraft/BlueMap/blob/7d80c0f04beee8d3d1b9fa5543c7d65ffa1515ca/common/src/main/java/de/bluecolored/bluemap/common/rendermanager/WorldRegionUpdateTask.java)
   - [RenderManager.java](https://github.com/BlueMap-Minecraft/BlueMap/blob/7d80c0f04beee8d3d1b9fa5543c7d65ffa1515ca/common/src/main/java/de/bluecolored/bluemap/common/rendermanager/RenderManager.java)
   - **事实**：后台监听区域文件变化，配置冷却去抖，增量比较区块时间戳/图块状态，仅重绘变化的图块；独立渲染线程与可取消、可排序任务；持久化更新状态。渲染的是服务端世界本身，可覆盖玩家未到过但真实已生成的区域。它的 3D 网页渲染不适合直接移植到轻量客户端地图，只参考数据层、增量机制和异步任务。

3. **Map Atlases** `Pepperoni-Jabroni/MapAtlases`，审阅 commit `cf41569acf23b7951c6ef02d44988a681786e853`，**GPL-3.0**。
   - [MapAtlasesAtlasOverviewScreen.java](https://github.com/Pepperoni-Jabroni/MapAtlases/blob/cf41569acf23b7951c6ef02d44988a681786e853/src/main/java/pepjebs/mapatlases/screen/MapAtlasesAtlasOverviewScreen.java)
   - [MapAtlasItem.java](https://github.com/Pepperoni-Jabroni/MapAtlases/blob/cf41569acf23b7951c6ef02d44988a681786e853/src/main/java/pepjebs/mapatlases/item/MapAtlasItem.java)
   - **事实**：把原版 128×128 地图作为页面，利用 Minecraft 自带 MapRenderer 绘制拼接视图，缩放、拖拽和坐标映射独立处理；消费空地图扩张。适合借鉴地图册物品/翻页交互，不适合解决“未去过但已生成的完整地表”，因为本质是 Vanilla map 已有数据。不可复制 GPL 代码到不兼容许可项目。

## 对 Cartograph 当前实现的实际诊断

工程：`/home/wanstu/projects/minecraft-cartograph`，已发布测试提交 `f77d325`。

| 症状 | 已找到的实现问题 | 正确方向 |
|---|---|---|
| 大地图只见中心约 170～200 像素的彩色斑块 | `AtlasScreen` 单页仅 128×128 方块；初始只有一页，缩放按物理像素算时页面必然很小。满屏画布**不等于有世界数据填满全屏**。其他已经生成但尚未扩页的区块被 `Entry.isCovered` 主动排除。 | 初始视野应自动按已购买页范围适配，明确提示页数和测绘覆盖。全屏地图空白的业务限制和实际无数据分开显示；不能私自用 Seed 绘制。是否取消每页限制须先确认玩法，不偷偷更改。 |
| 打开/平移/缩放时一阵阵卡顿 | `AtlasMapServer.tick` 仍在服务器主线程读保存区块 NBT 和采样；`CartographClient.acceptPage` 收到进度包就重新生成整页 `NativeImage` 并注册纹理；定时刷新再次扫描中心页。 | 已存档数据读取在专用 I/O 线程，已加载区块数据只在合规主线程短时取安全快照；采样/合成在限额后台工作器；客户端只在主线程上传已完成的脏矩形/纹理；变更合并和任务优先级控制。 |
| 一页新/旧区块未绘制、无法知道为什么 | 当前仅扫描“用户购买页”，没有真正独立、持久化的世界生成区域索引；完整生成、地图册购买、首次投墨和已扣费，混在同一页面请求结果里。仅靠当前视野重新请求才能获取结果。 | 世界生成索引独立于账本；每个 chunk 记录 `generated / indexed / paid / ink-unlocked / style-rendered / dirty-revision` 状态及真实数据来源。客户端可以明确报错、排队进度和首次与后续增量更新。 |
| 彩色似乱码，黑白一堆随机斑块，缺乏真实地理形态 | 当前主体像素只储存 1 byte 原版 `MapColor` 和 1 byte 特征类别；黑白模式只是亮度阈值+局部多数滤波，失去水系边界、地形高差、坡面方向和植被类型语义。 | 不再拿最终颜色作为基础事实。按真实地表 block、Biome、地形高度、流体面、结构/路线保存语义化的 `SurfaceSnapshot`；不同风格在客户端渲染同一份事实。 |
| 文字太小、原型不一致，左上角悬浮窗无意义 | 当前 `chromeScale=1.55/guiScale` 在 GUI scale 较大时使 MC 自带约 9px 字形最终仅约 14 物理像素甚至更难辨认；左上角 `Cartograph 地图册` 为纯身份装饰，不承担功能。 | 严格参照 HTML 浮窗层级和交互、提升文字物理高度与按钮尺寸，取消左上角身份装饰；让主要地图坐标、状态在需要时出现而非常驻。按 1686×895、GUI 2/3/4 真机截图逐项比对。 |
| 选择“写实/手绘”不具备可靠实现基础 | 原有数据层只有颜色，难独立得到等高线、坡度和水系/林地边界。 | 先建立数据中立的 `SurfaceSnapshot`，然后分别接 `RealisticRenderer` 和 `IllustratedRenderer`（不要改变已付费测绘范围或重新扣费）。 |

## 建议的新分层（明确重新设计，不继续往 AtlasScreen 堆代码）

```text
真实存档世界（从不依赖 seed 推断，也不触发新区块生成）
        │
        ├─ LoadedChunkSnapshot：在 Minecraft 主线程获取已加载完整区块的最小只读快照
        └─ RegionFileReader：后台读取已有 .mca 中 FULL 数据（含文件版本戳）
        │
   WorldSurfaceIndex：维度/区块坐标 -> 已生成 + 真实地表快照 + revision
        │
   SurveyLedger：该地图册已购买页、首次装墨与扣费（只影响能看到的范围）
        │
   DirtyScheduler：优先级、同一页去重、取消旧视野、增量更新与状态回报
        │
   Sync：按区块/图块传变更（带 revision），避免每步发送整页双数组
        │
   ClientTileCache：后台构图、LRU、按版本更新、GPU 仅主线程限量上传
        │
        ├── RealisticRenderer：自然真彩（高度阴影、自然地表色、水系、道路/铁轨）
        └── IllustratedRenderer：纸色、墨线水系/路网、地貌符号、适度等高线
        │
   MapCanvas：视口裁剪、地图拖动/缩放/LOD，动画帧与地图扫描解耦
        └── UIOverlay：原型浮窗/弹窗/标点/通知/字号；与地图数据生产完全解耦
```

原则：
- **不改材料经济**：墨囊首次开启测绘；三原色开启彩色；发光墨水与指南针既有规则；世界后续变更无额外扣材料；写实/手绘只是风格，不收材料、不改变世界信息。
- **数据范围限制明确**：真实已生成 FULL 才索引；允许读取玩家没踏足但已存档的 FULL；地下结构、未生成区块不推测；未购买页原则仍有效，显示清楚，不用假地图填满。
- **单线程读取游戏对象与后台磁盘 I/O 分离**：绝不在异步线程直接访问非线程安全 `ServerLevel/LevelChunk`；完整 NBT 文件可由后台读取后形成副本。客户端纹理创建/上传仍在渲染线程按预算进行。
- **增量与脏标识**：渲染帧不能触发地图扫描；扫描不能阻塞 GUI 帧；世界变更只标记旧已测绘区块为 dirty，重复计算不额外扣材料；更新旧区块不需要再次投入耗材。
- **缓存/内存**：近期可见优先、屏幕外任务可取消、LRU 按预算释放；快照与渲染贴图版本关联；使用变更批处理和抑制重复上传。
- **写实风格**：以真实高度、顶层物方块的颜色和真实生物群系为基底，陆水边界清晰，坡度阴影适度，路网单独图层；远景 LOD。
- **手绘风格**：将同一份数据转成羊皮纸、墨线等高线、海岸线/河流、水纹、森林/山脉抽象符号（仅真实地表可见内容）；严禁靠 Seed 猜地下和未生成结构。
- **版权**：只借鉴分层与算法思想。FTB Chunks 源码 All Rights Reserved；Map Atlases GPL-3.0；BlueMap MIT。任何实际复制 BlueMap 文件或片段都必须满足 MIT 署名与许可保留；最好所有 Cartograph 代码继续独立编写。

## 验收顺序与量化基线（目标，不是假称已达到）

1. **先保证读取正确**：找一份已有若干 region 的真实世界样本，输出 `FULL总数 / 允许显示 / 已扣首次墨 / 已呈现`，按块交叉检查 .mca 和内存中的数据；空白原因可核对。单人/联机、多维度分别做。
2. **再测性能**：记录按住拖动 30 秒的 frame time、服务端 tick 时间、内存与 GPU 上传次数；目标是静止时没有周期性全页重算，拖动时地图优先移动已有纹理（首次加载异步补细节）。
3. **再测风格**：相同的世界区块分别用写实、手绘出图；切风格不扣资源；比较森林、河流、山地、海岸、村庄、道路和真实铁轨；未生成/地下不自动出现。
4. **最后 UI**：对照 `preview/cartograph-fullscreen-hifi.html` 的实际布局、字号、弹层、鼠标位置与各按钮结果；删除左上角纯装饰图牌；截图和操作录屏作验收证据。
5. **发布门槛**：数据正确 + 无频繁主线程卡顿 + 两风格对比 + 原型 UI 实机截图通过，再推送开发预览包。构建成功不代表验收成功。

## 补充：VoxelMap / XaeroPlus / JourneyMap

后续又在 dadm 实际下载并检查了 VoxelMap Updated、XaeroPlus 和 JourneyMap Webmap / Web 客户端的公开源码；完整源码路径、commit、性能与缓存对照参见 [MAP_POPULAR_MOD_SOURCE_REVIEW.md](MAP_POPULAR_MOD_SOURCE_REVIEW.md)。公开项目的许可不同：VoxelMap Updated/主 JourneyMap/Xaero 正式版不能直接复制代码。

## 第一阶段实现进度

已经加入 WorldSurfaceIndex：服务器线程读取已加载区块，独立线程读取真实存档中的 FULL 区块。完整验证记录见 [WORLD_SURFACE_INDEX_IMPLEMENTATION.md](WORLD_SURFACE_INDEX_IMPLEMENTATION.md)。增量瓦片调度、两套地图风格和客户端实机视觉验收仍未完成。

## 本轮结论

**应停止在旧的 `AtlasScreen`、`AtlasMapServer` 中叠加局部修补；新建可测试的数据、调度、纹理与 UI 分层。** 首先修世界快照和增量任务机制，再做风格与界面。本轮只审阅了三个真实仓库源码，Cartograph 当前功能代码未改动，也未重新发布。

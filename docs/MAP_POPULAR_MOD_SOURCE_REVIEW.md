# 热门 Minecraft 地图模组源码研究：VoxelMap / Xaero / JourneyMap

日期：2026-11-09；项目：Cartograph（NeoForge 1.21.1）；机器：dadm。

本轮是**真实源码对照**，不是基于截图推测，也不复制他人受限代码。源码均下载到 dadm 临时目录 `/tmp/cartograph-source-review/`，不混入 Cartograph 发布工程。

## 1. 查看对象、源码可用性

| 模组 | 实际检查的仓库与 commit | 能看到的源码 | 许可与限制 |
| --- | --- | --- | --- |
| VoxelMap（原作者历史版） | [MamiyaOtaru/voxelmap](https://github.com/MamiyaOtaru/voxelmap) `32fe46c` | 1.4.7 时代 Minimap 与旧地图数据、缓存 | 历史源码，不代表现代版本；仓库未提供可直接用来复制的新版权限声明 |
| VoxelMap Updated（第三方延续版） | [Updated-VoxelMap/VoxelMap](https://github.com/Updated-VoxelMap/VoxelMap) `03dd60b` | `PersistentMap`、`CachedRegion`、`ThreadManager`、`CompressibleMapRegionTexture` 等现代实现 | Modrinth/原项目为 All Rights Reserved，公开代码≠自由移植；仅研究其分层与策略 |
| Xaero's World Map / Minimap（原作者正式版） | [CurseForge World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map) | **未发现可核验的原作者完整开源实现**，不能声称已读其核心源码 | All Rights Reserved |
| XaeroPlus（第三方扩展，不等于 Xaero 核心） | [rfresh2/XaeroPlus](https://github.com/rfresh2/XaeroPlus) `693f03a` | `ChunkHighlightCache`、`ChunkHighlightBaseCacheHandler`、`DrawManager`、`MixinMapTileChunk` 等 | MIT；可参考公开接口与可验证的缓存策略 |
| JourneyMap（旅行地图正式核心） | [TeamJM/journeymap](https://github.com/TeamJM/journeymap) | **官方 GitHub 仅问题跟踪仓库，核心未公开** | All Rights Reserved |
| JourneyMap Webmap 插件 | [TeamJM/journeymap-webmap](https://github.com/TeamJM/journeymap-webmap) `e8a73a3` | `routes/Tiles.java`、`WebMap.java`，包含对主模组内部 `RegionImageHandler` 的调用 | 该插件 LICENSE 仍是 All Rights Reserved，不复制 |
| JourneyMap Web 客户端 | [TeamJM/webmap-client](https://github.com/TeamJM/webmap-client) `ed6a87a` | `tileRegistry.js`、`tile.js`、`journeymap.js`，Leaflet 底图与标记层 | MIT（使用源代码时需保留许可/署名） |

还研究了前一轮 [FTB Chunks / BlueMap / Map Atlases](MAP_ARCH_SOURCE_REVIEW.md)；建议合并这些结论而不是从单一项目照搬。

## 2. 实际代码中的关键实现

### VoxelMap Updated（本轮最值得借鉴的实时游戏内地图）

已阅读：

- `common/.../persistent/ThreadManager.java`：计算任务与保存任务使用**两个线程池**，计算线程上限是 `min(max(cpu/2, 1), 4)`，切换大地图视野时可以取消过时任务队列。**不能照抄其无限 LinkedBlockingQueue：Cartograph 应使用有上限、可去重和有背压的队列。**
- `CachedRegion.java`：一张可缓存的 region 是 **16×16 个区块，即 256×256 方块**。每块有 dirty/changed 标记；`refresh` 在数据未变化时不重复计算，后台生成图片；正在变更的区块优先处理。
- `PersistentMap.java`：`getRegions(left,right,top,bottom)` 按**当前可见窗口**取得区域，按距离排序；窗口未变化直接复用缓存。地图移出屏幕会修剪缓存，按访问时间与距离淘汰。远处无关区域不该每次缩放都请求。
- `CompressibleMapRegionTexture.java`：GPU 贴图仅在渲染线程更新；按缩放程度选择纹理过滤，生成 Mipmap；已闲置的 CPU 像素可以压缩或释放。**移动现有地图时只变更视口坐标，绝不等待 NBT 采样。**
- `loadAnvilData`：新版代码还区分当前已加载区块、缓存区块和本地 Anvil 保存数据；客户端读取本地存档与远程联机权限不相同。我们不得拿它当成“多人游戏能扫描服务器所有已生成区块”的证明；Cartograph 的服务端按权限读取已有 FULL 区块更符合既定需求。

结论：要真正流畅，地图必须是**长期存在的区域对象 + 可见区域优先调度 + 渐进绘制 + 缓存持久化**，不是打开界面就对每个 page 重新走一遍 64 块采样与 GPU 贴图重建。

### JourneyMap（旅行地图）Webmap

已阅读：

- [Tiles.java](https://github.com/TeamJM/journeymap-webmap/blob/e8a73a34c7fa4a8a805a3e439f0f269a01658ea1/common/src/main/java/journeymap_webmap/routes/Tiles.java)：接收瓦片坐标、缩放、维度、地图类型；根据缩放计算地图边界，然后调用 JourneyMap 核心的 `RegionImageHandler.getMergedChunks`，输出 **512×512 PNG** 瓦片。主模组内部 `RegionImageHandler` 并不在开源插件中，不能推断其线程调度细节。
- [tileRegistry.js](https://github.com/TeamJM/webmap-client/blob/ed6a87a0167c1a8c66ee392fe70a2c6ec9e638a0/src/main/js/tileRegistry.js)：基于当前可见瓦片进行注册和显示更新，使用 IntersectionObserver 区分可见/不可见；基于 Leaflet 的瓦片分层。
- [journeymap.js](https://github.com/TeamJM/webmap-client/blob/ed6a87a0167c1a8c66ee392fe70a2c6ec9e638a0/src/main/js/journeymap.js)：位置、维度、标记、地图模式与地图数据加载相互独立。切换显示模式无需重新扫描世界。
- 注意：Web 客户端也有约 100ms 的轮询更新逻辑，那是特定 Web 应用实现，不应该照抄到 Minecraft 的地图 GUI；Cartograph 应改成 **revision/event 驱动**。

结论：**瓦片坐标体系与地图册的游戏物品页不是同一个概念**。地图画布以 worldX/worldZ 与 zoom 平滑工作；有多少合法可见区块由地图册预算/覆盖范围决定；不能把 128×128 物品页当成 GUI 的显示容器。

### XaeroPlus（用于验证 Xaero 生态外围的缓存设计）

已阅读：

- [ChunkHighlightCache.java](https://github.com/rfresh2/XaeroPlus/blob/693f03aa3dd125152faf531925c51833081e392c/common/src/main/java/xaeroplus/feature/highlights/ChunkHighlightCache.java)：标记按维度缓存；支持异步请求指定地图视窗区域的数据，注明**不要在主线程阻塞等待结果**，主线程缓存不可在其他线程直接修改。
- `ChunkHighlightBaseCacheHandler.java`：修改/移除数据时检查 Minecraft 主线程，避免跨线程直接操作。
- `DrawManager.java`：不同地图附加绘制层可以独立注册并按需使缓存失效。
- 但这只是 Xaero 的扩展，不是 `Xaero's World Map` 原作者的底图扫描器源码；**不能从中反推完整 Xaero 底图实现、渲染速度或许可**。

结论：自定义标点、自然结构、道路、铁轨是独立 Overlay，界面可以切换显示层，不应修改底图纹理像素或触发世界重绘。

## 3. 对 Cartograph 当前三个大问题的直接启示

### A. “旧区块和新区块都没画”

- 服务端应先独立维护 `WorldSurfaceIndex`：`dimension + chunkX + chunkZ` 对应 `FULL / surfaceSnapshot / revision / source(mca|loaded)`。
- 地图册首次耗材扣除和是否购买地图页，由 `SurveyLedger` 单独管理。**WorldSurfaceIndex 不因地图册库存变化而丢掉真实世界数据**。
- 生成的数据仍然要遵守已购买页、首次投入墨囊、0倍消耗等规则；在 UI 明确区分“世界真实存在但未购页”“世界未 FULL”“尚未扫描”“墨水不足”。
- 不使用 seed，不加载或生成未知区块，不显示地下结构和线路。

### B. “刷新一卡一卡”

当前 `AtlasMapServer.tick` 在服务器主线程调用 `SurfaceSampler.surface`，可能读取磁盘 NBT；客户端收到 page 更新重建 `NativeImage` 和纹理。改为：

1. 主线程安全快照**当前已加载的完整区块**；后台线程读取保存的 FULL NBT；两路合并、revision 去重。
2. **有界任务队列**：当前屏幕中心 > 屏幕边缘 > 预取一圈，移动视野丢弃旧的未执行任务。
3. 服务端按 dirty 的 chunk 发送增量数据，不是更新 1 块也传 128×128 全页像素。
4. 客户端后台生成风格化图片；渲染线程仅限额上传 dirty tile；拖拽/缩放只移动已有纹理，允许“旧瓦片先显示、新瓦片随后补上”。
5. 地图数据随存档持久化，重进世界优先读缓存；每次打开地图不应再次全图扫描。

### C. “地图和原型不一样，字太小”

- GUI 使用可读性优先的统一尺寸规范：主文本/按钮/浮窗按物理像素缩放，字号不低于明确的屏幕阅读门槛；去掉左上角无用途标识；说明/坐标不常驻固定底边。
- 地图本身占全部屏幕；右上工具栏只负责唤出浮窗/弹窗。标记名称与底图分层，按钮必须给服务端成功/失败回执。
- 地图风格独立：`RealisticRenderer` 与 `IllustratedRenderer` 使用同一份 `SurfaceSnapshot`，切换风格不能重采样/扣墨。真实地形应保留高度、流体、表面方块等语义；手绘要绘制真实海岸线、坡形、水系、林区轮廓，不能仅将一张噪点图滤成黑白。
- **原版一页覆盖 128×128 方块，并非渲染瓦片固定尺寸**。VoxelMap 256×256 缓存区域、JourneyMap Webmap 的 512×512 输出瓦片都说明：**渲染内部 tile 大小与游戏经济上的纸张页数应彻底分离**。

## 4. 重构次序（下轮开发工作）

1. 先建立与 `AtlasScreen` 无关的 `WorldSurfaceIndex`、`SavedFullChunkScanner`、`LoadedChunkSnapshot`，让已 FULL 的旧/新区块有可靠可测试的来源，并给出每个 chunk 的诊断状态。
2. 再实现 `DirtyTileScheduler`、有界后台 I/O 与主线程快照、可见优先、分批网络、客户端 LRU / 脏贴图缓存；通过移动 30 秒的性能测试，证实地图拖动不触发地表扫描。
3. 再实现**写实 / 手绘**两套纯渲染器（统一输入）和缩放层级，不用地图册资源作为渲染缓存 key。
4. 最后严格实现原型 UI，重点验收 1686×895 窗口、GUI Scale 2/3/4 的字体可读性和操作提示。
5. 实机验收通过前，不因为 CI 能编译就宣布“已修复”或发行正式版。

**结论**：VoxelMap Updated 的“后台区域处理/缓存”，JourneyMap Webmap 的“瓦片层与多级缩放”，XaeroPlus 的“视窗缓存与标注 Overlay”，应合成一个我们独立编写的数据与渲染架构。阅读公开源码不授予复制 ARR 项目代码的权利。

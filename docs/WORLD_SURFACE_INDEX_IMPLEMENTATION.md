# Cartograph：独立世界地表索引——第一阶段实装记录

日期：2026-11-09；开发机器：dadm；目标：NeoForge 1.21.1。

## 目标与已落地实现

本阶段从地图页扫描、原版地图拓印中剥离旧存档的磁盘读取，增加 WorldSurfaceIndex。它负责「真实存在的完整世界地表事实」，不负责地图册购买页、余额、首次材料扣除。游戏规则仍由 AtlasWorldData 与 SurveyLedger 单独决定。

SurfaceSampler 现在有两条路径：

- loaded：主服务器线程仅对 getChunkNow 已经返回的 LevelChunk 采样，不触发新的区块加载或世界生成。
- saved：主服务器线程只捕获真实区块坐标、存档区域目录、维度高度；后台工作线程读取保存的 .mca、解压 NBT、检查 FULL 状态与坐标并提取地表。没有 FULL 数据绝不借助 Seed 推测。

WorldSurfaceIndex 有 2 个磁盘工作线程、有界排队 128、8192 块 LRU，已经调度的相同区块去重；服务器每 tick 只收取最多 64 项异步结果。不同数据源的缓存使用不同更新时间：已加载区块约 3 秒、已保存区块约 10 秒、尚未生成区块约 8 秒。

已加载的真实世界数据优先于旧存档；后台完成结果不能覆盖更新版本的已加载区块快照。结束服务器时会销毁工作线程、完成队列及缓存。

地图册扫描现在遇到仍在读取的区块会等待，不会提前计入已扫描或收取材料；拓印原版地图也共享这一索引，等真正完成后才扣 1 张空地图。原有 128×128 方块的地图册扩容规则不变。

## 真存档对照

构建隔离测试世界 cartograph_index_async_smoke，只合并 dadm 两个开发测试世界中已存在的 .mca 数据，没有修改正式用户存档。来自 async-index-integration-2.log 的实际服务端结果：

- (0,1)、(1,2)：READY，source=LOADED。
- (14,3)、(15,3)、(18,6)：READY，source=SAVED。
- (30,30)：NOT_GENERATED。

这验证了磁盘真实 FULL 区块、内存区块和未生成区块能正确区分，真实区块不依赖玩家是否踏足、也不依赖 seed。长时间性能/交互验收尚未完成：dadm headless 服务器加载速度出现波动，测试超时中断；不能据此宣称已经消除客户端卡顿。

测试用的临时 Java 探针已清除，开发服务器世界名恢复为 cartograph_hifi_smoke。

## 测试

- python3 scripts/verify-world-index.py：静态回归，拒绝同步区块磁盘读取重新进入 server tick；检查有界队列、后台线程、清理、版本优先和等待状态。
- bash scripts/verify-common.sh：原有地图经济账本与 MCA 读取单元测试。
- NeoForge Gradle check build + JAR 结构验证：本轮收尾检查。

## 仍未完成

1. 当前保存的地表仍主要是原版 MapColor 及道路特征，并非完整高度、生物群系、流体和地表材质快照。写实/手绘风格不能只从这组像素中高质量派生。
2. 客户端仍可能在接收整页数据时重建 GPU 贴图；需要下阶段的增量通信与 DirtyTileScheduler、客户端后台预构图与可视区优先的纹理缓存。
3. 真实世界增量更新已按 TTL 重取数据，但仍需要低成本 dirty 通知/文件元信息版本，避免反复采样所有已加载区块。
4. UI 的文字可读性、左上装饰浮窗和两种风格均未在本阶段完成。
5. 未做客户端 30 秒拖动 FPS/MSPT 验收，因此此版本不因能编译就发布正式版。

路线参考：docs/MAP_ARCH_SOURCE_REVIEW.md 与 docs/MAP_POPULAR_MOD_SOURCE_REVIEW.md。

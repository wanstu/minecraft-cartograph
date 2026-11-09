# Cartograph 多版本工程与发布契约
2026-11-09。参考 GeoWorld 的 **公共核心 + 版本适配 + GitHub Actions 构建矩阵** 设计，代码实现独立，不复制 Map Atlases GPL-3.0 源码。

## 版本矩阵
维护 `config/targets.json`，目标与 GeoWorld 当前 CI 相同：
- Minecraft 26.3 Fabric / NeoForge（Java 25）
- Minecraft 26.2 Fabric / NeoForge（Java 25）
- Minecraft 26.1.2 Fabric / NeoForge（Java 25）
- Minecraft 1.21.1 Fabric / NeoForge（Java 21）
- Minecraft 1.20.1 Fabric（Java 17）

**只有 status=implemented 的目标才参与构建；当前只有 1.21.1 NeoForge 有适配器；其余 8 个尚未实现，不得声称支持，也不得出空 JAR。**

正式 GitHub Release 还有第二道保护：`config/targets.json` 的 `release_ready` 必须在完成实机验收后明确设为 `true`。目前为 `false`；允许 CI 验证开发 JAR，但即使错误创建 tag，也不会把不完整的模组发布为正式版。

## 目录规则
- `java/common`: 不依赖 Minecraft / Loader 的公共逻辑，按 Java 17 编译：地图页/负坐标、已生成区块文件识别、已支付测绘区块账本、更新免扣费、核心测试。
- `java/adapters/<mc>-<loader>`: 独立 Gradle 项目、官方 Loader API、物品、配方、UI、Chunk 读取、保存与资源扣费的事件接入；把 `java/common` 编译进**各自**一个 JAR，不依赖运行时第二个 common JAR。
- `docs/MECHANICS_V1.md`: 权威玩法契约。
- `preview/`: 独立 HTML 演示，无存档或游戏接入。
- `scripts/`: Java17 公共测试、release matrix、JAR 结构检查。
- `.github/workflows/release.yml`: 每个 implemented 适配器独立构建，校验 loader metadata/common 类、上载构建产物；只有 tag 才聚合发布。

## 新增版本的规则
1. 在 `java/adapters/<mc>-<loader>` 基于对应官方 MDK/Fabric 工程建立独立适配器。
2. 复用 `java/common`，不复制计费、MCA 存在性检查、坐标/账本实现。
3. 实现该版本 API 的地图册、存档和区块保存事件；禁用任何可能加载/生成未知区块的查询入口。
4. 验证独立 Gradle `build`、合法 JAR、原版配方与中文名称、区块事实与扣费测试。
5. 只有实际验证通过后把 `config/targets.json` 状态改为 implemented；CI 才自动纳入构建矩阵。
6. 发布的 JAR 名称统一 `Cartograph-<modVersion>-mc<mcVersion>-<loader>.jar`；同 tag 上传单套版本矩阵和 `SHA256SUMS.txt`。
7. **不让一个版本适配器更改公共模型的旧账本语义**。更改持久化结构须版本化迁移与向后兼容测试。

## 当前状态
公共层与单个 NeoForge 适配器正在建立中。**正式可玩地图界面、持续测绘和多版本适配尚未完成。** 不发布未经验证的“支持版本”。

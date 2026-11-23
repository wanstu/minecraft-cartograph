# Cartograph V3.1 地图表现架构

## 目标

Cartograph 的地图表现拆成两个彼此独立的维度：

1. **绘图方式（DrawingMode）**：决定“世界事实如何组织成地图数据”。
2. **绘图风格（VisualStyle）**：决定“同一份地图数据如何被画出来，以及前端 UI 如何呈现”。

两者必须自由组合。切换任何一个维度都只能重建显示层，不得重新扫描世界，不得改变持久化世界事实。

## 绘图方式：固定只有两种

`MapDrawingMode` 是封闭枚举，长期只允许：

- `SATELLITE / satellite / 卫星`
- `HAND_DRAWN / hand_drawn / 手绘`

不得把古式、动漫、水彩、等高线、专题图等塞进 DrawingMode。

### 卫星

链路：

```
SurfaceTile
  -> SatelliteMapData
  -> VisualStyle.renderSatellite(...)
```

`SatelliteMapData` 保留高精度地表事实。当前基础成图继续使用资源包感知的
`SatelliteSurfaceRenderer`，因此可以保留较精确的地表颜色、边界与方块级细节。

### 手绘

链路：

```
SurfaceTile
  -> HandDrawnMapData
  -> VisualStyle.renderHandDrawn(...)
```

`HandDrawnMapData` 是独立的数据结构，而不是“卫星图套滤镜”。

第一版会把每个 16×16 SurfaceTile 聚合成 4×4 语义单元。手绘结构不保存平均后的卫星 RGB，而是先把真实地表归类，再按单元做主类别投票与邻域平滑：

- 水体
- 植被
- 干燥地表
- 浅色中性地表
- 石质地表
- 深色中性地表
- 冷色地表
- 特殊色地表
- 土质地表

每个语义单元同时保留水体占比、水深和稳健地形信息。高度使用中位数表示主体地形，起伏使用四分位范围而不是 min/max，避免单棵树、局部尖峰或少量异常方块把整片区域染成强烈阴影。

水系语义已独立于普通地表颜色：窄河允许以较低水体占比进入水系，水深只在真实水体单元之间平滑，海岸/河岸由水陆阈值跨越生成边缘强度。VisualStyle 只负责把同一份水系语义画成现代、古式或动漫；水面不再叠加陆地起伏阴影。

绘制时再从这些粗粒度类别、水系和高度数据插值得到连续块面，不把卫星纹理噪声重新带回手绘图。

以后可以继续给 HandDrawnMapData 增加森林区域、山体、道路等派生语义，但不能因此新增第三种 DrawingMode。

## 绘图风格：可扩展注册表

`MapVisualStyleRegistry` 是开放注册表。

当前内置：

- `modern / 现代`
- `ancient / 古式`
- `anime / 动漫`

未来可以继续增加水彩、像素、蓝图等，而无需修改 `MapDrawingMode`。

每个完整风格必须提供两个地图入口：

```java
int[] renderSatellite(SatelliteMapData data, int stride);
int[] renderHandDrawn(HandDrawnMapData data, int stride);
```

如果某个新风格只支持其中一种数据结构，不应作为正式完整风格启用，也不允许偷偷 fallback 到其他风格。

风格还负责前端表现能力：

- Palette
- UI 布局
- 图标家族
- 字体
- 控件密度
- 面板质感

因此 DrawingMode 只控制地图数据表达，VisualStyle 才控制视觉语言。

## 当前组合

| 绘图方式 | 现代 | 古式 | 动漫 |
| --- | --- | --- | --- |
| 卫星 | 卫星 · 现代 | 卫星 · 古式 | 卫星 · 动漫 |
| 手绘 | 手绘 · 现代 | 手绘 · 古式 | 手绘 · 动漫 |

游戏内和 WebUI 都读取同一份：

- `drawingMode`
- `visualStyle`

旧的 `frontendTheme` 组合状态已经移除，不做兼容迁移。设置 schema 已升级，旧组合主题不再作为运行时概念存在。

## 渲染总链路

```
真实已生成区块
        |
        v
   SurfaceTile facts
        |
        +--------------------+
        |                    |
        v                    v
SatelliteMapData      HandDrawnMapData
        |                    |
        +---------+----------+
                  |
                  v
       MapVisualStyleProcessor
       ├─ Modern
       ├─ Ancient
       ├─ Anime
       └─ future styles...
                  |
        +---------+----------+
        |                    |
        v                    v
  游戏内原生地图          WebUI
```

`MapPresentationRenderer` 只负责：

1. 读取当前 DrawingMode。
2. 构造对应地图数据结构。
3. 读取当前 VisualStyle。
4. 调用该风格对应的数据结构处理入口。

## 成图缓存

游戏内原生地图区分两类缓存：

- `PersistentTileStore`：权威的 SurfaceTile 世界事实缓存。
- `PersistentRenderedAtlasStore`：可丢弃、可重建的表现层 Atlas 缓存。

成图缓存的 key 至少包含：世界、维度、区域、stride、`renderSignature`。其中 `renderSignature` 已包含绘图方式、绘图风格、资源包相关卫星渲染签名和表现管线版本，因此不同组合不会互相污染。

每个持久化 Atlas 同时保存：

- 完整像素结果。
- 组成该 Atlas 的每个 SurfaceTile fingerprint。

重新进入游戏时，原生地图可以先直接恢复 Atlas 像素并立即显示；随后 SurfaceTile 世界事实异步恢复时，再按 fingerprint 增量校验，只重画真正变化的区块。

切换绘图方式或绘图风格后，原生地图只允许显示当前 `targetSignature` 对应的成图。旧绘图方式/旧风格不得继续作为视觉兜底，也禁止不同 `renderSignature` 的 Atlas 在同一帧混合。已经生成过的目标组合优先从内存或磁盘 Atlas 缓存立即恢复；如果目标组合从未生成过，只允许逐步显示目标组合本身，宁可暂时缺块，也不能显示上一种风格。

磁盘区域存在性使用内存索引，禁止在超大视野下每帧逐区域执行文件系统探测。

## 数据边界

VisualStyle / DrawingMode 永远不得拥有或复制：

- 区块扫描
- Minecraft 世界读取
- 世界身份
- 持久化 SurfaceTile 世界事实
- 后端缓存失效策略
- WebSocket 传输
- 标点持久化
- 结构发现
- 传送语义

主题切换或绘图方式切换只允许失效：

- 游戏内 GPU atlas / 展示纹理
- WebUI region/tile 绘制缓存
- 与表现相关的 ETag/signature

不得触发第二次世界扫描。

## UI 设置

游戏内和 WebUI 都提供两个独立设置：

### 绘图方式

- 卫星
- 手绘

这个集合是固定的。

### 绘图风格

- 现代
- 古式
- 动漫
- future...

这个集合来自 VisualStyleRegistry，可继续增加。

顶部和关于页只显示当前组合，例如：

`手绘 · 古式`

但这个组合只是两个独立设置的显示结果，不再拥有独立的“主题 ID”。

## 动态覆盖层：未来项，不在本轮实现

未来可在手绘地图上增加 `HandDrawnDetailOverlay`，用于让抽象底图体现玩家建设和明显人工变化，但它不是第三种 DrawingMode。

预期来源是**方块模式穷举 + 连续性 / 连通区域识别**，例如：

- 连续草径、道路类方块 -> 道路
- 连续铁轨 -> 铁路
- 石砖 / 石头 / 木头等人工材料的大型连通混合区 -> 建筑或人工区域
- 大片耕地 -> 农田
- 桥梁、码头、人工岸线等明显人工变化

第一阶段如果未来实现，应优先识别“人工区域 / 道路网络”，而不是强行判断“这是一栋住宅/仓库/工厂”。

性能边界：

- 只处理 dirty chunk / dirty region
- 不定时全图重扫
- 小尺度变化可忽略
- Overlay 作为派生层，不写回 SurfaceTile 世界事实

这项能力当前仅预留设计，不进入本轮代码路径。

## 验收

CI 需要验证：

- DrawingMode 只有 satellite / hand_drawn。
- VisualStyleRegistry 当前至少有 modern / ancient / anime。
- 每个内置 VisualStyle 同时实现 satellite / hand-drawn 两个入口。
- SatelliteMapData 与 HandDrawnMapData 是不同数据结构。
- 游戏内和 WebUI 使用相同 drawingMode / visualStyle 设置。
- WebUI 能切换当前 2×3 六种组合。
- 切换表现不触发世界扫描或持久化数据重建。
- 原生 UI 和 WebUI 的视觉样式由 VisualStyle 驱动，而地图数据表达由 DrawingMode 驱动。

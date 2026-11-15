# Cartograph V3 地图风格渲染设计

## 1. 目标

Cartograph V3 的地图事实只有一份，显示风格可以有多种。

- **卫星 / 写实**：尽量反映真实地表方块与资源包颜色。
- **手绘 · 现代**：根据真实地貌和群系形成的“地理区域”进行现代地图式概括。
- **手绘 · 古式**：使用古地图的符号、墨线和纸张语言表达同一份真实地理事实。

手绘模式**不是逐方块重新上色，也不是 biome -> 固定图标的简单映射**。

真正的处理顺序应当是：

```
真实已生成区块
    ↓
Surface Facts
    ↓
地理语义分析（区域 / 水系 / 岭线 / 大小 / 形状）
    ↓
Map Primitives（可复用的地理绘制对象）
    ↓
现代手绘 Renderer / 古式手绘 Renderer
```

风格切换只重新绘制最后一层，不重新扫描世界。

---

## 2. 共享事实层

继续使用现有已经缓存的真实地表事实：

- surface block / material
- biome
- surface height
- local relief
- water depth
- 是否海洋 / 河流 / 湖泊
- 已生成区块边界
- GeoWorld 可提供的附加地理事实（如果存在）

手绘分析不依赖 seed 推测，不处理未生成区块。

### 2.1 事实层与风格层必须分离

缓存至少分成三层：

1. **Surface Fact Cache**
2. **Semantic Geography Cache**
3. **Style Raster Cache**

这样：

- 换资源包：卫星图重新着色；手绘语义不用重新分析。
- 换“现代 / 古式”：只换 renderer。
- 地表变化：只失效对应区域和必要的邻域 halo。

---

## 3. 地理语义分析

### 3.1 先得到区域，而不是先画图标

对连续事实场进行区域化，形成类似：

- forest region
- plains region
- desert region
- snow region
- mountain region
- hill region
- swamp region
- lake region
- ocean region
- river corridor
- beach / coast strip

区域使用 8 邻域连通，并允许对非常小的噪声区域合并到优势邻区。

每个区域保存：

- 面积
- 包围盒
- 质心
- 长宽比 / 细长度
- 平均海拔
- 最大 / 最小海拔
- relief
- slope
- 边界长度
- 与水体 / 海岸的邻接关系
- 主要 biome 构成
- 方向性
- 是否跨越多个地图 tile

### 3.2 大小直接影响画法

例如森林：

- **小树林**：少量树木符号，保留周围空地。
- **中型森林**：树群 + 稀疏底色。
- **大型森林**：连续林地区域，内部只抽样绘制树群，不逐棵树。
- **超大型森林**：低缩放时成为一个带名称的林地区域，高缩放再补树群。

山地：

- **小丘**：单个或 2~3 个低矮 hill glyph。
- **山群**：沿局部高点布置一组山形符号。
- **大山脉**：先提取 ridge，再沿岭线布置山脉 glyph，大小随局部高差变化。
- **孤峰**：使用单独的大型峰体符号。

因此不能写成：

```
if biome == forest:
    drawTree()
```

应当是：

```
ForestRegion -> measure size / density / shape
             -> choose cartographic representation
```

---

## 4. 山脉 / 丘陵

### 4.1 山地判断

综合：

- 绝对海拔
- 邻域 relief
- slope
- 连通面积

而不是仅看 biome 名。

### 4.2 Ridge 提取

对山地区域的高度场做局部最大值与骨架化，得到主要岭线。

输出 primitive：

```
MountainRangePrimitive
- ridge polyline
- local peak heights
- local relief
- width
- importance
```

### 4.3 绘制

现代手绘：

- 简洁山形线稿
- 很弱的单侧阴影
- 大山脉沿 ridge 连续排列
- 次级小山减少密度

古式：

- 更具象的山峰符号
- 深棕 / 墨线
- 山脉沿岭线排布，但允许适度形状概括
- 不要求逐块吻合，位置与总体方向必须忠实于真实地形

---

## 5. 森林

输出：

```
ForestPrimitive
- polygon
- area
- density
- dominant forest type
- sample anchors
```

树符号位置必须是**确定性的**：

```
hash(worldId, regionId, anchorIndex)
```

不能每帧随机，否则拖动 / 重开地图时树会跳动。

现代手绘：

- 绿色浅底
- 单色树群线稿
- 密度随区域面积降低，避免大森林铺满重复图标

古式：

- 无或很淡的底色
- 墨线树木
- 大森林采用组团式符号
- 可在区域中心出现“XX林”等地理名称

---

## 6. 水系

### 6.1 水体分类

通过连通水体 + 形状判断：

- ocean / sea
- large lake
- small lake
- wide river
- river
- tributary
- pond

不能只依赖 biome，因为实际河道与湖泊形状比 biome 标签重要。

### 6.2 河流

对狭长水域提取中心线并估计平均宽度：

```
RiverPrimitive
- centerline
- width samples
- hierarchy
- mouth / source / confluence
```

分级：

- 大河
- 普通河流
- 小河 / 支流

现代手绘：

- 平滑蓝色带状曲线
- 河宽保留等级关系
- 岸线简化但不改变流向

古式：

- 墨蓝 / 灰蓝细线
- 大河使用双线或有宽度笔触
- 支流用更细线
- 河口、汇流关系必须真实

### 6.3 湖泊

湖泊使用简化后的真实轮廓，不改成固定圆形。

小湖泊可以更强概括，大湖泊必须保留辨识度。

---

## 7. 海岸

海岸线来自真实陆海边界。

先做多尺度 simplification：

- 高缩放：接近真实海岸
- 中缩放：去除像素锯齿与极小凹凸
- 低缩放：保留半岛、海湾、岛屿等主要轮廓

古式可增加：

- 双海岸线
- 海岸短线 / stipple
- 浅海装饰

这些只能作为视觉层，不能改变实际海岸位置。

---

## 8. 手绘 · 现代

关键词：

- 清晰
- 简洁
- 地理信息优先
- 少装饰
- 平滑区域
- 一致的线宽

建议视觉：

- 米灰地图底
- 森林浅绿
- 草原浅黄绿
- 沙漠米黄
- 水体低饱和蓝
- 山脉灰绿线稿
- 单色结构图标
- 无重描边

低缩放强调：

- 海陆轮廓
- 大河
- 大湖
- 大山脉
- 大森林
- 主要结构

高缩放再出现：

- 小河
- 小丘
- 小树林
- 次级结构

---

## 9. 手绘 · 古式

不是简单给现代手绘加 sepia filter。

需要独立 renderer / symbol library。

关键词：

- 羊皮纸
- 棕墨
- 手工线条
- 山水符号
- 地图概括
- 低信息噪音

建议视觉：

- 羊皮纸底纹
- 深棕海岸
- 蓝灰河流
- 山脉 pictogram
- 树林 pictogram
- 结构使用古地图式单色符号
- 字体 / 标签布局更像古地图

古式可以比现代手绘更“概括”，但必须遵守：

> 允许概括真实形状，不允许发明不存在的主要地貌。

---

## 10. 多级 LOD

手绘模式必须有自己的 LOD，不应把所有细节缩小后硬塞进屏幕。

建议：

### 6.25% ~ 12.5%
只显示：

- 大陆 / 海岸
- 主要海域
- 大河
- 超大湖泊
- 主要山脉
- 大型森林
- 极少量重要结构

### 25% ~ 50%
增加：

- 普通河流
- 山群
- 中型森林
- 湖泊
- 主要结构

### 100%+
增加：

- 支流
- 小丘
- 小树林
- 小湖
- 详细地理标签
- 全部结构 / 自定义标点

地貌 primitive 本身有 importance，renderer 根据 zoom 决定显示，而不是简单 alpha fade。

---

## 11. 推荐代码结构

```
SurfaceTile
    ↓
SemanticTerrainAnalyzer
    ↓
SemanticMapRegion
    ├── MountainRangePrimitive
    ├── HillClusterPrimitive
    ├── ForestPrimitive
    ├── RiverPrimitive
    ├── LakePrimitive
    ├── CoastPrimitive
    └── RegionFillPrimitive
             ↓
    ┌────────────────────┬─────────────────────┐
ModernHandDrawnRenderer  AncientHandDrawnRenderer
             ↓                    ↓
        DynamicTexture / cached raster tiles
```

卫星图继续走现有：

```
SurfaceTile -> SatelliteSurfaceRenderer
```

三个 renderer 最终统一输出同一套地图 region texture 接口。

---

## 12. 缓存与失效

Semantic cache key：

```
worldId
dimension
factRevision
semanticVersion
regionKey
```

Style raster key：

```
semanticRevision
styleId
styleVersion
zoomLod
regionKey
```

卫星额外带：

```
resourcePackSignature
```

手绘不应依赖资源包。

区块变化时：

1. 更新 Surface Facts。
2. 标记所在 semantic region + 邻接 halo。
3. 局部重新连通 / 重新提取。
4. 只失效受影响的手绘 raster region。

---

## 13. 与标点 / 结构图层的关系

自动结构和自定义标点**永远属于独立 overlay**。

地貌 renderer 不把结构烘焙进底图。

因此：

- 可单独关闭结构层。
- 可编辑自动结构图标。
- 风格切换时标点位置不变。
- 现代 / 古式可以选择不同的结构图标外观，但使用同一个 marker 数据。

---

## 14. 第一阶段建议

不要一次实现完整古地图。

先实现 Modern Hand Drawn v1：

1. 海陆 / 海岸区域简化。
2. 水体分类：海 / 湖 / 大河 / 河 / 支流。
3. 高度 + relief 分类：平原 / 丘陵 / 山地 / 山脉。
4. Forest connected region + size-aware tree groups。
5. 固定 LOD。
6. Modern renderer。
7. 与卫星模式一键切换、共享缓存事实。

Modern v1 成功后，再复用同一 SemanticMapPrimitive 层实现 Ancient renderer。

这样可以验证“地理抽象”是否正确，而不是先把时间花在古式纹理和装饰上。

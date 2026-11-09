# Cartograph GitHub & CI 交付说明

## 当前结论

- dadm 工程：`/home/wanstu/projects/minecraft-cartograph`。
- 当前 Git `main` 已存在（本地提交后未自动同步 GitHub）。
- **目标 GitHub 仓库**：`wanstu/minecraft-cartograph`，但在 2026-11-09 检查时 GitHub 当前授权未能找到它，远端未配置。
- dadm 目前没有 `gh` CLI、GitHub Token 或 Git 凭据。ChatGPT 的 GitHub 连接器有读写已存在仓库功能，但没有提供“创建仓库”能力。
- 当前代码是开发测试版，可由 GitHub Actions 产出测试 JAR；**尚未完成客户端实机验收，正式 GitHub Release 保护标志维持 false**。

## 首次建立远端（仅需一次）

1. 使用当前 GitHub 账号（`wanstu`）在 [GitHub 新建仓库](https://github.com/new) 建立**空的** `minecraft-cartograph`，**不要预生成 README/.gitignore/LICENSE**，避免与本地 Git 产生不同历史。
2. 在 dadm 上配置当前账户的 GitHub HTTPS 或 SSH 推送身份（不要把 Token 写入 README、仓库或日志；也不要将凭据发给 agent）。
3. 在 dadm 工程目录中执行：

```bash
git remote add origin https://github.com/wanstu/minecraft-cartograph.git
git push -u origin main
```

建议默认分支 `main`。完成后可在 GitHub 的 **Actions → Cartograph build matrix → 最新主分支运行记录 → Artifacts** 下载 `cartograph-mc1.21.1-neoforge` 产物。

## 自动构建流程

- push `main` / `master` 或 PR: `common-tests`（Java17）、`build`（根据 `config/targets.json` 实际 implemented 版本动态矩阵），包含：
  - NeoForge 1.21.1 Java21 `gradlew check build`；
  - JAR 内类、NeoForge metadata、语言、配方、公共核心与大小校验；
  - artifacts 上传，`if-no-files-found:error`；
- 标准 release tag `v0.1.0` 与 `gradle.properties` 的 `mod_version=0.1.0` 必须匹配，完成所有 implemented 构建后才聚合；匹配 SHA256SUMS；`softprops/action-gh-release`。
- 正式发布 job 的首步要求 `config/targets.json.release_ready=true`；代码未实机验收前必须保持 false。不因为源码能编译就声称完成产品。
- 目前 9 个目标中 8 个 `planned`，**不会伪造 8 个 JAR 或把它们放进矩阵**。

## 需要的实机验收

1. Java21 + Minecraft1.21.1 NeoForge21.1.265: 双端安装开发 JAR；创造/生存模式获取图册。
2. 空地图+皮革+羽毛、图册+空地图在工作台分别测试；扩页前后墨水/颜料余额持久化。
3. 新图册不装墨囊必须没有内容。补墨囊以后仅已生成 FULL 的地表测绘，未生成保持空白；检查“玩家从未去过但已经保存”的区块。
4. 红黄蓝颜料升级，已有黑白区块首次上色扣 1×倍率；之后更新不重扣。
5. 发光标点创建扣 1×倍率；编辑名称、颜色、图标不重复扣；删标点、重启存档后标点状态正确。
6. 指南针装卸，未装不显示玩家点；HUD 与 M 键正常。配置三个0～10倍率在服务端仅OP/单人世界主可保存、0不扣但需要首次解锁。
7. 正常/负坐标大地图拖放、跨页、未知区块、真实地表建筑/道路/原版轨道/Create轨道、地下不显示。
8. 取出原版地图应消耗一张空地图，产生带当前实际测绘内容的Filled Map；图册页不减少。
9. 2+人共享地图册、复制、防重复扣费、老存档、服务端重启/意外中断恢复、大范围远程 region 读性能。
10. 完成后才能在受保护合并分支设置 `release_ready=true` 并创建匹配版本号 tag 发布稳定版。

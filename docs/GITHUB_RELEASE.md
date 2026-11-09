# Cartograph GitHub & CI 交付说明

## 当前结论（2026-11-09）

- dadm 开发工程：`/home/wanstu/projects/minecraft-cartograph`。
- **GitHub 公共仓库已创建并成功推送**：[wanstu/minecraft-cartograph](https://github.com/wanstu/minecraft-cartograph)。
- 由 **eadm** 上已经认证的 GitHub CLI 创建远端；由 **dadm** 使用已经验证的 SSH 密钥推送：`git@github.com:wanstu/minecraft-cartograph.git`。后续代码只需在 dadm `git push origin main`，不用再创建仓库或人工提供 Token。
- **第一轮 GitHub Actions 真实构建成功**：[构建 42571229244](https://github.com/wanstu/minecraft-cartograph/actions/runs/42571229244)，包含 common-tests、版本矩阵和 1.21.1 NeoForge JAR 校验。
- 新增 `development-preview` CI job：每次 `main` 自动构建成功后，将已验证的 JAR 和 SHA256SUMS 更新到 [Development 预发布版](https://github.com/wanstu/minecraft-cartograph/releases/tag/development)。这是 **Prerelease 测试版**，与正式版不同。
- **客户端实机验收尚未完成**，正式 `v0.1.0` Release 的 `release_ready=false` 安全保护继续生效，不可为了发布而提前开启。

## 下载与推送

直接打开 [Development 下载页](https://github.com/wanstu/minecraft-cartograph/releases/tag/development)，下载与 Minecraft 版本和 Loader 匹配的 `Cartograph-*-mc1.21.1-neoforge.jar`，将其放到游戏的 `mods/`；客户端和服务器均需要安装。开发版本请先备份世界。

若开发预发布尚未生成，则从 [Actions 构建列表](https://github.com/wanstu/minecraft-cartograph/actions/workflows/release.yml) 打开最新成功的 `main` 构建，在 Artifacts 下载 `cartograph-mc1.21.1-neoforge` ZIP 并解压 JAR。

```bash
cd /home/wanstu/projects/minecraft-cartograph
git status --short
git push origin main
```

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

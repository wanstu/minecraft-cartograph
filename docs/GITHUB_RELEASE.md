# Cartograph V3.1 的 GitHub 发布

Minecraft 1.21.1 的 NeoForge 和 Fabric 包由同一个 Git 标签构建，各包使用对应加载器的模组元数据。版本号来自 `v3.1/VERSION`，原生界面和地图实现位于 `v3.1/common-1.21.1`。

1. 更新 `v3.1/VERSION`，为正式版本添加对应的 `docs/releases/v<版本>.md`。
2. 使用 Java 21 在 `v3.1/neoforge-1.21.1` 和 `v3.1/fabric-1.21.1` 分别运行 `bash ./gradlew --no-daemon check build`。在实际游戏中验收区块补图、地图缩放、缓存恢复和保存退出。
3. 推送提交到 `main`，等待 [Cartograph V3.1 构建](https://github.com/wanstu/minecraft-cartograph/actions/workflows/release.yml) 的两个目标通过。
4. 对通过构建的提交创建并推送同版本的 `v<版本>` 标签。CI 校验两个加载器的 JAR，生成同一份 `SHA256SUMS.txt`，随后发布 [GitHub Release](https://github.com/wanstu/minecraft-cartograph/releases)。

`main` 和 PR 的构建附件可在 GitHub Actions 下载。`main` 的版本号包含开发后缀时，CI 也会更新 `development` 预发布附件。

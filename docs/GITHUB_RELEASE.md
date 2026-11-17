# Cartograph V3.1 的 GitHub 发布

[发布页](https://github.com/wanstu/minecraft-cartograph/releases) 提供 Minecraft 1.21.1 NeoForge 的 V3.1 JAR 和 `SHA256SUMS.txt`。

1. 在 `v3.1/neoforge-1.21.1/gradle.properties` 设置正式版本号，并添加对应的 `docs/releases/v<版本>.md`。
2. 使用 Java 21 运行 `bash ./gradlew --no-daemon check build`，验收实际存档中的地图显示、缓存恢复及保存退出。
3. 推送提交到 `main`，等待 [Cartograph V3.1 NeoForge 构建](https://github.com/wanstu/minecraft-cartograph/actions/workflows/release.yml) 完成。
4. 对通过构建的提交创建并推送同版本的 `v<版本>` 标签。标签构建检查版本、模组 ID、JAR 内容和 SHA-256，随后发布 GitHub Release。

`main` 和 PR 的构建附件可在 GitHub Actions 下载。`main` 的版本号包含开发后缀时，CI 也会更新 `development` 预发布附件。

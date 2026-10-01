# 开发与发布

项目由 [LuoH-AN](https://github.com/LuoH-AN) 维护。构建和原生测试通过本仓库的 GitHub Actions 执行。

## 测试包

`main`、`feat/**`、`dev/**` 分支触发 `Build Debug APK`。通过后可在对应运行的 Artifacts 下载测试 APK。

测试包使用 `com.luoh.music.lrc.dev`，与正式包分开安装。

## 正式版

版本在 `app/build.gradle.kts` 中维护；发布时同步更新 README、CHANGELOG 和 `docs/releases/<版本>.md`。`versionCode` 必须递增。

`Release APK` 工作流在 `feat/release-*` 分支上先构建、签名和验证，不创建公开 Release。检查通过并将代码合入 `main` 后，推送与版本一致的 `v<版本>` 标签；同一工作流再次验证 APK 后发布正式 Release，附带 APK 和 `SHA256SUMS`。

签名由以下 GitHub Actions Secrets 提供：

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

密钥使用 PKCS12。不得提交私钥、密码或签名文件，也不得把它们上传到公开 Artifacts；公开的 `debug.keystore` 只供测试包使用。正式签名须另外保留私有备份，后续版本继续使用同一签名。

工作流会校验版本、正式包名、非调试属性和 APK 签名。测试、校验或签名失败时不会发布。

正式签名证书 SHA-256：`482fae2e74bcf1cce5a4006dae7c86795b6eeb0112154cff462b20e4dd07300c`。此指纹不是私钥，可用于核验发布包；工作流会拒绝其他证书签名的 APK。

## 参考

[原生界面结构](material-ui.md) · [歌词搜索流程](lyrics-search.md) · [来源与致谢](../NOTICE.md)

# 第三方组件声明

Re:Suiteki Pro 以 GNU AGPL-3.0 发布（见 [LICENSE](LICENSE)）。
本项目包含下列第三方组件，其版权归各自作者所有，使用时遵循各自的许可证。
完整依赖树见 `gradle/libs.versions.toml` 与 `app/build.gradle.kts`。

## 需要特别说明的组件

### Bouncy Castle

| 项 | 内容 |
|---|---|
| 文件 | `app/lib/Bouncycastle.jar`（裁剪子集，47 个类，约 49 KB） |
| 包名 | `org.bouncycastle.shaded.*` |
| 版权 | Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org) |
| 许可证 | Bouncy Castle Licence（MIT 式许可证，全文见 https://www.bouncycastle.org/licence.html） |

该 jar 是完整 `bcprov` 的裁剪产物，只保留 AES-CCM 加解密所必需的类
（`AESEngine`、`CCMBlockCipher`、`CBCBlockCipher`、`SICBlockCipher` 及参数/工具类），
没有 RSA、ECDH、证书等部分。包名被重定向为 `org.bouncycastle.shaded`，
以区别于 Android 系统内置的 `org.bouncycastle`，避免类冲突。

本项目未修改其中的算法实现，仅按上述方式重新打包。用途见
`device/xiaomi/XiaomiAuthService.kt`：小米手环认证完成后，所有命令载荷使用
AES-128-CCM（32 bit tag）加密。

## 主要运行时依赖

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| AndroidX Core / Activity / Lifecycle / Navigation | — | Apache-2.0 | 基础框架 |
| Jetpack Compose（BOM 2024.04.01，含 Material3、material-icons-extended） | — | Apache-2.0 | UI |
| AndroidX Room | 2.6.1 | Apache-2.0 | 设备数据库 |
| Kotlin Standard Library / Reflect / Coroutines | 1.9.23 | Apache-2.0 | 语言运行时 |
| Google Protobuf Java | 3.25.3 | BSD 3-Clause | 小米协议编解码 |
| Gson | 2.10.1 | Apache-2.0 | JSON 解析（`.zab` 清单等） |
| Okio | 3.4.0 | Apache-2.0 | I/O |
| Apache Commons Lang3 | 3.7 | Apache-2.0 | 字节数组工具 |
| FastBle | 2.4.0 | Apache-2.0 | BLE 扫描与连接 |
| Compose Destinations | 2.0.0-beta01 | Apache-2.0 | 类型安全导航 |
| Flexbox | 3.0.0 | Apache-2.0 | 布局 |
| material-kolor | 1.4.4 | MIT | Material You 取色 |
| flexible-bottomsheet-material3 | 0.1.2 | Apache-2.0 | 底部弹层 |
| Accompanist Navigation Animation | 0.35.0-alpha | Apache-2.0 | 导航转场 |

## 其他

- 主页「一言」文案来自 [hitokoto.cn](https://hitokoto.cn) 公开接口，内容版权归原作者。
- 项目源自 [Sky130/Suiteki](https://github.com/sky130/Suiteki)（AGPL-3.0），
  上游版权声明见 `LICENSE` 与源码文件头。
- 表盘、固件、小程序等安装包由用户自行提供，本项目不附带任何此类资源。

---

若你是上述组件的权利人，认为本项目的使用方式不妥，请通过仓库 Issues 联系。

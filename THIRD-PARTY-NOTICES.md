# 第三方组件声明

Re:Suiteki Pro 以 GNU AGPL-3.0 发布（见 [LICENSE](LICENSE)）。
本项目包含下列第三方组件，其版权归各自作者所有，使用时遵循各自的许可证。
完整依赖树见 `gradle/libs.versions.toml` 与 `app/build.gradle.kts`。

## 需要特别说明的组件

### Bouncy Castle

| 项 | 内容 |
|---|---|
| 文件 | `app/lib/Bouncycastle.jar`（裁剪子集，32 个源文件 / 35 个 class，49,302 字节） |
| 上游 | Bouncy Castle `bcprov-jdk18on` **1.76**（2023-09-19 发布） |
| 包名 | `org.bouncycastle.shaded.*` |
| 版权 | Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org) |
| 许可证 | Bouncy Castle Licence（MIT 式许可证，全文见 https://www.bouncycastle.org/licence.html） |
| 构建 | 2024-04-30，由源码编译，非官方构件 |

该 jar 是完整 `bcprov` 的裁剪产物，只保留 AES-CCM 加解密所必需的类
（`AESEngine`、`CCMBlockCipher`、`CBCBlockCipher`、`SICBlockCipher` 及参数/工具类），
没有 RSA、ECDH、证书等部分。包名被重定向为 `org.bouncycastle.shaded`，
以区别于 Android 系统内置的 `org.bouncycastle`，避免类冲突。

用途见 `device/xiaomi/XiaomiAuthService.kt`：小米手环认证完成后，所有命令载荷使用
AES-128-CCM（32 bit tag）加密。

**相对上游的全部改动**（未触碰算法逻辑）：

| 文件 | 改动 |
|---|---|
| 全部 32 个 | 包名 `org.bouncycastle.*` → `org.bouncycastle.shaded.*` |
| `AESEngine` | 注释掉 2 处 `CryptoServicesRegistrar.checkConstraints(...)`，删掉对应 2 个 import。该调用牵连 `CryptoServicesRegistrar`、`DefaultServiceProperties`、`Utils` 一整条依赖链，与加解密结果无关 |
| `Pack` | 只保留实际用到的 4 个方法（上游 46 个） |
| `Strings` | 只保留 3 个方法，且这份代码取自**反编译产物**（文件内仍保留 `/* loaded from: classes.dex */` 注释） |
| `SkippingCipher`、`AEADCipher` | 仅 javadoc 中的全限定名被简写，逻辑未变 |
| `Arrays`、`CBCBlockCipherMac` | 仅缩进不同 |
| 其余 25 个 | 与上游逐字节一致（含 `CCMBlockCipher`、`CBCBlockCipher`、`SICBlockCipher`、`KeyParameter`、`AEADParameters`） |

上游 1.76 的源码包本身即不含逐文件的许可证头注释，jar 内也没有；因此本文件的这一节即承担
Bouncy Castle Licence 所要求的版权声明作用。

**来源已核实**：原始源码在本仓库历史中，`a3fc306`（2024-04-27）引入，
`e8d5610`（2024-04-30「将Java代码打包成Jar，缩短编译时间」）删除源码、改为提交编译产物。
用 `git show e8d5610^:app/src/main/java/org/bouncycastle/...` 可取回全文。

版本号按以下证据判定为 **1.76**：`CCMBlockCipher` 与 1.76、1.77 完全一致，1.78 起加入了
`inputAdjustment` 分支（`CCM packet too large` 判定位置不同）；`Arrays` 1227 行与 1.76 完全一致，
1.77 起增至 1243 行。比对对象为 Maven Central 上的 `bcprov-jdk18on-sources`。

如需替换为官方构件，添加 `implementation("org.bouncycastle:bcprov-jdk18on:1.76")`
并把 import 改回 `org.bouncycastle.crypto.*` 即可，代价是 release 体积增加约 4~6 MB。

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

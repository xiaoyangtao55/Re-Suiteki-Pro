# Re-Suiteki-Pro

给小米手环 / 红米手表装表盘和小程序，不登录 Zepp、不需要云端。

应用名 **Re:Suiteki Pro**，包名 `com.re.suiteki.pro`，可以和原版 Suiteki 同时安装。

基于 [sky130/Suiteki](https://github.com/sky130/Suiteki) 重做设备层，小米私有协议的实现参考
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)。

## 支持情况

| 型号 | 状态 | 传输通道 | 需要授权密钥 |
|---|---|---|---|
| 小米手环 7 | ✅ 已实测 | BLE `0x1530` 固件通道 | 否 |
| 小米手环 8 | ⚠️ 未验证 | BLE `fe95` | 是 |
| 小米手环 8 Pro | ⚠️ 未验证 | SPP (RFCOMM) | 是 |
| Redmi Watch 4 | ⚠️ 未验证 | SPP (RFCOMM) | 是 |
| Watch S3 | ⚠️ 未验证 | SPP (RFCOMM) | 是 |

> 手环 7 之外的四个型号代码已完成，但没有任何设备验证过。
> 如果连不上或装不完，请附上「关于 → 运行日志 → 复制」的内容提 issue。

### 手环 7 为什么不要密钥

Band 7 的固件/表盘通道（`0x1530/0x1531/0x1532`）是老的文件传输服务，
手环 3 时代就有，固件不校验 `authKey`。所以这个版本直接跳过 `0x0082` 认证，
连上就能装。

官方 App 和 Suiteki 1.8.x 之所以看起来"要密钥"，是因为它们走了认证流程；
1.8.x 其实是自己从小米运动健康的日志里把 `authKey` 读出来填进去的。

## 功能

- 安装表盘（`.bin`）
- 安装小程序 / 快应用（`.zab` / `.zip`）
- 查看已安装的应用列表
- 删除应用
- 给应用改名字（手动命名，或安装时从包内 `app.json` 自动读取）

## 安装

从 [Releases](../../releases) 下载 APK。两个包的区别：

| 文件 | 包名 | 说明 |
|---|---|---|
| `*-release.apk` | `...suiteki.pro` | 体积小，日常使用 |
| `*-debug.apk` | `...suiteki.pro.dev` | 日志更全，可与 release 共存，排障用 |

都是用 debug 证书签名的，安装时系统会提示"未知来源"。

**权限**：只需要蓝牙。Android 11 及以下系统还会要求定位权限，那是系统对 BLE 扫描的硬性规定，
应用只用它发现手环，不读取、不保存任何位置信息。

日志导入和选择安装包都走系统文件选择器，只读取你当场选中的那一个文件，
不申请存储权限，也不会扫描手机里的其它内容。

## 使用

1. 「设备」页右下角 `+` → 选型号 → 填 MAC（可点扫描）→ 确定
2. 回「主页」，在「未连接设备」里点刚添加的那台连接
3. 连上后点「安装」，选表盘或小程序包

## 构建

```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包（用 debug 证书签名）
./gradlew test               # 单元测试
```

- JDK 17
- Android SDK 34
- Gradle 8.6（wrapper 里已固定）

## 已知限制

- **不能远程启动手环上的应用**。Zepp OS 的服务列表里没有这个端点，
  `0x0023` 是表盘服务，`0x07` 是"设为当前表盘"，与启动 app 无关。
- 应用列表只有 id 和版本号，设备本身不返回名字。
- 小米系四条链路未上真机，SPP 通道只实现了协议 V1，没有版本协商。

## 致谢

- [sky130/Suiteki](https://github.com/sky130/Suiteki) — 项目起点
- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — 小米私有协议、Zepp OS 协议的实现参考
- 1.8.3 版本的安装状态机与默认授权密钥

## 许可证

[AGPL-3.0](LICENSE)。`XiaomiSppPacket.kt`、`XiaomiFWHelper.kt`、`StringUtils.kt`
等文件衍生自 Gadgetbridge，保留原始版权头。

## 免责声明

刷表盘/固件有变砖风险，自行评估。作者不对设备损坏负责。

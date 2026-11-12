# GitHub 开源案例调研：蓝牙连接 + 数据保存 + 表格 / 图表 / 导出

> 调研日期：2026-11-12 · 检索方式：GitHub 仓库检索 + 代码检索 + 逐个读 README / 目录结构
> 目的：为「蓝牙卡尺测量数据助手」找可借鉴的成熟案例，并回答 **Android APK 适配更多机型** 怎么落地
> 状态：**调研结果已交付**；本轮不写源码
>
> **后续状态（2026-11-12 同日追加）**：本文件是当时的调研快照，结论与证据保持原样。你已据此确认 **路线 C**；第 4 节的三选一、第 6 节的「待定」、第 7 节的三个问题均已关闭，定稿见 [README.md](./README.md) v0.3 与 [V1开发基线.md](./V1开发基线.md)。第 3.3 节的「分ABI包体」已更正为**不分 ABI**（本项目无 native 库，但签名需 v1+v2+v3 全开）；第 4 节路线 C 里的 MPAndroidChart 已改选 Vico（停维风险证据与回退条件见 `V1开发基线.md` 第 2.4、5.1 节）。

---

## 0. 三句话结论

1. **没有现成可抄的「蓝牙卡尺 App」**。GitHub 上卡尺类项目都是小工具（读数播报、ESP32 硬件侧），没有数据库 / 表格 / 图表 / 导出。
2. **但有一条几乎一模一样的成熟链路可借鉴**：`oliexdev/openScale`（蓝牙体重秤 → 本地数据库 → 表格 + 图表 + 统计 → CSV 导入导出 → 适配几十种设备），2661 ★、Kotlin、仍在活跃维护。它就是我们 V1 的镜像。
3. **广陆这套协议（`GL-BLE#` / `DAT0:`）在 GitHub 代码搜索里 0 命中**，协议解析层必须自己写，只能借鉴架构，不能复制实现。
4. ⚠️ **额外发现一个会改技术选型的坑**：原方案里的 `flutter_blue_plus` 采用自定义许可证，**营利性使用需要购买商业授权**，不是免费开源库。详见第 4 节。

---

## 1. 最值得借鉴：openScale（首推）

| 项 | 内容 |
| --- | --- |
| 仓库 | https://github.com/oliexdev/openScale |
| 规模 / 状态 | 2661 ★ · 582 fork · Kotlin · **2026-11-11 仍在推送**，未归档 |
| 许可证 | **GPL-3.0**（⚠️ 直接复制源码会要求整个 App 开源；借鉴架构与思路可以） |
| 定位 | 开源体重 / 体成分记录 App，支持几十种蓝牙秤 |

README 原文逐条对上我们的需求：

| openScale 已有能力（README 原文要点） | 对应我们 V1 的哪一块 |
| --- | --- |
| `Displays all your data on a chart and in a table to track your progress` | 数据表格页 P3 + 图表页 P5 |
| `Import or export your data from/into a CSV file` | CSV 模板上传 + 原始数据导出 V1 必做项 |
| `Evaluates measurements and gives a quick visual feedback ... within or outside the recommended range` | 公差上下限 / 合格超差判定与色标 |
| `supports various Bluetooth scales`（几十种型号，含 Custom made Bluetooth scale） | **多设备 / 多机型适配的设备抽象层** |
| `doesn't require you to create an account` / `doesn't send any data to a cloud` / 不申请 INTERNET 权限 | V1 纯本地、无后端、无账号 |
| `The location permission is only needed to find a Bluetooth scale` | Android 11 及以下 BLE 扫描的定位权限处理 |
| `metrics you don't use can be disabled and hidden` / `custom metrics` | 模板字段可配置、列显隐 |

**建议重点读这几个文件**（路径均已核实存在）：

| 文件 | 借鉴什么 |
| --- | --- |
| `android_app/app/src/main/java/com/health/openscale/core/database/AppDatabase.kt` | Room 本地库建模（对应我们的 `measurement` / `batch` / `template` 表） |
| `android_app/app/src/main/java/com/health/openscale/core/usecase/ImportExportUseCases.kt` | CSV 导入导出用例（对应模板上传 + CSV 导出） |
| `android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/CustomOpenScaleHandler.kt` | **自定义蓝牙设备的协议解析层**——我们写 `DAT0/DAT1/MEAS/CLR/MOD` 解析时照这个分层 |
| `android_app/app/src/main/java/com/health/openscale/core/data/MeasurementType.kt` | 测量指标类型的可扩展定义（对应模板字段类型） |
| `android_app/app/src/main/java/com/health/openscale/ui/screen/table/TableScreen.kt` | 表格页 UI 结构 |
| `android_app/app/src/main/java/com/health/openscale/core/facade/DataManagementFacade.kt` | 数据层统一门面，隔离 UI 与数据库 |

> 一句话：**把 openScale 的「体重」换成「卡尺读数」，把「蓝牙秤驱动」换成「广陆 DAT0 解析」，骨架基本可以直接搬思路。**

### 次要参考（同一类，但不如 openScale 贴合）

| 仓库 | ★ | 协议 | 可借鉴 / 不推荐原因 |
| --- | --- | --- | --- |
| https://github.com/phyphox/phyphox-android | 669 | 需查证 | 亚琛工大的物理实验 App，BLE 传感器采集 + 实时图表 + 数据导出做得很成熟；但业务模型是「实验」不是「检验记录表格」 |
| https://github.com/OpenTracksApp/OpenTracks | 1400 | Apache-2.0 | BLE 传感器 + 本地数据 + 导出；**GitHub 仓库已归档**，项目迁到 codeberg.org，参考价值降低 |
| https://github.com/Freeyourgadget/Gadgetbridge | 4603 | **AGPL-3.0** | 最成熟的「多品牌 BLE 设备 + 本地数据库」Android App，机型适配经验最丰富；但 GitHub 已归档迁 Codeberg，且 AGPL 传染性最强，商业使用风险高 |

---

## 2. 卡尺专门项目：结论是「没有成熟案例」

| 仓库 | ★ | 语言 | 实际内容 |
| --- | --- | --- | --- |
| https://github.com/MachineryEnchantress/SpeakingCaliperApp | 24 | Java | README 只有一句：`Speaking Caliper is used to read the output of a Bluetooth caliper or micrometer`。2020 年后未更新，**只做读数语音播报**，没有数据库 / 表格 / 图表 / 导出 |
| https://github.com/Benjaminbburn/Bluetooth-Calipers | 13 | C++ | ESP32 硬件侧项目，不是手机 App |
| https://github.com/Mew463/esp32-caliper | 59 | HTML | ESP32 读卡尺，硬件侧 |
| https://github.com/djneo33/WirelessCaliper2 | 0 | Java | 个人实验项目，无 star |
| https://github.com/Faith3494/Bluetooth-caliper-baseplate | 0 | C | 「蓝牙卡尺」底座结构件，硬件侧 |

**代码检索证据**（GitHub 全库代码搜索，2026-11-12）：

| 查询 | 命中 | 结论 |
| --- | --- | --- |
| `"GL-BLE#"` | **0** | 没有公开的广陆广播名前缀实现 |
| `"DAT0:m"` | 2（均为无关文件：`gipsy` 模板、`scout-dealerlab` TS 文件） | 没有公开的 `DAT0` 帧解析实现 |
| `repo:oliexdev/openScale "exportCsv" / "csv"` | 命中 `ImportExportUseCases.kt` 等 | openScale 的 CSV 能力确实存在、可定位 |

> 所以：**协议层（`GL-BLE#` 扫描过滤、`DAT0/DAT1` 帧解析、`DAT0:OK` 握手、`MEAS/CLR/MOD/NAM` 指令、mm/inch 换算）必须按接口文档自己写**，这部分没有捷径，也是 M0 实机验证必须先过的一关。

---

## 3. Android 多机型适配：这才是本次调研的重点

### 3.1 三个可直接用的成熟库

| 仓库 | ★ / 协议 / 活跃度 | README 里明确写到的机型兼容能力 |
| --- | --- | --- |
| https://github.com/weliem/blessed-android | 595 · **MIT** · 2026-05 推送 | Android 5+；`Workarounds for some known Android bugs`；命令队列（不必等上一条完成）；自动配对 / 绑定；线程安全；`autoConnectPeripheral` 断线自动重连；MTU 协商；多设备同时连接；`BluetoothBytesParser` 解析字节帧。**MIT 无授权成本** |
| https://github.com/nordicsemi/Android-Scanner-Compat-Library | 796 · **BSD-3** · 2026-11 推送 | 抹平 Android 4.3 / 5.0 / 6.0 / 8.0 扫描 API 差异；老机型上用软件模拟硬件过滤与批量上报；`useHardwareFilteringIfSupported(false)` 可绕开部分机型硬件过滤不可靠；Android 6/7 Doze 不广播；Android 12 `BLUETOOTH_SCAN` + `neverForLocation` 权限写法 |
| https://github.com/dariuszseweryn/RxAndroidBle | 3540 · **Apache-2.0** · 2025-06 推送 | 最老牌的 Android BLE 封装之一，RxJava3 接口，错误处理与连接生命周期成熟 |

### 3.2 原方案 `flutter_blue_plus` 的真实情况（重要）

| 项 | 内容 |
| --- | --- |
| 仓库 | https://github.com/chipweinberger/flutter_blue_plus · 1011 ★ · Dart · 2026-11-10 更新 |
| **许可证** | **自定义 FlutterBluePlus License**：README 原文 `Nonprofit use: Free for personal use, nonprofits, and educational institutions` / `Commercial use: For for-profit ... a Commercial License is required`。**公司 / 营利场景必须买商业授权** |
| 机型坑（README「Common Problems」原文） | `The Huawei P8 Lite is one of the reported phones to have this issue. Try stopping your scanner before connecting.`（部分机型扫描中不能连接）；Android 每 30 秒最多 `startScan` 5 次；`ANDROID_SPECIFIC_ERROR` 无 100% 解法，只能 catch + retry；Android 配对弹窗出现两次（系统 bug，需主动 `createBond()`）；`minSdkVersion 21`；release 包必须加 Proguard keep 规则 |
| 与本项目直接相关 | `onValueReceived is called with duplicate data`（重复帧）、`onValueReceived data is split up`（**拆包 / 粘包**）两条，正好对应我们 `DAT1` 连续上报要去重、以及 `DAT0:m+155.15` 可能被拆成两半必须做帧缓冲 |
| 权限清单 | README 给了 Android 12 / 11 / 9 三档权限模板，可直接抄配置 |

> ⚠️ 上一版 README 把它当免费开源库写进技术选型，这里更正：**需要商业授权，且它主打的跨端优势在你只要 Android 的场景下用不上。**

### 3.3 建议的机型适配清单（写进 M0 门禁）

| 维度 | 要验证 / 要处理 |
| --- | --- |
| 系统版本 | minSdk 21 起；按 Android 12+ / 11 / 9 及以下三档声明权限；`neverForLocation` 免定位 |
| 品牌 ROM | 华为 / 小米 / OPPO / vivo / 荣耀 / 三星 各至少 1 台；重点测「扫描中发起连接」「后台回前台重连」「定位开关关闭时能否扫描」 |
| 扫描策略 | 优先按 `GL-BLE#` 名称前缀过滤；硬件过滤不可靠时降级为软件过滤；遵守 30 秒 5 次扫描限制；连接前先 `stopScan()` |
| 连接稳定性 | 连接失败 catch + 指数退避重试；`autoConnect` 断线自动重连；重连后**必须重新 discoverServices + 重新订阅 notify** |
| 数据完整性 | notify 帧做缓冲拼包（防 `DAT0` 被拆）、去重（防 `DAT1` 刷屏）；收到即落库 |
| 后台 / 省电 | 国产 ROM 后台蓝牙限制差异大，V1 按「前台采集」设计，不承诺后台保活 |
| 打包 | release 混淆规则、签名 keystore、分ABI包体；真机安装测试而非模拟器（README 明确说模拟器扫不到 BLE） |

---

## 4. 技术选型要不要改：三条路线对比

既然你确定**只做 Android APK + 尽量多机型**，跨端框架的收益基本没了，而商业授权和多机型坑都在 Flutter 插件这一侧。

| 路线 | 做法 | 优点 | 代价 |
| --- | --- | --- | --- |
| **A. 维持 Flutter + flutter_blue_plus** | 保持上一版 README 方案 | UI 开发快、图表 / 导出生态好、改动最小 | 需购买商业授权；机型坑靠插件内置缓解，深度定制要 fork；跨端能力闲置 |
| **B. Flutter + 自写原生 BLE 通道**（推荐之一） | UI 仍 Flutter；BLE 层用 MethodChannel/EventChannel 调 `blessed-android` + `Nordic Scanner Compat` | 保留 Flutter 的表格 / 图表 / 导出开发效率；蓝牙层用 MIT/BSD 免费库，机型适配可控；无授权成本 | 要写一层原生桥接代码，双端工作量略增（但你目前只要 Android） |
| **C. 纯原生 Kotlin**（推荐之一） | Kotlin + Room + `blessed-android` + Nordic Scanner + MPAndroidChart + 自写 CSV/PNG 导出 | **机型兼容性最强**、完全对齐 openScale 的技术栈（借鉴成本最低）、无授权风险、包体最小 | 以后若要做 iOS 需重写；UI 开发比 Flutter 慢 |

**我的建议**：如果这 App 是公司内部 / 车间自用且短期不做 iOS → **路线 C**，直接照 openScale 的骨架做，机型适配最稳；如果后面仍可能要 iOS 或想快速迭代 UI → **路线 B**。路线 A 只有在你愿意买商业授权、且机型范围可控时才划算。

> 这一条会改 README 的「⑧ 技术选型方案」，所以先摆出来给你定，不擅自动手。

---

## 5. 许可证风险一览（借鉴前必看）

| 项目 | 协议 | 能不能抄代码 |
| --- | --- | --- |
| openScale | GPL-3.0 | ❌ 直接复制会让你的 App 被迫以 GPL 开源；✅ 可以看架构、读实现思路后自己写 |
| Gadgetbridge | AGPL-3.0 | ❌ 传染性最强，尤其涉及网络服务时 |
| phyphox-android | 需单独核实 | ⚠️ 先查 LICENSE 再决定 |
| blessed-android | **MIT** | ✅ 可自由使用（保留版权声明） |
| Nordic Scanner Compat | **BSD-3** | ✅ 可自由使用（保留版权声明） |
| RxAndroidBle | **Apache-2.0** | ✅ 可自由使用 |
| flutter_blue_plus | **自定义商业授权** | ⚠️ 营利使用需购买 Commercial License |

> 结论：**可以借鉴的免费库都在原生侧（MIT / BSD / Apache），需要付费或会传染的都在 Flutter 插件 / 完整 App 侧。**

---

## 6. 对现有需求草案的影响（已同步到 README）

| 变更 | 内容 |
| --- | --- |
| 已确认 | 目标平台 = **Android APK**，V1 不交付 iOS IPA；机型适配范围扩大 |
| 新增必做 | 协议层自己做（GitHub 无现成实现）；帧缓冲拼包 + 去重；重连后重新订阅 notify |
| 待定 | 技术选型 A / B / C 三选一（见第 4 节） |
| 待验证 | 真机机型矩阵、`DAT0:OK` 回包时机、`DAT1` 上报频率、真实模板样表 |

---

## 7. 下一步讨论的 3 个问题

1. **选哪条技术路线**（A / B / C）——决定蓝牙层写法、机型适配上限、以及有没有商业授权成本。
2. **要不要按 openScale 的骨架来**（Room 分库 / 设备驱动抽象 / 用例层）——决定源码目录结构，也决定后续能不能平滑加第二台卡尺型号。
3. **机型清单能给到什么程度**（有没有现成的车间在用手机型号 / 最低 Android 版本）——决定 M0 门禁要测几台、minSdk 定多少。

另外上一版 README 的第 2、3 问（**真实模板样表**、**增减表是否认可**）仍未回答，一起拍板后我再进入源码与打包方案。

---

## 证据台账

| 结论 | 证据来源 |
| --- | --- |
| openScale 功能清单、多设备支持、CSV 导入导出、无账号无云 | `README.md` 原文 |
| openScale 具体可借鉴文件路径 | `android_app/app/src/main/java/com/health/openscale/` 目录 + 代码检索命中 |
| 广陆协议无公开实现 | 代码检索 `"GL-BLE#"` → 0；`"DAT0:m"` → 2 条无关命中 |
| flutter_blue_plus 商业授权与机型坑 | `packages/flutter_blue_plus/README.md`（根目录 README 为符号链接） |
| blessed-android 机型 workaround | `README.md` Introduction / Connecting / MTU 章节 |
| Nordic 跨版本扫描兼容 | `README.md` 全文（API 差异、权限、Doze、硬件过滤开关） |
| 各仓库 star / 协议 / 活跃度 | GitHub 仓库检索返回的元数据（2026-11-12） |

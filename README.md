# Lenovo Legion Y900 (TB522FU) 注视不熄屏增强模块 (AttentionService)

专为**联想拯救者 Y900 (TB522FU)**（Snapdragon 8 Elite，ColorOS 16 移植版）深度定制的「注视时不熄屏」接管与增强模块。

核心思路：**不走重量级相机方案，直接调用联想原生低功耗 AON 相机硬件**（Camera 3 / OG0VE，Qualcomm AON FDPRO），以 AOSP 标准 `AttentionService` 契约接入框架，实现**平时零功耗、灭屏前按需探测**的注视感知。

---

## 🆚 与 LwKy（凝光）方案对比

底包 ROM 内置的 LwKy 注视方案（`lwky_oplus_aon` 初始化服务）通过**监听系统日志**在灭屏前触发唤醒前置检测，配合 AON 相机完成注视判断；另有独立的流光灯控制应用（`lwky.FlowingLight.Control`，驱动后置模组灯环 aw22xxx LED，与注视感知无关）。本模块是对注视感知部分的完全替代，两者的设计差异：

| 维度 | LwKy 方案 | 本模块方案 |
|---|---|---|
| **感知硬件** | AON 相机（经由 ROM 脚本编排） | Camera 3 (OG0VE) AON FDPRO 直连 daemon，**推理跑在专用 Hexagon DSP（ADSP）**，不占用 AP——主 SoC 可保持深睡 |
| **触发方式** | 日志监听 + 唤醒前置检测 | **Demand Mode 按需**：仅在框架灭屏前回调 `onCheckAttention` 的 4s 预算内拉流，1~2 帧即断电 |
| **流生命周期** | 由脚本编排启停 | **协议级停止**：`state=stop seq=` → daemon `UnregisterClient (0x8dc0)`，框架回调间隔内流保持关闭 |
| **框架接入** | ROM 内置服务 + frameworkres overlay | **AOSP 标准契约**：secure `attention_service_component` 指向无头 APK，无 hook、无 priv-app 依赖 |
| **运行防护** | — | supervisor 15s 看护 + **ADSP 熔断**（15s 内 ≥3 crash → 杀全部客户端 + 10min 冷静期）+ **bootloop guard**（连续 3 次开机失败自动禁用并还原设置） |
| **控制界面** | ROM 集成 | **无头 APK**（无界面）+ 管理器 WebUI（KernelSU/ReSukiSU/APatch/MMRL）+ `attention_ctrl` CLI |
| **实测能耗** | — | 熄屏 0.78%/h；AON app 自身整夜 **0.167 mAh**；ADSP 0 crash（2026-09-17 整夜基线） |

说明：之前排查到的整夜异常掉电，根因是底包 LwKy AON 集成中的 `is_island=1` 配置导致 AON 相机跨区域 fault，引发 ADSP 5s SSR 自持风暴；本模块通过 persist registry 终态修复（`is_island=0`）+ 服务净化将该路径彻底关闭，整夜基线测试确认无复发。

---

## 🌟 核心特性

1. **原生 AOSP 架构接管**：直接接入 `AttentionManagerService` 与 `PowerManagerService`，让系统【设置 - 显示与亮度 - 注视时不熄屏】真正生效，解决移植版缺少 Priv-App 特权导致的绑定断链。
2. **零待机能耗**：平时与息屏时前摄 100% 断电、不与协处理器握手；仅灭屏前单次探测（~350ms，1~2 帧），检测完立即释放。
3. **Demand Mode**：流生命周期完全跟随框架回调，探测答案带置信度（PRESENT 立即回 / 3 连 ABSENT 才判负 / 超时有事件判正），dwell 窗口（默认 60s）后协议级停流。
4. **WebUI 控制台**：一屏式界面（横竖屏自适应），主开关、状态指标、立即测试、参数调节、实时日志（自动滚动）。
5. **三重防护**：服务看护（supervisor，单实例锁 + 退避重拉）+ ADSP 崩溃熔断 + 开机失败自动回退（bootloop guard）。
6. **跨 ROM 兼容设计**：核心依赖 AOSP 契约 + vendor 固件（AON HAL/QSH），不绑定 ColorOS；底包 AON 服务净化在其他 ROM 上自动 no-op。

---

## 📦 安装

1. 设备解锁 BL 并刷入 ReSukiSU / KernelSU / APatch / Magisk。
2. 从 [Releases](https://github.com/futureharmony/Lenovo-TB522FU-Attention-KeepOn/releases) 下载最新 zip（或本地构建 `./build_zip.sh`）。
3. 管理器 → 模块 → 本地安装 → 重启。

**开机失败自动回退**：连续 3 次开机失败（卡死在 boot_completed 之前）时，模块自动禁用自身并在下次成功开机后还原全部 secure 设置；在管理器中重新启用模块即可恢复，无需重刷。

---

## 🖥️ 使用

### WebUI（推荐）
管理器中点击模块卡片打开控制台：主开关 / 状态指标（服务、绑定、硬件）/ 立即测试 / 参数（Demand Mode、dwell）/ 实时日志。

### 系统设置
设置 → 显示与亮度 → 注视时不熄屏，与模块双向联动。

### 命令行（root）
```bash
attention_ctrl status       # 状态 JSON（服务/流/配置）
attention_ctrl on|off|toggle
attention_ctrl test         # 单次探测并输出耗时与结果
attention_ctrl set <json>   # 修改配置（preserve-merge）
attention_ctrl log [n] | clear_log
```

---

## 🛠️ 技术规格

- **设备**：联想拯救者 Y900 (TB522FU)，Snapdragon 8 Elite；**系统**：ColorOS 16 移植版（**Android 16 / SDK 36**）—— 模块只依赖 AOSP `AttentionService` 契约（Android 14+）与保留的 vendor 固件，不绑定具体 ROM
- **感知**：Camera 3 / OG0VE AON FDPRO —— 单色全局快门 ULP 传感器，QSH 通道直通 ADSP，**不占用普通相机流**（无 ISP 管线，CamX/CHI 路径恒返 `-38`）。运行模式 **480×360（`algoModeIdx=2`）**、`deliveryPerSec=15`；推理（EAI）跑在 ADSP Hexagon 主域
- **为什么不用 160×120**：FDPRO 共暴露 3 个模式 —— `160x120`（`isIslandCapable=1`）/ `320x240` / `480x360`。`160×120` 是固件唯一标记"可进岛"的模式，也正是崩溃源：`is_island=1` 时模型走后处理 island 分支，跨执行域取指 fault（`PD_ERR: qsh_process : EX:qsh_process:0x4:AonCam_0:0x10000014`），触发 ADSP 每 5~10 s 自持重启并连带 Type-C/USB 掉线。本模块终态为 **`is_island=0` + 480×360** —— 岛路径弃用是修复结论，不是设计取舍
- **配置修复范围**：`is_island` 归零覆盖 persist registry 中**全部 `nms_*` 模型**（`eod` / `fd_qqvga` / `fd_qvga` / `fd_360p` / `qrcode` / `hd`），开机修正 + 运行期漂移自检双重防线；任意一项漏改都会复发
- **架构**：`aon_daemon.bin`（250ms 轮询 aon_cmd → aon_evt）+ 无头 APK（`:attention` 进程，specialUse FGS）+ service.sh/supervisor.sh + WebUI
- **配置**：`/data/adb/tb522fu_attention/config.json`；**日志**：同目录 `attention.log`（自动轮转 800 行）
- **回退**：`boot_fail_count` 计数 + `AUTO_DISABLED` 标志 + `/data/adb/service.d/tb522fu_rollback_cleanup.sh`（独立于模块启停状态）

## 📚 技术文档

本项目的取证与逆向过程完整落盘在 [`docs/`](docs/)（**先读 [`docs/README.md`](docs/README.md) 索引**）：

| 文档 | 回答什么 |
|---|---|
| [`docs/PROJECT_HISTORY.md`](docs/PROJECT_HISTORY.md) | **项目总汇**：崩溃机制最终判决、修复与实测、原厂对照、**18 条已作废结论清单**、硬数据速查（地址 / SHA256 / 分区 / 参数语义） |
| [`docs/PITFALLS.md`](docs/PITFALLS.md) | 逆向 / 取证时**哪些判据会骗人**（每条都曾静默给出错误结论） |
| [`docs/github_module_vs_native_verdict_20260917.md`](docs/github_module_vs_native_verdict_20260917.md) | 联想原生方案与本模块的 12 项维度对照 + 实机 A/B 实验 + 模块缺陷清单 |
| [`docs/zux_attentive_display_why_not_crash_verdict_20260917.md`](docs/zux_attentive_display_why_not_crash_verdict_20260917.md) | 原厂 ROM 为什么"不崩"：其产品路径里根本没有该分支的调用者 |
| [`docs/zux_gd_vs_island0_verdict_20260917.md`](docs/zux_gd_vs_island0_verdict_20260917.md) | 原厂 GD（Tobii gaze）路线 vs 本模块 FD-Pro + `is_island=0` 路线的取舍 |
| [`docs/aon_session_lost_verdict_20260917.md`](docs/aon_session_lost_verdict_20260917.md) | 一次自我纠错记录：`cid=-1` 与零事件是 Demand Mode 空闲态，**不是**故障 |
| [`QSH_AON_FIRMWARE_BUG_REPORT.md`](QSH_AON_FIRMWARE_BUG_REPORT.md) | 提交给固件 / 平台侧的缺陷报告（fault 串与最小复现） |

> 文档是**当时的取证快照**，被判"已作废"的结论按原样保留（用于说明错误怎么发生）。判断当前有效结论以 `PROJECT_HISTORY.md` §8 为准。

---

## 📁 仓库结构

```
magisk_module/        # 模块本体（含预编译 aon.apk / aon_daemon.bin / WebUI）
app/                  # 无头 APK 源码（Kotlin）与构建脚本（app/tools 工具链不入库）
aon_ulp_probe/        # AON 硬件探针与逆向笔记（AIDL 还原、事件线格式破译）
docs/                 # 技术文档：故障取证、方案判决、坑清单（索引见 docs/README.md）
build_zip.sh          # 模块打包（版本号从 module.prop 单一来源读取）
.github/workflows/    # CI：语法检查 + tag 自动发版
```

> 注：`WorkBuddyKeyReuslt/`（本地分析工作区）与签名密钥 `app/aon_release.keystore` 不入库。

---

## 📝 版本

当前版本以 [`magisk_module/module.prop`](magisk_module/module.prop) 的 `version` 为**唯一来源**
（`build_zip.sh` 与 CI 均从此读取，不再硬编码）。近期变更：

- **v1.8.2** —— 修正 `attention_ctrl test` 的**假阴性**（曾报「无事件（流未出帧）」而实际流是通的）。三个独立缺陷：① 新帧判据用「`aon_evt` 文件变大」，但 **daemon 启动会重写该文件**（实测 55804 B → 61 B、`EVT` 1534 → 0），窗口跨越一次 daemon 重启即必然误报 —— 现改为检测到 size 回落即 **re-baseline** 并继续等待；② 8 s 窗口太短（AON 客户端交接后首次 `state=start` 偶发不生效），现为 20 s + 零帧**重发一次 start** 再等 10 s；③ 「流是否已在跑」原按 `HBT` 心跳的 `st` 判定，而 `HBT` 每 5 s 一条且滞后、热流下 `tail -n 20` 可能一条都取不到 —— 现改为「2 s 内是否**新增 EVT 行**」（心跳只写 `HBT`，故不能比文件大小）。另修正收尾语义：只在 `test` 自己拉起流时才 `stop`，且重试分支也计入（否则流会一直挂着）。
- **v1.8.1** —— 修复 `supervisor.sh` 重拉 daemon 时 IPC 路径与其余组件不一致导致的**控制面静默失联**（路径统一到 app files 目录）；`is_island` 修复范围由 4 个模型扩展到**全部 `nms_*` 模型**；supervisor 增加单实例锁（toybox 无 `flock`，用 `mkdir` 原子锁实现）与 **≥20 s 重拉退避**（AON 客户端是单占用资源，紧邻重拉必失败）。
- **v1.8.0** —— `attention_ctrl` 命令改为完整协议 `state=start <camIdx> <srv> <mask> <algo> <w> <h> <dps> seq=<ms>`；此前发裸 `start` 与 daemon「内容变化才处理」的语义打不出配合，表现为 `test` 长期返回 `ABSENT`。
- **v1.7** —— `is_island=0` 终态修复，关闭 ADSP 崩溃循环（根因见 `docs/PROJECT_HISTORY.md` §3）。
- **v1.6** —— 强制 `algo=2 / 480×360`（非岛模式），修复回调解析错位，daemon 内置 SSC 断连自愈。

> 发版：CI 校验 `tag` 必须与 `module.prop` 的 `version` **完全一致**（如 `v1.8.2`），否则 release job 失败。
>
> ⚠️ **发版前先处理两个幽灵 tag**：远端曾出现 `v1.9` / `v1.9.1`，它们指向**与 `main` 无关的孤立提交**、
> 内容**比 `main` 旧**（`is_island` 一个都没修），却会被 GitHub Releases 页显示成 "Latest"。
> 二选一：先 `git push --delete origin v1.9 v1.9.1` 再按 `v1.8.2` 发；或把版本号直接跳到 `v1.9.2`
> 承认该号段。`git ls-remote` 能读远端 ≠ 有写权限（公开仓库匿名只读也成功）。

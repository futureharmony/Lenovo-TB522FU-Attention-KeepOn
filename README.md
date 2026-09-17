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
5. **三重防护**：服务看护（supervisor）+ ADSP 崩溃熔断 + 开机失败自动回退（v1.9）。
6. **跨 ROM 兼容设计**：核心依赖 AOSP 契约 + vendor 固件（AON HAL/QSH），不绑定 ColorOS；底包 AON 服务净化在其他 ROM 上自动 no-op。

---

## 📦 安装

1. 设备解锁 BL 并刷入 ReSukiSU / KernelSU / APatch / Magisk。
2. 从 [Releases](https://github.com/futureharmony/Lenovo-TB522FU-Attention-KeepOn/releases) 下载最新 zip（或本地构建 `./build_zip.sh`）。
3. 管理器 → 模块 → 本地安装 → 重启。

**开机失败自动回退（v1.9）**：连续 3 次开机失败（卡死在 boot_completed 之前）时，模块自动禁用自身并在下次成功开机后还原全部 secure 设置；在管理器中重新启用模块即可恢复，无需重刷。

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

- **设备**：联想拯救者 Y900 (TB522FU)；**系统**：ColorOS 16 / Android 14+ 移植版（vendor 固件保留即可）
- **感知**：Camera 3 / OG0VE AON FDPRO（160x120 单色 ULP，QSH 通道直通 ADSP，不占用普通相机流）；推理引擎（EAI）跑在 ADSP Hexagon 主域。厂商固件另有 ADSP 低功耗岛（LPai island）执行路径，但存在构建期链接缺陷（`is_island=1` 即跨区取指 fault），故本模块终态配置 `is_island=0` 走主域——岛路径弃用是修复结论，不是设计取舍
- **架构**：`aon_daemon.bin`（250ms 轮询 aon_cmd → aon_evt）+ 无头 APK（`:attention` 进程，specialUse FGS）+ service.sh/supervisor.sh + WebUI
- **配置**：`/data/adb/tb522fu_attention/config.json`；**日志**：同目录 `attention.log`（自动轮转 800 行）
- **回退**：`boot_fail_count` 计数 + `AUTO_DISABLED` 标志 + `/data/adb/service.d/tb522fu_rollback_cleanup.sh`（独立于模块启停状态）

## 📁 仓库结构

```
magisk_module/        # 模块本体（含预编译 aon.apk / aon_daemon.bin / WebUI）
app/                  # 无头 APK 源码（Kotlin）与构建脚本（app/tools 工具链不入库）
aon_ulp_probe/        # AON 硬件探针与逆向笔记
build_zip.sh          # 模块打包
.github/workflows/    # CI：语法检查 + tag 自动发版
```

> 注：`WorkBuddyKeyReuslt/`（本地分析工作区）与签名密钥 `app/aon_release.keystore` 不入库。

# 技术文档索引

本目录收录本模块立项以来的**取证、逆向与方案判决文档**。它们记录的是"为什么这样设计"，
而不是"怎么用"（用法见根目录 [README](../README.md)）。

一句话背景：底包 ROM 的 AON 注视实现在 `is_island=1` 配置下会让 AON 相机跨执行域取指 fault，
引发 ADSP 反复自重启（每 5~10 s）并连带 Type-C/USB 掉线。本模块的终态修复是把
`is_island` 归零并把 AON 客户端收敛为唯一实例。这条链路的完整证据在下面这些文档里。

---

## 先读哪份

| 你想知道 | 读这份 |
|---|---|
| 这个故障到底是什么、怎么修的 | [`PROJECT_HISTORY.md`](PROJECT_HISTORY.md) |
| 有哪些**曾经写对、后来被推翻**的结论 | [`PROJECT_HISTORY.md`](PROJECT_HISTORY.md) §8（18 条） |
| 逆向 / 取证时哪些判据会骗人 | [`PITFALLS.md`](PITFALLS.md) |
| 为什么不能启用固件的"岛"（island）路径 | [`PROJECT_HISTORY.md`](PROJECT_HISTORY.md) §3 |
| 联想原厂方案和本模块谁更好 | [`github_module_vs_native_verdict_20260917.md`](github_module_vs_native_verdict_20260917.md) |
| 原厂 ROM 为什么"不崩" | [`zux_attentive_display_why_not_crash_verdict_20260917.md`](zux_attentive_display_why_not_crash_verdict_20260917.md) |
| 两条注视实现路线（原厂 GD vs 本模块 FD-Pro）的取舍 | [`zux_gd_vs_island0_verdict_20260917.md`](zux_gd_vs_island0_verdict_20260917.md) |
| AON 会话"看起来死了"其实是误判 | [`aon_session_lost_verdict_20260917.md`](aon_session_lost_verdict_20260917.md) |

---

## 文档清单

### 总汇与坑清单

| 文档 | 内容 |
|---|---|
| [`PROJECT_HISTORY.md`](PROJECT_HISTORY.md) | **项目总汇**（由 25 份历史取证文档归并）。含：崩溃机制最终判决 · 修复方案与实测 · 原厂 ZUX 对照 · 两条 gaze 与 FD 链路 · 时间线 · **已作废结论清单（18 条）** · 硬数据速查（地址 / SHA256 / 分区 / daemon 参数语义）· 备份与回滚 · 归档索引。**只想读一份就读它。** |
| [`PITFALLS.md`](PITFALLS.md) | **排障坑清单**，按设备运维 / 反汇编 / 镜像取证 / AON 运行期分组。每条都曾**静默**给出错误结论，包括若干"看起来最硬、实则无效"的判据。 |

### 方案判决（原厂 vs 本模块）

| 文档 | 内容 |
|---|---|
| [`github_module_vs_native_verdict_20260917.md`](github_module_vs_native_verdict_20260917.md) | 联想原生 `Smart Attention` 与本模块的 12 项维度对照、三条实机 A/B 实验、模块缺陷清单。**结论：原生的"安全"来自走非岛代码路径，模块的"能落地"来自接入 AOSP `AttentionService` 契约。** |
| [`zux_attentive_display_why_not_crash_verdict_20260917.md`](zux_attentive_display_why_not_crash_verdict_20260917.md) | 原厂 ROM 为什么从不触发这条 fault：**原厂产品路径里根本没有该分支的调用者**（不是厂商藏着没做的功能）。 |
| [`zux_gd_vs_island0_verdict_20260917.md`](zux_gd_vs_island0_verdict_20260917.md) | 原厂 GD（Tobii gaze）与本模块 FD-Pro + `is_island=0` 的优劣判决；并证明"抓 `fd_algo_mode_index` 日志才能闭环"是伪命题——该日志只在存在订阅者时才产生，而原厂路径里没有订阅者。 |

### 方法论复盘

| 文档 | 内容 |
|---|---|
| [`aon_session_lost_verdict_20260917.md`](aon_session_lost_verdict_20260917.md) | 一次完整的**自我纠错记录**：曾判定"AON 会话断链不可自愈、只能重启设备"，后被证伪——`cid=-1` 与零事件是 Demand Mode 的正常空闲态。文中保留了被推翻的两条判据及其为何错，供后续避坑。 |

### 固件侧报告

| 文档 | 内容 |
|---|---|
| [`../QSH_AON_FIRMWARE_BUG_REPORT.md`](../QSH_AON_FIRMWARE_BUG_REPORT.md) | 提交给固件 / 平台侧的缺陷报告：island 路径构建期链接缺陷的现象、fault 串与最小复现。 |

---

## 阅读提示

- 本目录文档是**当时的取证快照**，其中被判为"已作废"的结论仍按原样保留（这是刻意的，
  用于说明错误是怎么发生的）。**判断当前有效结论请以 `PROJECT_HISTORY.md` §8 为准。**
- 文档内出现的绝对路径已做脱敏处理（`<backup-root>/`、`<local>/`）；部分命令中的
  `adb` 路径请按本机环境替换。
- 时间戳为 2026-09 的取证记录，设备固件版本见 `PROJECT_HISTORY.md` §2。

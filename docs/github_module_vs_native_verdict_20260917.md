# GitHub 模块方案 vs 联想原生方案 —— 优劣判决

> ## ✅ 状态更新（v1.8.1）
> 本文 §5 列出的 3 个模块缺陷**均已处理**，本文正文保留当时的原始记录：
> - **缺陷 1（`supervisor.sh` IPC 路径不一致）** → v1.8.1 修复：路径统一到
>   `service.sh` 同一常量（app files 目录），并在两侧加同源注释防再次漂移。
> - **缺陷 2（`attention_ctrl` 发裸 `start`）** → v1.8.0 已修复：改为完整协议
>   `state=start <camIdx> <srv> <mask> <algo> <w> <h> <dps> seq=<uptimeMs>`。
> - **缺陷 3（AON 客户端单占用，紧邻 kill 后重拉必失败）** → v1.8.1 缓解：
>   supervisor 增加 `RESPAWN_MIN_GAP=20 s` 最小重拉间隔 + 单实例锁（toybox 无 `flock`，
>   用 `mkdir` 原子性实现；含陈旧锁回收、pid 未落盘时的 5 s 宽限期）。该硬件约束只能规避不能消除。
>   锁逻辑已抽出在设备 `/data/local/tmp` 做 7 项隔离用例验证（首次获取 / 自持重入 / 陈旧锁回收 /
>   真并发出让 / 持有者退出后可重取 / 竞态宽限 / 超期回收，**7/7 通过**）。
>   ⚠️ 存活判定靠 `/proc/<pid>/cmdline` 含 `supervisor` 串，**因此不要重命名 `supervisor.sh`**。
>
> 另：v1.8.1 将 `is_island` 修复范围从 4 个模型扩展到全部 `nms_*` 模型（见根目录
> [README](../README.md)）。

> 日期：2026-09-17（第 8 轮）
> 设备：Lenovo TB522FU（Y900）／ColorOS 移植 + 本模块 `tb522fu_attention_keepon`
> 取证源：① GitHub `futureharmony/Lenovo-TB522FU-Attention-KeepOn` v1.8.0（本轮克隆，含 APK 源码与探针）
> ② ZUXOS 2.0.13.057 全量包静态取证（前几轮）
> ③ **本轮设备实机 A/B 实验**（新增，见 §4、§6）

---

## 0. 一句话判决

**两条路解决的问题不同：原生那条"安全但没大脑"，模块这条"有大脑但要靠配置保险"。**

- 论**能不能真正实现「注视不息屏」** → **模块完胜**。联想原生在 ZUX 这个 build 上根本没有
  `AttentionService` 实现体（全 ROM 双编码 0 命中），它的 gaze 只服务「AI 眼动多窗」；
  是模块把「检测 → 判定 → `PowerManager.userActivity` 续屏」端到端接通的。
- 论**结构安全性与长期免维护** → **原生路线更优**。原生的 GazeDetect 走 OEM1/Tobii，
  代码在**非岛段 ph[27]**、**不经 `eai_execute`**、**没有 `is_island` 键** —— 它天然不碰那堵墙；
  模块走的是 NMS 家族 FD-Pro，安全性建立在 `is_island=0` 这层**配置覆盖**上。
- 论**精度上限与能力** → **原生更高**。原生交付 `gaze_info{x,y}` 坐标流（能回答"在看哪里"），
  模块交付 bbox + `has_gaze` 布尔（只能回答"有没有人在看"）。
- 论**可控性/可维护性/可移植性** → **模块完胜**。原生是闭源私有件（SmartVision + Tobii 模型 +
  联想产品逻辑 + `INJECT_EVENTS` 特权），不可改、不可搬；模块全开源，可在设备上直接迭代。

**结论**：在"不改固件、不改 ROM"的现实约束下，**模块是唯一能落地的方案**，因此就本项目目标而言
**模块更优**；但**最理想的形态是两者优点的合成** —— 拿模块的骨架（AOSP 契约 + Demand Mode + 自愈），
配原生那条结构性非岛的 GD 通道。§7 给了落地路径，§5 给了模块当前**实测到的 3 个可修缺陷**。

---

## 1. 两个方案的准确画像

### 1.1 联想原生：`Smart Attention`（ZUX 原厂）

| 项 | 内容 |
|---|---|
| 用户可见名 | `Smart Attention` / `智能常亮` / AOSP 遗留的「屏幕感知」文案（`ZuiSettings` + `ZuiPermissionController` 多语言） |
| 实现体 | **`SmartVision`（`com.zui.camera.ex.av`）** —— ZUX system 分区**唯一**直接调 AON 的 APK |
| 订阅的 AON 服务 | **`GazeDetect`(4)** + **`HandDetect`(3)**；全 dex 14 754 类中 `AONManager.subscrible(int)` 只有这两个调用点，`subscrible(0/1)`（FD/FD-Pro）**零次** |
| 算法家族 | **OEM1 / Tobii GazeNet**（`GazeNetInference::process` / `tobii_nexus_process_frame`） |
| 代码落点 | **ph[27]（0xb2200000，3.6 MB 非岛段）**；岛块 ph32–36 对 `tobii/gaze/oem1` **字符串级 0 命中** |
| 经 `eai_execute`？ | **否**（全固件 5 个调用者 5/5 都是 NMS 家族） |
| 受 `is_island` 控制？ | **否**（`tuning_params` 只有 6 个 NMS 子节，**没有 `nms_gaze`**） |
| 交付内容 | **`gaze_info{x,y}` 坐标流**（`gazeInfo valid:%d x:%f, y:%f`）+ `cal_status` |
| "是否注视"在哪判定 | **AP 侧**：`EyeTrackingExternalAction.process(x,y)` → `StableGazeDetector.processGazePoint` → `onStableGazeConfirmed` |
| 续屏原语 | `ScreenManager.simulateUserActivityReflectively()`（反射调 `PowerManager.userActivity()`）+ `keepScreenOnWithDimWakeLock()` |
| 产品功能 | **「AI 眼动多窗」**（分屏注视切焦点）+ 隔空手势（截屏/点赞/比心/保屏） |
| ⚠️ 缺口 | **AOSP `AttentionService` 在 ZUX 里没有实现体**（全 ROM 8 分区、UTF-16+UTF-8 双编码 0 命中）；`ATTENTIVE_DISPLAY` 枚举只有定义没有读取者 ⇒ 设置里的开关在 framework 层**不可用** |

### 1.2 GitHub 模块：`Lenovo-TB522FU-Attention-KeepOn` v1.8.0

| 项 | 内容 |
|---|---|
| 形态 | Magisk/KernelSU 模块；无头 APK（`:attention` 进程）+ native daemon + WebUI + CLI |
| 框架接入 | **AOSP 标准契约**：`settings put secure attention_service_component <pkg>/AONAttentionService` + `adaptive_sleep 1` ⇒ **无 hook、无 priv-app 依赖** |
| 感知硬件 | Camera 3 / OG0VE AON（CSIPHY 4），QSH 通道直通 ADSP |
| 服务/算法 | `FaceDetectPro`(1)，**NMS / FD_ENPU 家族**，mode[2] **480×360**（非岛 capable） |
| 安全性来源 | **配置性**：`is_island=0` 幂等写 persist registry（`post-fs-data.sh` + `service.sh` 二次漂移自检） |
| 运行模式 | **Demand Mode**：屏亮时流不注册；框架灭屏超时回调 `onCheckAttention` 的 4 s 预算内拉流，1~2 帧即停 |
| 防护 | supervisor 15 s 看护 + **ADSP 熔断**（15 s ≥3 crash → 杀客户端 + 10 min 冷静期）+ **bootloop guard**（连续 3 次开机失败自禁并还原设置） |
| 实测能耗 | 熄屏 0.78%/h；AON 应用整夜 **0.167 mAh**；ADSP 0 crash（2026-09-17 整夜基线） |

> 模块 README 自己对 `is_island` 的定位说得很准：*"岛路径弃用是**修复结论**，不是设计取舍"*。
> 这句是对的 —— 见 §3.2 的对照。

---

## 2. 维度对照表（12 项）

| # | 维度 | 联想原生（SmartVision / GD） | GitHub 模块（FD-Pro + `is_island=0`） | 胜 |
|---|---|---|---|---|
| 1 | 能否实现「注视不息屏」 | ❌ ZUX 无 `AttentionService` 实现体 | ✅ 端到端打通（框架契约 + 续屏原语） | **模块** |
| 2 | 抗崩溃的结构性 | ✅ 代码在非岛段，**无配置依赖** | ⚠️ 靠 `is_island=0` 覆盖层，**配置性** | **原生** |
| 3 | 失败条件 | 无（不进那条路径） | OTA／恢复出厂／重刷 persist ⇒ 覆盖失效 ⇒ 复发 | **原生** |
| 4 | 能力上限 | ✅ gaze 坐标（x,y）+ 标定 + 稳定注视判定 | ⚠️ bbox + `has_gaze` 布尔 | **原生** |
| 5 | 可做的功能 | 眼动多窗、注视不息屏、手势（可扩展） | 只有"有人看→续屏" | **原生** |
| 6 | 分辨率自由度 | 由 Tobii 模型固定 | ✅ 实测 mode 可自选（0/1/2 均可用，崩只由 `is_island` 决定） | **模块** |
| 7 | 待机功耗 | 眼动会话期间常驻出坐标 | ✅ Demand Mode，平时零功耗（0.167 mAh/夜实测） | **模块** |
| 8 | 可改动/可定制 | ❌ 闭源私有件，改不了 | ✅ 全开源（APK 源码 + daemon + WebUI 都在仓库） | **模块** |
| 9 | 可移植性 | ❌ 绑 SmartVision + 联想产品逻辑 + `INJECT_EVENTS` | ✅ 只依赖 AOSP 契约 + vendor 固件 | **模块** |
| 10 | 对底包其他方案的冲突 | 无 | 需主动净化底包 `lwky_oplus_aon`（会争抢 `attention_service_component` 绑定） | **原生** |
| 11 | 工程健壮性（当前实现） | ✅ ROM 内建，系统服务生命周期管理 | ⚠️ 实测 3 个缺陷（§5），其中 1 个 v1.8.0 仍在 | **原生** |
| 12 | 设置项联动 | ❌ framework 层不可用（无实现体） | ✅ 与系统设置「注视时不熄屏」双向联动 | **模块** |

**计分**：模块 6 项、原生 6 项 —— 平手。但**权重不同**：第 1 项（能不能用）是**门槛项**，
第 2/3 项（会不会崩）是**风险项**，第 4/5 项是**上限项**。门槛不过，后面都没意义。

---

## 3. 为什么"安全性"这一项原生更强 —— 以及它强在哪里

### 3.1 崩与不崩的唯一自变量是 `is_island`，不是算法家族

前几轮已定案（并有本轮新证据加固）：

```
崩溃 = 后处理岛分支 → 跨执行域取指（岛块 436/436 出站 call 100% 落 ph[27]）→ ADSP fault
       ⇒ 自持 SSR（~5 s/次）→ pmic_glink PDR down → USB 掉线
触发键 = nms_{eod, fd_qqvga, fd_qvga, qrcode}.is_island = 1
```

**所以"原生更安全"不是因为 GD 算法更高级，而是因为 GD 的代码压根不在岛里。**
把 GD 搬进岛，它会撞同一堵墙。

### 3.2 原生的"安全"是**便宜**换来的，"能落地"是模块**多付代价**换来的

| | 原生 GD | 模块 FD-Pro |
|---|---|---|
| 怎么拿到安全 | 什么都不用做（结构性） | 每次开机幂等写 persist 覆盖层 + 漂移自检 |
| 为什么还得走 NMS | 不需要 | **GAZE 那条路我们拿不到**（见 §7 探针结果） |
| 代价 | 功能没落地（无消费者） | 多一层配置依赖 + 一个可修的控制面缺陷 |

> 一句话：**原生把"安全"做成了免费项，代价是"不能用"；模块把"能用"做成了现实，代价是要养一层配置。**

---

## 4. 本轮实机实验（新增硬证据）

### 4.1 实验一：命令通道 A/B（决定性）

同一 daemon 二进制、同一参数 `0 1 15 2 480 360 3`、同一时刻，**只改命令行的写法**：

| 条件 | 写入 `aon_cmd` 的内容 | 结果 |
|---|---|---|
| A | 裸 `start`（**与文件既有内容相同**） | ❌ `EVT` 行 = 0，daemon 日志仅 95 B |
| B | `state=start 0 1 15 2 480 360 3 seq=7`（内容变化） | ✅ **94 个 `EVT` 行**，`st=1 rep=1 cid=-5476376651825832944` |
| C | 裸 `start`（**内容确实发生变化**时） | ✅ 事件流启动（累计计数 337） |

**⇒ AON 硬件、固件、传感器全部健康**；命令通道是"**内容不变即忽略**"语义（很可能按 `seq` 去重）。

### 4.2 实验二：纠正第 7 轮的误判（重要）

第 7 轮的判决是"**AON 会话断链不可自愈，只有重启设备可恢复**"。本轮证伪：

| 第 7 轮的观测 | 本轮定性 |
|---|---|
| "会话断链期：`cid=-1` + 无 `EVT` 行" | **这是 Demand Mode 的正常空闲态** —— `st=0 rep=0 cid=-1`，流未注册。不是断链 |
| "重启后事件文件每 15 s +100~130 B ⇒ 恢复 ✅" | **那是 `HBT` 心跳行**（`HBT <ts> st=0 rep=0 cid=-1`，39 B × 3 行/15 s ≈ 117 B）。**不是事件**。真事件流是 `EVT b=1 n=7 …`，健康态 8 帧/s ⇒ 15 s 该涨 ~3.7 KB |
| "重启是唯一手段" | ✅ 已证伪：**杀掉 daemon 重拉 + 正确命令，12 秒内即出 94 个事件**，全程零重启、零崩溃 |

**⇒ 正确表述**：AON 会话**可重建**；重启只是"最省事的重建方式"，不是唯一方式。
第 7 轮把"空闲态"读成了"故障态"，又把"心跳增长"读成了"事件恢复"——两次误判方向相反、恰好抵消，
所以表面上自洽。已在 `aon_session_lost_verdict_20260917.md` 顶部加更正 banner。

### 4.3 实验三：GD 通道能不能被我们的 daemon 驱动（探针）

把 daemon 的 `srv` 参数换成 **4（GazeDetect）**：

```
REG ok=0  err=transact_-38          ← -38 = ENOSYS（注册时 HAL 返回"未实现"）
```

⇒ **GD 不是配置级可切换的**。想在模块里用原生那条结构性安全的路，需要 daemon/HAL 侧**新增代码**
（GD 分支的注册负载与 FD 分支不同：`[present=0]`、无 mask/algo/w/h）。
这条留在 §7 作为"未来最优解"。

---

## 5. 模块当前的 3 个真实缺陷（本轮实测得出，均可修）

### 缺陷 1（v1.8.0 仍在）：`supervisor.sh` 的 IPC 路径与其余组件不一致

| 组件 | cmd/evt 路径 |
|---|---|
| `service.sh` §100-102 | `/data/data/futureharmony.tb522fu.aon/files/` ✅ |
| `system/bin/attention_ctrl` §20-22 | `/data/data/futureharmony.tb522fu.aon/files/` ✅ |
| **`supervisor.sh` §64-66** | **`/data/adb/tb522fu_attention/`** ❌ |

`supervisor.sh` 从 v1.7 到 **v1.8.0 一字未改**，而同版 `service.sh`/`attention_ctrl` 都已统一到 app 路径
⇒ **它是唯一的不一致点**。

**后果**：daemon 一旦死亡，supervisor 用错路径重拉它 ⇒ 控制面永久失联（app 把命令写进 app 路径，
daemon 读的是 `/data/adb` 路径），表现为"功能开着但完全不工作"，直到下次开机。

**修法（一行）**：

```diff
-                /data/adb/tb522fu_attention/aon_cmd /data/adb/tb522fu_attention/aon_evt \
+                /data/data/futureharmony.tb522fu.aon/files/aon_cmd \
+                /data/data/futureharmony.tb522fu.aon/files/aon_evt \
```

### 缺陷 2：v1.7 的 `attention_ctrl` 发裸 `start`，与 daemon 的"内容变化才处理"语义打不出配合

设备装的 `attention_ctrl`（v1.7）在 §61/§114/§170/§181 处发的是裸 `"start"`；
v1.8.0 已改成 `state=start <9 参数> seq=N`。配合 §4.1 的"内容不变即忽略"语义，
裸 `start` 在重复调用时会**静默失效** ⇒ `attention_ctrl test` 长期返回
`ABSENT (未检测到人脸)`（这正是第 7 轮那 5 次"3 秒一次的 ABSENT"的真因）。

### 缺陷 3：AON 客户端是**单占用**资源，紧邻上一个已注册客户端被杀后立即重拉会失败

观测：在上一个 daemon **有活跃注册**（`cid` 有效、正流事件）时把它 kill，3 s 内重拉新 daemon + 发命令
⇒ `EVT = 0`（拿不到传感器）；而上一个 daemon **从未注册**时，同样操作立刻成功。
**机制属推测**（释放延迟/未清理的 sensor 持有），但**现象已两次复现**。
缓解：重拉前留足间隔（≥20 s），或让 supervisor 在重拉失败后做退避重试。

> 另外两条低危项（第 7 轮已记录，仍在）：`supervisor.sh` 无单实例保护（本机实测能冒出 2~3 份）；
> `bootloop guard` 的 `disable` 落盘后需要用户在管理器里重新启用。

---

## 6. 对照一个常被误读的点：`cid=-1` 不是故障

```
HBT 1789632220 st=0 rep=0 cid=-1      ← 空闲（Demand Mode，未注册）
HBT 1789632391 st=1 rep=1 cid=-5476376651825832944   ← 已注册、正在出流
```

`cid` 是 **client handle**；`-1` 表示"当前没有注册的流"。Demand Mode 的设计就是**平时不注册**。
**判活的正确判据**（本轮更新）：

| 判据 | 可用性 |
|---|---|
| `EVT b=1 …` 行数 / 事件文件 size **增量** | ✅ 最可靠（注意：只数 `EVT` 行，**别把 `HBT` 心跳当成事件**） |
| daemon 日志里有 `[AON] cb transact code=1` | ✅ 可靠（需显式重定向 stdout 到文件） |
| `cid != -1` / `st=1` | ⚠️ 只在"正被请求"时有意义；空闲态为 `-1` 属正常 |
| daemon CPU 累积 | ❌ 已证伪（空闲与工作都是 ~2 ticks/25 s） |
| `attention_ctrl test` 的返回值 | ❌ 不可靠（见缺陷 2） |
| `dumpsys vendor.qti.hardware.camera.aon.IAONService/default` 非空 | ❌ 该服务是 lazy 的，返回空 ≠ 服务不存在 |

---

## 7. 落地建议（按性价比排序）

| 优先级 | 动作 | 理由 |
|---|---|---|
| **P0** | 修 `supervisor.sh` 的 IPC 路径（一行） | 这是"功能静默失效"的唯一必然来源，改动最小、收益最大 |
| **P0** | 在 `supervisor.sh` 加单实例锁（`exec 9>…; flock -n 9 \|\| exit`） | 本机实测会出现 2~3 份 |
| **P1** | 统一命令语义：让 v1.7 的 `attention_ctrl` 也发完整协议（含 `seq` 自增） | 否则 CLI 的 `test`/`on` 长期假阴性 |
| **P1** | 重拉退避：daemon 被 kill 后 ≥20 s 再重拉；失败则指数退避 | 规避 §5 缺陷 3 |
| **P2** | 评估 **GD（`srv=4`）通道**：让 daemon 支持 GD 分支注册 | 若做成 = **结构性安全 + 零配置依赖 + 拿到 gaze 坐标**，即两者优点的合成；当前探针返回 `transact_-38`，需代码工作 |
| **P2** | 保留 `is_island=0` 作为**兜底**，即使将来走 GD 也不回滚 | 成本为零，防"NMS 家族被别的客户端（如 CamX）拉动" |

**关于「要不要干脆照搬原生」**：不可行。原生方案 = `SmartVision.apk`（闭源）+ Tobii 模型 +
联想产品逻辑 + `INJECT_EVENTS` 特权 + 一个 ZUX 里都不存在的 attention 消费者；
把它搬过来等于重写一个 APK，且仍要自己实现 `AttentionService` —— 那正是模块现在做的事。

---

## 8. 设备状态（本轮收尾）

| 项 | 值 |
|---|---|
| daemon | ✅ 运行中，argv 用 **app 路径**（= `service.sh` 的做法）：`--daemon /data/data/futureharmony.tb522fu.aon/files/aon_{cmd,evt} 0 1 15 2 480 360 3` |
| supervisor | ✅ 1 份（实验期间曾出现 2 份，已收敛） |
| app | ✅ `futureharmony.tb522fu.aon:attention` 运行中 |
| 功能开关 | `config.json` `enabled:true`；`attention_service_component` 已指向本模块；`adaptive_sleep=1` |
| `is_island` 生效值 | ✅ 六个子节全部 `data":"0"`（直读 persist registry 逐个确认） |
| AON 会话 | ✅ 空闲（Demand Mode 正常态，`st=0 cid=-1`），按需即可拉流（§4.1 已验证 12 s 内出流） |
| ADSP 崩溃 | `handling crash = 0`、`PD_ERR = 0`、`qsh_process = 0` |
| SELinux / uptime | `Enforcing` / 1 627 s（**本轮全程未重启**） |
| 临时文件 | 已清理（`/data/local/tmp` 下本轮脚本与日志已删）；`camxoverridesettings.txt` 保持不存在 |

> 收尾动作：kill 掉实验用 daemon 后，直接跑模块自身的 `service.sh`（幂等）恢复，
> 与开机路径一致 —— 因此当前状态等价于"刚开机"。

---

## 9. 一页速查

```
联想原生 (SmartVision → GazeDetect(4)/Tobii)
  ✅ 结构性安全（ph[27] 非岛段、无 is_island 键、不经 eai_execute）
  ✅ gaze 坐标流（能回答"在看哪里"）
  ❌ ZUX 无 AttentionService 实现体 ⇒ 「注视不息屏」在 ZUX 上落不了地
  ❌ 闭源私有件，不可改、不可移植

GitHub 模块 (FD-Pro mode[2] 480×360 + is_island=0)
  ✅ 唯一能真正实现「注视不息屏」的方案（AOSP 契约 + Demand Mode + 三重防护）
  ✅ 零待机功耗（0.167 mAh/夜）、全开源、可移植
  ⚠️ 安全性 = 配置性（persist 覆盖层，OTA/恢复出厂会失效）
  ⚠️ 只到"有人在看"级别（无 gaze 坐标）
  ⚠️ 实测 3 个可修缺陷（supervisor 路径 / 命令语义 / 单占用接管）

结论：现在用模块；将来若要把 daemon 的 GD 分支做出来，
      就能拿到"原生级结构安全 + 原生级精度 + 模块级可控性"的组合。
```

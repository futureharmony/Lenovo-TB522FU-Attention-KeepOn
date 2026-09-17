# ZUX「注视不息屏」方式 vs 我们 island=0 legacy 模式 —— 优劣判决 + 岛分支缺口闭环

> 日期：2026-09-17（第 6 轮）
> 设备：Lenovo TB522FU（Y900）／当前跑 ColorOS 移植 + 本模块 `tb522fu_attention_keepon` v1.7-adspfix
> 取证源：ZUXOS 2.0.13.057 全量包 + 设备实件 + 实机运行期日志 + 本模块源码
> 新增工具：无（复用 `efetch.py` / `elfstr.py` / `strref.py`）

---

## 0. 两个结论先行

**问题 1（哪个更优）**：
- **论「不崩的确定性」：ZUI 的 GD 方式更优** —— 它是**结构性**远离岛（算法代码在非岛段 ph[27]，全固件 5 个 `eai_execute` 调用者里没有它），零配置依赖。
- **论「能真正实现注视不息屏」：我们的 island=0 模式更优** —— ZUX build 里 GD **没有任何 attention 消费者**（只服务「AI 眼动多窗」），它是"有眼睛没大脑"；我们端到端打通了「检测 → 判定 → `PowerManager.userActivity` 续屏」。
- **我们的短板**：安全性是**配置性**的（依赖 persist 覆盖层里的 `is_island=0` 持续生效），一旦 OTA／恢复出厂／重刷 persist 就会回归崩溃。GD 没有这个风险，因为它压根不进那条路径。

**问题 2（缺口闭环）**：
- **原假设不成立** —— "抓 `fd_algo_mode_index` 就能解释原厂为何不崩"是**伪命题**。
- 实测：`AONCam FD subscribe success` 在本设备 **6 个窗口内零命中** ⇒ **CamX 从不订阅 FD-Pro**。
- 上轮"FD Pro 的真实消费者是 CamX"**被本轮证伪**：那 3798 次事件是**我们自己的 `aon_daemon.bin`** 产生的（`FaceDetectPro event received` 的格式串只在 **AON HAL 服务端**里，是它向外部客户端回调时打的）。
- `fd_algo_mode_index` 由客户端参数直接给定，且**与"是否执行岛"解耦**：我们的 daemon 在 `algo=0`（= mode[0]，`isIslandCapable=1`）参数下也照常收到 FD-Pro 事件、**零崩溃**。
- ⇒ **决定崩与不崩的唯一自变量是 `is_island` 这个运行期开关，从来不是 mode 选择。** 原厂不崩，是因为**它的产品路径里根本没有 FD-Pro 订阅者**。

---

## 1. 对照表：两条注视实现路线

| 维度 | ZUI／GD（=「AI 眼动多窗」用的那条） | 我们：FD-Pro + `is_island=0`（legacy） |
|---|---|---|
| AON 服务类型 | `GazeDetect`(4) + `HandDetect`(3) | **`FaceDetectPro`(1)** |
| 服务端标识 | `QSEEAONSrvGD` | `QSEEAONSrvFDPRO` |
| 算法家族 | **OEM1 / Tobii GazeNet** | **NMS / FD_ENPU**（`fdEngine=1`） |
| 代码落点 | **ph[27] 非岛段**（3.6 MB XWR）——岛块 ph32–36 对 `tobii/gaze/oem1` **字符串级 0 命中** | 代码在岛块能力范围内，但**运行期被 `is_island=0` 约束走非岛后处理** |
| 经过 `eai_execute`(@0xb206ff4c)？ | **否**（结构性：全固件 5 个调用者 5/5 都是 NMS 家族） | **否**（配置性：`is_island=0` ⇒ 走 `client_algo_fd_enpu_post_proc_nms`） |
| 安全性来源 | **结构性**（不进岛代码域） | **配置性**（persist overlay 的 6 个 `is_island=0`） |
| 失效条件 | 无 | OTA／恢复出厂／重刷 persist → `is_island=1` → 复发自持 SSR |
| 交付内容 | `gaze_info{x,y}` **坐标流** | 人脸 bbox／角度 + `has_gaze` 布尔 |
| 「是否注视」在哪判定 | **AP 侧**：`StableGazeDetector.processGazePoint` → `IEyeTrackingCallback.onStablePosition` | **AP 侧**（我们同样是 AP 判定）：daemon 收 `EVT FDPro` → app 判 → `service call power 14`（= `PowerManager.userActivity`） |
| 续屏原语 | `ScreenManager.simulateUserActivityReflectively()`（反射调 `userActivity`）+ dim wake lock | `service call power 14 i32 0 i64 <uptime_ms>`（同一原语） |
| 运行形态 | 常驻（只要眼动功能开着就持续出坐标） | **按需／Demand Mode**（设计上在熄屏超时窗口内才建立 AON 通道） |
| 依赖厂商私有件 | **需要**（`SmartVision.apk` 私有 + Tobii 模型 + 联想产品逻辑） | **不需要**（标准 AON HAL + 标准 `AttentionService` 组件名） |
| 在 ZUX 上能实现「注视不息屏」吗 | **不能**（全 ROM 无 `AttentionService` 实现体；GD 的唯一消费者是「眼动多窗」） | **能**（这就是它的设计目标） |
| 是否需要一个可写配置层 | 否 | **是**（模块 `post-fs-data.sh` 幂等写 persist registry overlay） |

### 1.1 判据展开

1. **崩不崩，看的是"进不进岛"，与"用哪家算法"无关。**
   GD 之所以天然安全，是因为它的代码落在**非岛段** —— 不是因为它更"高级"。
   若把 GD 也搬到岛内执行，它会撞同一堵墙（岛块 436/436 出站 call 100% 落 ph[27]）。

2. **功能上，ZUX 那条路其实缺了"大脑"。**
   上轮已定案：ZUX 里 `AttentionService` 无实现体、`ATTENTIVE_DISPLAY` 枚举只有定义没有读取者、
   `AttentiveDisplay_On/Off` 只是联想小天 AI 的跨设备协议。
   ⇒ ZUX 的 GD 输出坐标给「眼动多窗」，**并没有谁拿它去做"注视→不息屏"**。

3. **我们的方案是"唯一能落地"的解，代价是安全性可失效。**
   在不改固件（PIL/TZ 签名死结）的前提下，把 NMS 家族从岛内搬到岛外，只能靠 registry 覆盖层。
   好在它可以幂等固化（模块 `post-fs-data.sh` 每次开机重写，`island_fix.log` 留痕），
   并且有 supervisor 兜底（`enabled=false` 或 ADSP 崩溃风暴 ≥3 时熔断）。

4. **一个意外的灵活性优势。**
   既然"崩不崩"只由 `is_island` 决定（见 §3.4），我们的客户端就**可以自由挑精度最高的 mode**
   （480×360 而非 160×120），而不必为了避险牺牲分辨率。GD 路线没有这种自由度
   （它的分辨率由 Tobii 模型固定）。

### 1.2 推荐

| 目标 | 推荐 |
|---|---|
| 只求"设备稳定不崩" | 关掉 AON 订阅（`enabled=false`），或保持 `is_island=0`。两者都安全；前者零功耗。 |
| 求"注视不息屏能真用" | 维持现状：`is_island=0` + FD-Pro mode[2]（480×360）。这是当前唯一可落地组合。 |
| 求"长期免维护" | 把 `is_island` 值的**开机自检**做进模块（已做）；另建议在模块里加一条"若发现任一 `is_island != 0` 则告警"的巡检。 |
| 若将来能改固件 | 最理想是"结构性方案"：让 NMS 家族也落到非岛段（等价于把 GD 的做法复制过来），从此不需要配置层。 |

---

## 2. 缺口闭环：`AONCam FD subscribe success` 为什么抓不到

### 2.1 这条日志的归属（静态）

| 格式串 | 所在库 | 层级 |
|---|---|---|
| `AONCam FD subscribe success serviceType = %s fd_algo = %d **fd_algo_mode_index = %u** event_mask = 0x%x deliverMode = %d deliverPeriod = %u detections_per_delivery = %u` | **`com.qti.qseeaon.so`** | CamX **客户端**库，`[CORE_CFG]` |
| `Invalid client algoModeIdx: %d for %s, number of supportedFdAlgoMode: %d` | 同上 | ⇒ **index 由上层 client 传入** |
| `%s FD algo mode[%u] imgWidth = %u, imgHeight = %u, isIslandCapable = %d fdEngine = %u …` | `vendor.qti.hardware.camera.aon-service-impl.so` | **AON HAL 服务端** |
| `Get %s algo mode[%d] …`（带 `Get `） | `com.qti.qseeaon.so` | CamX 客户端版 |
| `FaceDetectPro event received!! EvtTypeMask: 0x%x` | **只在 `vendor.qti.hardware.camera.aon-service-impl.so`** | HAL 服务端**回调客户端**时打印 |

> 实机打印的是 `QSEEAONSrvFDPRO FD algo mode[0] …`（**无 `Get ` 前缀**）⇒ 来自 **HAL 服务端**。
> 这一点同时**证伪了上轮"CamX 在消费 FD-Pro"**的推论。

### 2.2 运行期穷举（本设备，`logCoreCfgMask=0xFFFFFFFF` 已验证生效）

| 窗口 | 触发动作 | `AONCam FD subscribe` 命中 | `FaceDetectPro event` 命中 |
|---|---|---|---|
| ① | provider 冷启（相机打开） | 0 | 0 |
| ② | 打开相机 `logicalCameraId=1`（前置） | 0 | 0 |
| ③ | 打开相机 + 持续 25 s | 0 | 0 |
| ④ | 杀掉 daemon 让 supervisor 重拉 | 0 | 0 |
| ⑤ | 再杀再拉（第 2、3 次） | 0 | 0 |
| ⑥ | `attention_ctrl test`（发 `start` 给 daemon） | 0 | 0 |

⇒ **CamX 侧（`com.qti.qseeaon.so`）的 FD 订阅代码在本设备从未执行**。
唯一加载该库的进程 = camera provider（`/proc/*/maps` 只有一个命中），
而它即使在相机打开、AON 通道被请求的情况下也不发 FD 订阅。

### 2.3 那上轮那 3798 次事件是谁的？—— 是我们自己的 daemon

- 本模块 `xon_daemon.bin` 的输出文件 `daemon.out`（605 MB，持续写到今天 14:35）
  里 **满屏都是** `[AON] EVT FDPro n=7 vals=10,1,16,0,0,0,1` + `[AON] cb transact code=1`，
  结尾是 9 次 `[AON] IAONService binder died!`（= 我上轮 kill provider 的时刻）。
- `daemon.bin` 的字符串自证身份：`ACameraManager_openCamera` / `[AON] warmup open camera3 cs=%d dev=%p` /
  `vendor.qti.hardware.camera.aon.IAONService` / `[AON] EVT FDPro n=%d vals=%s`。
  ⇒ 它是**外部 AON 客户端**：打开 camera3（OG0VE）做 warmup → 连 `IAONService` → 订阅 FD-Pro → 收事件。
- HAL 服务端向它回调时打的正是 `FaceDetectPro event received!!`（tag 显示为 `ChiX`，
  因为 AON HAL 服务是 in-process 跑在 camera provider 内）。

**⇒ 上轮结论「FD Pro 的真实消费者是相机 provider 内的 CamX，不是 APK」应更正为：
真实消费者是本模块的 native daemon（APK 侧组件），CamX 只是被借了日志 tag。**

### 2.4 `fd_algo_mode_index` 到底取什么值 —— 已由 daemon 参数直接确定

`aon_daemon.bin` 的用法串：

```
usage: --daemon <cmd> <evt> <camIdx> <srv> <mask> <algo> <w> <h> <dps>
```

`(w,h)` 与固件上报的能力表**一一对应**：

| `algo` | `(w,h)` | 对应固件 mode | `isIslandCapable` |
|---|---|---|---|
| 0 | 160×120 | mode[0] | **1**（岛 capable） |
| 1 | 320×240 | mode[1] | 0 |
| **2** | **480×360** | **mode[2]** | **0** |

实机两套参数：

| 来源 | 命令行 | 含义 |
|---|---|---|
| `service.sh` / `supervisor.sh`（现行 v1.7） | `… 0 1 15 **2 480 360** 3` | `srv=1`(FaceDetectPro)、`algo=2` = **mode[2]（非岛 capable）** |
| 今天 14:35 前在跑的旧进程 | `… 0 1 15 **0 160 120** 3` | `algo=0` = **mode[0]（isIslandCapable=1）** |

### 2.5 决定性反证：index 与"是否执行岛"无关

旧进程运行在 `algo=0`（= island-capable 的 mode[0]）+ `is_island` 已全 0 的条件下，
`daemon.out` 里仍是**海量** `EVT FDPro` 事件（今晚 14:35 前持续产生），
且同期 **`PD_ERR = 0`、`handling crash = 0`**。

⇒ **"选了岛 capable 的 mode" 并不会崩；崩只由 `is_island=1` 引起。**
这同时说明：
- `is_island=0` 的 legacy 模式下，**FD-Pro 功能完全可用**（事件照出、精度可自选）；
- 固件上报的 `isIslandCapable`（能力位）与配置键 `is_island`（运行期开关）是**两个独立的东西**
  （实测：qqvga 的 `is_island` 已改 0，HAL 仍报 mode[0].isIslandCapable=1）。

### 2.6 生效值核对（设备物证）

`/mnt/vendor/persist/sensors/cam_registry_dump.txt` —— 六个 NMS 子节**全部** `is_island: 0`：

```
NMS_PARAMS_FOR_FD_QQVGA.fd.is_island : 0     (is_used=1)
NMS_PARAMS_FOR_FD_QVGA.fd.is_island  : 0     (is_used=1)
NMS_PARAMS_FOR_FD_360P.fd.is_island  : 0     (is_used=1)   ← 我们用的就是这一档
NMS_PARAMS_FOR_QR_CODE.qr.is_island  : 0     (is_used=1)
NMS_PARAMS_FOR_EOD.eod.is_island     : 0     (is_used=1)
NMS_PARAMS_FOR_HD.hd.is_island       : 0     (is_used=1)
```

### 2.7 代码同源核对（保证上述结论可外推到原厂）

| 库 | ZUX `super_7.img` | 设备 | 结论 |
|---|---|---|---|
| `camera.qcom.so`（8.8 MB，含 `CamX::CSSService::StartAONCameraUsecase` / `AONFDMIPI` / `AONFD`） | `1861fe084b516afc…` | `1861fe084b516afc…` | **SAME** |
| `com.qti.chi.override.so`（6.6 MB，含 `AONCamera` / `FrontAONCam_LogicalImpl`） | `a26351bafc2b2fcb…` | `a26351bafc2b2fcb…` | **SAME** |
| `com.qti.qseeaon.so`（FD 订阅客户端） | 同一份（前轮已证） | 同一份 | **SAME** |

⇒ 设备上"CamX 不订阅 FD-Pro"的这一实证，可**直接外推到原厂 ZUX**。

---

## 3. 闭环结论

> **原厂 ZUX 之所以从不执行 FD 的岛分支，不是因为它"聪明地选了非岛 mode"，
> 而是因为它的产品路径里根本没有 FD-Pro 订阅者。**

三条腿支撑：

1. **AP 侧无订阅者**：全 ROM（14 754 类）中 `AONManager.subscrible(int)` 只有 `GazeDetect(4)` 与
   `HandDetect(3)` 两个调用点；`subscrible(0/1)` = 0 次。
2. **相机栈无订阅者**：唯一持有 AON 客户端库（`com.qti.qseeaon.so`）的 camera provider
   在 6 个运行期窗口里 **零** FD 订阅日志。
3. **我们在设备上看到的 FD-Pro 流量，来源是本模块自己的 daemon**，与原厂行为无关。

因此：
- **"抓 `fd_algo_mode_index` 才能解释原厂为何不崩" 是伪命题** ——
  index 只在"存在订阅者"的前提下才有值，而原厂没有订阅者 ⇒ 该日志在原厂语境里永不产生。
- **真正的自变量是 `is_island`**。七层全同 + 同源代码下，只要客户端订阅了 FD/EOD 且 `is_island=1`，
  就必然跨执行域 fault。原厂逃过一劫，靠的是"没有客户端"，不是"选对了 mode"。

### 3.1 与历史结论的关系

| 旧结论 | 本轮判定 |
|---|---|
| 崩溃机制 = 跨执行域 fault，由 `is_island=1` 触发 | **不变，加强**（新增 index 无关性的反证） |
| "只关 `nms_eod` 不够，一级 stage 是 FD_PRO" | **不变** |
| "ZUX 不崩 = 产品路径走不到该配置" | **不变**（本轮把"走不到"精确到"无订阅者"） |
| "FD Pro 的真实消费者是相机 provider 内的 CamX，不是 APK" | **✗ 作废** —— 消费者是本模块的 native daemon |
| "ZUX 全 ROM 唯一摸 NMS 的是 CamX 的 FD-Pro" | **✗ 作废** —— CamX 不碰 FD-Pro；那是我们自己的模块 |

---

## 4. 设备状态与恢复建议

### 4.1 本轮做过的设备操作（全部已还原）

| 操作 | 目的 | 状态 |
|---|---|---|
| 写 `/data/vendor/camera/camxoverridesettings.txt`（`logCoreCfgMask=0xFFFFFFFF`） | 抓 `[CORE_CFG]` 日志 | **已删除**（`No such file`）；`/vendor/etc/camera/camxoverridesettings.txt` **原文件未动**（仍 `logCoreCfgMask=0x80`） |
| 多次重启 camera provider | 复现订阅窗口 | 已由系统按需恢复（PID 10600） |
| 多次重启 `aon_daemon.bin`、跑过一次 `service.sh` | 尝试复现 AON 订阅 | 已收敛：**1 个** supervisor（15276）+ 1 个 daemon |
| `attention_ctrl on/off/test` | 尝试触发订阅 | 无副作用（只写 config/IPC 文件） |

**设备健康**：`handling crash = 0`、`PD_ERR = 0`、`uptime = 84 230 s`（**未重启**）、SELinux `Enforcing`。

### 4.2 ⚠️ 一个需要用户决策的现状

**AON 通道目前处于断开状态**，`attention_ctrl test` 返回 `ABSENT (未检测到人脸)`，
事件文件是心跳行 `HBT … st=0 rep=0 cid=-1`（**`cid=-1` = 未分配到 client handle**）。

原因链：
1. 我上轮/本轮为抓日志 `killall` 了 camera provider → daemon 报 `IAONService binder died!`（9 次）；
2. 本模块 v1.7 的设计是**只在开机阶段建立 AON 通道**（`module.prop`：
   *"将无法从 userspace 消除的那一次 AON 通道建立崩溃吸收到开机阶段"*），
   运行期 kill 后**不自动重建**；
3. 反复重拉 daemon（supervisor + 我的手动）都无法恢复 ⇒ 需要重新走一次开机流程。

**建议：重启设备**（`adb reboot`）即可恢复。
在此之前，"注视不息屏"处于**安全但不工作**的状态（无崩溃风险，也无检测能力）。

### 4.3 顺手发现的两个维护点

1. **`/data/adb/tb522fu_attention/daemon.out` 已 605 MB**（v1.7 后 daemon 输出已改为 `>/dev/null`，
   该文件不再增长，但历史存量占空间）。建议清理或归档。
2. **`supervisor.sh` 无单实例保护**：跑一次 `service.sh` 就会出现 2–3 个 supervisor 并存
   （本轮实测出现过 3 个）。它们做的是幂等操作（`pidof` 检查 + 重拉），不致命，
   但建议加 `pgrep -f 'sh .*supervisor\.sh'` 自检（注意 `pgrep -f supervisor.sh` 会**自匹配**，
   必须写成 `sh .*supervisor\.sh` 并排除自身）。

---

## 5. 残余空白（更新）

| # | 空白 | 价值 | 状态 |
|---|---|---|---|
| 1 | 固件侧 `CAMERA_FD_QUERY_AVAIL_ALGO_MODES` 的处理函数未定位（`isIslandCapable` 由什么算出） | 中 —— 搞清"能力位"的来源，才能解释为何改 `is_island` 不改它 | 未动 |
| 2 | `com.qti.qseeaon.so` 里 FD 订阅函数的**调用者**未静态定位 | 低 —— 运行期已证无人调用（6 窗口零命中） | 可放弃 |
| 3 | `LenovoXiaoTian` 的 `changeAttentiveDisplay` 发给谁 | 低 | 未动 |
| 4 | **恢复 AON 通道后验证 mode[2] 的实际检测效果**（480×360 vs 旧 160×120 的检出率/延迟） | **高** —— 直接关系体验 | 待重启设备后做 |

---

## 附：本轮新增/更新的工具与事实速查

**daemon 参数语义**（`aon_daemon.bin`）：
```
usage: --daemon <cmd> <evt> <camIdx> <srv> <mask> <algo> <w> <h> <dps>
srv  : AONServiceType（1 = FaceDetectPro）
algo : fd_algo_mode_index（0=160×120 岛capable / 1=320×240 / 2=480×360 非岛）
```

**AON 服务类型枚举**（来自 SmartVision `AONServiceType`）：
`0=FaceDetect ｜ 1=FaceDetectPro ｜ 2=QRCode ｜ 3=HandDetect ｜ 4=GazeDetect`

**固件上报的 FD-Pro 能力表**（本机，`QSEEAONSrvFDPRO`）：
```
mode[0] 160x120  isIslandCapable=1  fdEngine=1  mask=0xf  (30 fps)
mode[1] 320x240  isIslandCapable=0  fdEngine=1  mask=0xf  (30 fps)
mode[2] 480x360  isIslandCapable=0  fdEngine=1  mask=0xf  (30 fps)
```
且 **不提供基础版 FaceDetect**（HAL 只报 `HS / FDPRO / QR / HD / GD` 五种）。

**模块控制面**：`/data/adb/tb522fu_attention/attention_ctrl {on|off|toggle|status|test|daemon|log [n]|clear_log}`

# TB522FU 注视不息屏 / ADSP 排障 —— 项目文档总汇

**归并窗口**：2026-09-16 10:28 ~ 2026-09-17 16:10
**归并日期**：2026-09-17
**本文定位**：项目 25 份历史文档的归并总汇。**读本文即可掌握全部历史结论与证据链**；需要一手细节（反汇编片段、完整实验日志、可复现命令）时，见 §附录 D 的主题索引。

**原文位置**：归并自工作区 `aon_rootcause/_archive/` 下的 25 份取证文档（原文不随本仓库分发）。

---

## 0. 阅读指引

### 0.1 当前活跃的 5 份文档（未归档）

| 文档 | 角色 |
|---|---|
| `aon_rootcause/zuxos_native/PITFALLS.md` | **排障坑清单**，活文档，持续追加（22 条） |
| `aon_rootcause/zuxos_native/github_module_vs_native_verdict_20260917.md` | **GitHub 模块 vs 联想原生方案** 优劣判决（最新主交付） |
| `aon_rootcause/zuxos_native/zux_gd_vs_island0_verdict_20260917.md` | **ZUX GD 方式 vs 我们 island=0** 优劣判决 |
| `aon_rootcause/zuxos_native/zux_attentive_display_why_not_crash_verdict_20260917.md` | 原厂为什么"不崩" —— 收口判决 |
| `aon_rootcause/zuxos_native/aon_session_lost_verdict_20260917.md` | AON 会话断链诊断（**部分结论已被后续实验推翻，文首有更正 banner**） |

### 0.2 本文与其他文档的边界

- **本文**：历史结论的**高密度沉淀 + 作废项存档**。
- **PITFALLS.md**：只记**方法论与踩坑**，不记结论。
- **归档原文**：一手证据（字节级对拍、反汇编清单、完整日志、脚本）。

---

## 1. 问题与结论速览（一页）

### 1.1 要解决的问题

ColorOS 移植版 TB522FU 上，"注视不息屏"功能（AON 前置相机做人脸/注视检测，屏幕暗下时判断用户是否在看）要跑起来，但**一旦真正建立 AON 相机流，ADSP 就反复崩溃**，进而 USB 掉线。

### 1.2 最终答案

| 问题 | 答案 |
|---|---|
| 崩溃的直接原因？ | **tuning 配置把 4 个 NMS 模型的 `is_island` 置为 1**，令固件走 EAI island 低功耗路径；该路径在本 build 上是跨执行域调用，**必然 fault** |
| 崩溃的机制？ | 岛代码块（ph32–36）在岛执行域内 `call` 进岛外段 ph[27] 的 `eai_execute_internal`，**callee 页在岛域不可取指** |
| 能修吗？ | **设备端不可修**（构建期链接/属性缺陷，属签名固件）；但**不需要修** —— 把 `is_island` 置 0 退回 legacy 路径即可 |
| 修好了吗？ | ✅ **已修复并上线**。10 个连续冷启动周期 ADSP fault = 0，FDPro 事件 160~360/轮（不降反升） |
| 原厂为什么不崩？ | **联想原厂从未执行过这条路径** —— 它的产品功能是「AI 眼动多窗」（Tobii GD），不是「注视不息屏」（EOD/NMS） |

### 1.3 修法（一行）

```
把 nms_eod / nms_fd_qqvga / nms_fd_qvga / nms_qrcode 的 is_island 全部置 0
```

落地形态：**persist registry 覆盖层**（幂等固化在 KernelSU 模块的 `post-fs-data.sh`）。
⚠️ **只关 `nms_eod` 不够** —— 崩溃链的一级 stage 是 FD_PRO（`nms_fd_qqvga`），EOD 只是二级 stage。

---

## 2. 设备事实基线

> 以下为**硬事实**，已多轮核实，不要重新探测。

### 2.1 硬件与系统

| 项 | 值 |
|---|---|
| 设备 | Lenovo Legion Y900（**TB522FU**），ColorOS 移植版 |
| SoC | SM8750P（`ro.board.platform=sun`、`soc_id=639`、`machine=SUNP`） |
| 内核 | 6.6.82 + KernelSU 4.2.0-rc1（su 域 `u:r:ksu:s0`） |
| ROM 供体 | **OPPO OPD2409（OPPO Pad 4 Pro）**，`ro.product.device=OP615CL1` |
| 原厂包 | `TB522_ZUXOS_2.0.13.057_Tool`（EDL，19 GB） |

### 2.2 分层溯源（决定"是谁的问题"）

| 层 | 位置 | 归属 | 证据 |
|---|---|---|---|
| system / product / system_ext | super | **移植者重打包** | `oplus/ossi/ossi:16`，构建日 2026-07-02 |
| bootimage / odm / vendor props | super | **供体 OPPO 原件** | `OPPO/OPD2409/OP615CL1:16`，构建日 2026-06-26 |
| **modem 分区**（挂 `/vendor/firmware_mnt`） | 独立物理分区 | **Lenovo 签名原件** | 证书链 `Lenovo Root CA` + `Mojito Root CA` |
| `/vendor/dsp` | **独立分区 sde9**（不在 super） | 单一时间戳 2026-07-02 | 7 个 QSH 相机模型 |

**关键结论**：ADSP 固件（Lenovo 签名，独立 modem 分区）↔ AP 侧 AON 栈（OPPO 交付物）是**跨厂商对接**。
后续又证明：**两侧 DSP 供给其实同一份二进制**（见 §5.2），跨厂商只成立于 super 的分区指纹层面。

### 2.3 分区映射（务必记牢）

```
dsp_a    →  /dev/block/sde9     64 MiB   /vendor/dsp          ext4 ro
dsp_b    →  /dev/block/sde43    64 MiB   （未挂载）
modem_a  →  /dev/block/sde6    350 MiB   /vendor/firmware_mnt  vfat ro
/vendor  →  /dev/block/dm-5              /vendor              ext4 ro（super 逻辑分区）
```

四个陷阱：

1. **`/vendor/dsp` 是独立物理分区 sde9，不属于 super**，与 `/vendor`（dm-5）无关。
2. **KernelSU overlay 不覆盖 `/vendor/dsp`**（overlay 只挂 `/system/bin`、`/system/etc`、`/vendor/etc`、`/my_product/vendor`、`/system/lky`）→ 改它必须**直写物理分区**，没有 overlay 的"卸载即回滚"。
3. **`firmware_mnt` 没有 by-name 符号名，它就是 `modem_a`**。写它同时影响 modem，**最高风险区**。
4. **`dsp_b` 不是 `dsp_a` 的字节级副本**（sha256 不同）→ 跨槽 `dd` 兜底**有损**，只是最后手段。

### 2.4 引导与认证

```
verifiedbootstate=orange ｜ vbmeta.device_state=unlocked ｜ flash.locked=0 ｜ veritymode=disabled
```

⚠️ **AVB 已关 ≠ 能改 ADSP。PIL/TZ 对 ADSP 的认证独立于 AVB**，不要混为一谈。这是"改固件"路线的死结。

### 2.5 相机映射（空闲态 device 数为 0，须先启动相机应用）

| device | 朝向 | 分辨率 | sensor |
|---|---|---|---|
| dev0 | Back | 4208×3120 | `ov13b10` |
| **dev1** | **Front** | 3264×2448 | `ofilm_sc820acs` |
| dev2 | Back | 1600×1200 | `qtech_ov02b10` |
| **dev3** | **Front** | 640×480 | **`qtech_og0ve`（AON FDPRO 那颗）** |

### 2.6 `/sys/class/remoteproc/` 编号不固定

必须按 `name` 解析。实测过一次 boot 内顺序互换：

```
remoteproc1 = 3000000.remoteproc-adsp    ← ADSP 在这里
remoteproc0 = 188101c.remoteproc-spss
remoteproc2 = 4080000.remoteproc-mss
remoteproc3 = 32300000.remoteproc-cdsp
remoteproc4 = a3380000.remoteproc-soccp
```

---

## 3. 崩溃机制（最终判决）

### 3.1 确切故障串

```
PD_ERR: qsh_process : EX:qsh_process:0x4:AonCam_0:0x10000014:PC=0xb2268d30:LR=0xb20700a4
```

- **18 条字节级一致**（= 9 次崩溃 × 2 条）。固件格式串 `0xf0356da8`：

```
EX:%s:0x%x:%s:0x%x:PC=0x%x:LR=0x%x:BADVA=0x%x:CAUSE=0x%x:SSR=0x%x
```

- `BADVA` / `CAUSE` / `SSR` 走 SMEM，**AP 侧拿不到**（无 debugfs 通路）。这是结构性的，不是采集失败。
- 崩溃模块：ADSP 侧 QSH AON 相机模块，源文件 `qsh_camera_sensor_island.c` / `qsh_camera_sensor_instance_island.c`，线程名 `AonCam_0`。

### 3.2 自持崩溃循环

| 特征 | 实测 |
|---|---|
| 周期 | **~5.0 秒** |
| 规模 | 7 次 SSR / 26 秒（安全阀中止） |
| `state=stop` 是否有效 | **无效** |
| 唯一停止方式 | **杀掉 AON 客户端进程** |

### 3.3 崩溃→USB 掉线的完整传导链

```
AON 相机流真正建立（FDPro 事件开始输出）
        ↓
ADSP fault: EX:qsh_process:0x4:AonCam_0:0x10000014
        ↓
remoteproc SSR（首次 fatal 产生 minidump；后续 PDR 级重启不产生）
        ↓
pmic_glink PDR down → altmode-glink: altmode_write: Error in sending message: -110
        ↓
PDR: msm/adsp/{sensor_pd, ois_pd, audio_pd} 各自重新上线
        ↓
UCSI 复位 Type-C → usb_role 读到 none
        ↓
USB 掉线 / adb 断开（6~9 秒后重新枚举，transport_id 递增）
```

### 3.4 fault PC 的真实身份

| 步 | 结论 |
|---|---|
| `0xb2268d30` 是什么 | **EAI 运行时 `eai_execute_internal` 的惰性绑定桩**（失败分支打印 `"Failed to get execute internal"`） |
| 崩溃在哪条指令 | 该函数**第一条 packet**，唯一访存指令 `memd(r29+#-0x10) = r17:16; allocframe(#0x10)` → **崩溃点是这次栈帧分配本身** |
| 早于什么 | 早于任何句柄表查找（`call 0xb2268e40`）⇒ **不是"句柄表返回 NULL"** |
| 谁调用它 | 相机 island 模块**全固件只调它一次**（`0xb207009c`）；`LR=0xb20700a4` 与栈帧严丝合缝 |
| `eai_execute` 全部调用者 | **5 个，全是 `client_algo_*_post_proc_island`**（eod / fd_enpu / hd / hgd / qrcode），**无 GazeDetect** |

### 3.5 机制判定：跨区执行域（决定性）

**全量出站调用普查**：岛代码块 **436 个出站 `call` 全部指向 ph[27]**，无一例外。

| 段 | 几何 |
|---|---|
| 岛代码段 | ph32–36 = `0xb200e000` – `0xb20df868` |
| ph[27] | 3.6 MB，**XWR**（可执行可写），是全固件仅两个 XWR 段之一 —— **不在岛块内** |

**四个候选机制的判定**：

| 候选 | 判定 | 依据 |
|---|---|---|
| **(A) 非法执行域** | ✅ **坐实** | 436/436 出站调用指向 ph[27] |
| **(C) 桩所在页不可取指** | ✅ **坐实** | 与 (A) 同因：callee 页在岛域不可执行 |
| (B) 栈/帧指针不可用 | ❌ **证伪（实测）** | 同一线程、同一 `r29`、`0x74` 字节之前的 `0xb2012308` 有**逐字节相同**的序言且成功返回 |
| (D) island spec 未创建 | ⚪ **降级为无关** | 即便 spec 全部创建成功，仍需调 ph[27]，照样 fault |

> **最终机制**：岛代码块在运行时被放进岛页表；它调用的 `eai_execute_internal` 位于 ph[27]。
> 跨区 `call` 在 callee 入口包上取指失败 ⇒ `PC=0xb2268d30`。
> **这是构建期链接/属性缺陷，设备端不可修。**

### 3.6 为什么"一直恰好是 0xb2268d30"

因为岛块里**每一个** `client_algo_*_post_proc_island` 都走同一个 `eai_execute` 桩，而桩是唯一入口。
40/40 次 PC/LR 完全一致，佐证调用深度固定。

### 3.7 岛覆盖由谁创建（排除"运行期可切"）

| 命题 | 判定 | 依据 |
|---|---|---|
| 岛页表 API 是 `trap0(#0x1e)` 系统调用族 | ✅ | 全是薄包装 |
| 45 处调用点全在 QURT 内核 ph[9] | ✅ | 4 个 `*_island.c` 段**零调用** |
| 段头 `bit27(0x8000000)` 是 island 标志 | ❌ **否** | 56 个段**都有**这个位 = 通用加载位 |
| 全镜像存在"岛覆盖表" | ❌ **无** | 覆盖不查表得来 |
| AP 可写旋钮能选"让 ph[27] 进岛" | ❌ **证伪** | 旋钮是**总开关**，不是覆盖选择器 |
| `EAI_ISLANDPOOL`（192 KB TCM）能装 ph[27]（3.6 MB XWR） | ❌ | **容量与权限双不成立** |

**⇒「不改固件的 island=1 修复」不存在。**

### 3.8 为什么 minidump / ramdump 这条路彻底封死

| 测量 | 结果 |
|---|---|
| `ADSP_MD0` 熵 | 7.9999 |
| `ADSP_MD1` 熵 | 8.0000 |
| 两份 dump 的密钥块 | **不同**（现场随机生成） |
| 5 候选密钥 × 3 IV × 3 模式 AES | 全部噪声（零字节率 0.3–0.5%） |

容器是 `QBEC` / `SS_MINIDUMP`，密钥**按 dump 随机生成并用 TZ/OEM 公钥包裹**。
没有私钥就是不可能。**把 ramdump 从所有方案里划掉。**

---

## 4. 修复方案与实测验证

### 4.1 根因链

```
移植 ROM 的 tuning 配置把 EOD/FD/QR 算法的 is_island 置为 1
        ↓
固件走 EAI island 低功耗路径（client_algo_*_post_proc_island）
        ↓
岛域内 call 进岛外 ph[27] 的 eai_execute_internal
        ↓
callee 页在岛域不可取指 ⇒ 0x10000014 fault
```

### 4.2 配置位置

| 项 | 路径 |
|---|---|
| 配置源（只读） | `/vendor/etc/sensors/config/qsh_camera_common.json` |
| **实际生效的覆盖层** | `/mnt/vendor/persist/sensors/registry/registry/` 下的 `qsh_camera_common.json.qsh_camera.tuning_params.<model>` |
| 总闸（保持不动） | `pakala_power_0.json.power.island` 的 `enable_island`（= 1，其他子系统仍在用岛） |

### 4.3 实测验证（对照表）

| 判据 | 修复前 | 修复后 |
|---|---|---|
| 冷启动单轮新增 ADSP minidump | 1 ~ 3 | **0（10/10 轮）** |
| FDPro 事件 / 轮 | 176 | **160 ~ 360（不降反升）** |
| 开机 AON 自启 | 1 次 ADSP SSR + USB-GUARD 风暴 | **0 次 SSR，USB 守护零触发** |
| 开机窗口内核 `PD_ERR` / `EX:` | 有 | **0 / 0** |

### 4.4 为什么只改 persist 就够

固件读配置的路径是 registry 覆盖层优先，覆盖层在 persist 分区（可写），而 `/vendor/etc/...` 那份只读源不需要动。
落地形态做到了**幂等**（重复执行无副作用），且随模块挂载自动生效。

### 4.5 需要正视的代价与边界

- 覆盖层**可能因 OTA / 恢复出厂而失效** ⇒ 复发。这是"配置性安全"的固有短板。
- legacy 路径与岛路径**可能不是同一份算法实现**（精度/帧率差异未量化）。这是唯一尚未量化的功能代价。
- `is_island` 键只覆盖 NMS 6 项，**没有 `nms_gaze`** —— 因为 "gaze" 这条在固件里属另一个家族（见 §6）。

### 4.6 早期失败路径（已全部作废，存档备查）

| 阶段 | 做法 | 结果 |
|---|---|---|
| **Exp-1** | 补 `oemconfig.so` + `remote_heap_config.so` | ❌ **作废**（机制不符，只会更严，见 §8） |
| **Exp-2** | 读 `fastrpc_shell_0` 的 sigverify 分支 | ✅ 结论：`oemconfig.so` 是**反回滚黑名单**，缺失即 **fail-open** |
| **Exp-3** | 判定栈耗尽 vs 野指针 | ❌ 结论被 Exp-4 取代（真正原因在配置层） |
| **Exp-4** | 定位到 `is_island` 配置层 | ✅ **成功，即最终修法** |
| **D2** | Camera 3 改走常规 camera3 流 | ❌ 不成立（见 §7.1） |
| **D3-1** | 搬供体 OPPO 的 ADSP 固件 | ❌ 证伪（OPPO 的 DSP 侧就是 Lenovo 的，同一份） |
| **v1.7 模块** | 声称"每次开机恰好 1 次 SSR、可吸收" | ❌ **失败**，依据是错误指标（dump 计数）得出的假绿 |

---

## 5. 原厂对照：ZUX 为什么"不崩"

### 5.1 静态层面：七层全同

| 层 | 结果 |
|---|---|
| ADSP 供给 | `dspso.bin` sha256 `746fe468…`、ADSP FDT sha256 `6f6f7f84…` —— **两侧相同** |
| AP 侧 8 个 AON 库 | **同一份二进制**（此前记的"原厂文件更小 4~56%"是**提取工具丢块**造成的假象） |
| 配置 | 两类 JSON **逐字段相同**（189 个扁平字段零差异） |
| ADSP 内核镜像 | `adsp.b08/b09/b10` sha256 **完全相同** |

**⇒ 整条链上每一个可枚举对象都相同** ⇒ "设备崩、原厂不崩"**只能**由**运行时行为差异**解释。

### 5.2 供给链闭合（硬结论）

```
DTB  island@c,ff800000  addr 0xCFF800000  size 0x7C0000  scid 0xC
        ↕ 逐字节吻合（合计恰为 0x7C0000）
adsp.b10 的 4 个 LLC 子池
```

`adsp.b10` 偏移 `0x2602c` 起 **18 条 × 60 B** 池清单：

| 池 | 基址 | 尺寸 |
|---|---|---|
| **`EAI_ISLANDPOOL`** | `0x06440000` | `0x30000`（**192 KB**） |
| `TCM_PHYSPOOL` | `[0x06400000, 0x06440000)` | — |
| `AUDIO_ISLAND_TCM_PHYSPOOL` | `[0x06470000, 0x06580000)` | — |

**三条关键纠正**：

1. **DTB 从不声明岛池**。DTB 里那几个 `*_ISLAND_POOL` 是 `sw/diag/diagcfg_param` 的**诊断缓冲落池配置值**，不是声明。池的权威清单在 **ADSP 内核镜像**（modem 分区）。
2. **`EAI_ISLANDPOOL` 真实存在**（旧结论"不存在 → attach 失败 → fault"**推翻**）。
3. `qurt_mem_pool_attach("EAI_ISLANDPOOL")` 失败是**优雅日志分支**，它自己**不 fault**。

### 5.3 反证法：原厂必然从未执行过这条路径

- 证据：设备 ADSP 供给 **≡ 原厂字节**（两侧 sha256 相同）
- 若原厂执行此路径 ⇒ **必崩** ⇒ 不可能上市
- **⇒ 原厂从未执行**。厂商出厂的是一份"**自己产品路径走不到的配置**"（高通 PAKALA 基线 tuning 默认值，原样继承、无人清理）

⚠️ **准确表述**（取代旧说法"厂商出厂一个不工作的功能"）：
**厂商出厂的是"自己产品路径走不到的配置"** —— 联想的产品功能是「**AI 眼动多窗**」（Tobii GD），
崩的是**高通 NMS 家族**（EOD/FD/QR）。**激活者是移植来的 ColorOS「注视时不息屏」（EOD）**。

### 5.4 静态分析的边界（诚实标注）

| 命题 | 能否静态判定 |
|---|---|
| ZUX **部署**了 island（总闸 + 逐模型开关 + 固件岛变体齐全） | ✅ 能，且 `is_island` 值就是 **1** |
| ZUX **运行时是否真的走进 island 分支** | ❌ 原理上不能（运行时属性：谁批准、什么 spec、谁投票） |
| EOD 的岛分支在原厂**不可达** | ✅ 能（`nms_eod` 在 ZUX 里只有 1 个配置键 + 1 个 DSP 模型，**AP 侧 0 个调用者**；4 字节 `"EOD"` 的其他命中经 ELF 节表判定**全是 TFLite `.text` 机器码巧合**） |
| FD Pro（崩溃链一级 stage）的岛分支在原厂是否可达 | ❌ 不能 —— 唯一的 AON 客户端 `SmartVision` **确实提供** FaceDetectPro 模式，需运行时判别 |

### 5.5 缺口闭环：原厂是否也从不执行

| 原假设 | 闭环结论 |
|---|---|
| "抓一次 `AONCam FD subscribe success … fd_algo_mode_index = %u` 就能闭环" | ❌ **伪命题** —— 该日志**只在存在订阅者前提下才产生**；原厂产品路径里根本没有 FD-Pro 订阅者，**这段日志永不产生** |
| "原厂不崩是因为选对了 mode" | ❌ 否 |
| **真实答案** | **原厂不崩 = 无订阅者**，不是"选对了 mode" |

**决定性反证**：旧进程以 `algo=0 160 120`（= `isIslandCapable=1` 的 mode[0]）+ `is_island` 全 0，
**仍照收海量事件、零崩溃** ⇒ **崩只由 `is_island` 决定，与选哪个 mode 无关**。

**实机 algo-mode 表**（2026-09-17 实抓）：

| 分辨率 | mode | `isIslandCapable` |
|---|---|---|
| **160×120** | mode[0] | **1** |
| 320×240 | mode[1] | 0 |
| 480×360 | mode[2] | 0 |

---

## 6. 两条 gaze 与 FD 链路

### 6.1 固件里有两套同名不同物的 gaze

| | ① Tobii gaze = OEM1 家族 | ② EOD gaze = 高通 NMS 家族 |
|---|---|---|
| 代码 | `qsh_camera_oem1_gaze_sensor*`、`qsh_camera_tobii_gaze_private.c`、`GazeNetInference::process`、`tobii_nexus_process_frame` | `client_algo_eod_post_proc(_island)`、`Gaze={%d,%d}` |
| 标定 | `gaze_calibration.bin` | — |
| 事件 | `QSH_CAMERA_GAZE_MSGID_*`，负载 `gaze_info{x,y}` + `delivery_mode` + `cal_status` | 交付走 **FD 事件**（`QSH_CAMERA_FD_EVENT_TYPE_GAZE_DETECTED`） |
| AP 侧 | `com.qti.qseeaon.so` 的 **`GazeDetect` 服务** + `vendor.gaze.aon.` | — |
| 岛块归属 | **代码全在 ph[27]，岛块 ph32–36 零命中** | **在 ph[34] 岛块内** |
| 是否经 `eai_execute` | **否** | **是** |
| 产品功能 | **「AI 眼动多窗」** | **「注视不息屏」** |
| 岛化方式 | **运行期 vote**（`qsh_camera_oem1 island vote: failed to create handle`），非配置键 | 由 `nms_eod.is_island` 配置键控制 |

**→ 崩的是 ②。`is_island` 键只覆盖 NMS 6 项，没有 `nms_gaze`。**

⚠️ **物理通道共用**：两者同 OG0VE、同 QSH 相机总线、同 `qsh_camera_sensor_island.c` 握手骨架。
若 Tobii 也被要求走岛，会撞**同一堵墙**（岛块 436/436 出站调用 100% 落 ph[27]）。

**设备物证**：`/mnt/vendor/persist/sensors/gaze_calibration.bin` **存在**（4137 B，2026-07-11 22:38 一次性写入）。
原厂 `persist.img` **空白** + `rawprogram_save_persist_unsparse0.xml`（persist 保留不刷）⇒ **设备自身写出**。
`/vendor/dsp/adsp/` **无 gaze 模型**（Tobii GazeNet 编进 ADSP 固件，不是独立 `.so`）。

### 6.2 FD 链路：两个版本，只有 Pro 是活的

| | 链路 A：FaceDetect | 链路 B：FaceDetectPro |
|---|---|---|
| AONServiceType | **0** | **1** |
| HAL/QSH 服务名 | `QSEEAONSrvFD` | `QSEEAONSrvFDPRO` |
| 引擎 fdEngine | 0 = `EngineCADL` | **1 = `EngineENPU`** |
| 本机 HAL 是否通告 | **否**（实机只有 FDPRO/HS/QR/HD/GD） | **是** |
| 本机是否真实运行 | **否**（无路径可达） | **是** |
| 是否碰 EAI / island | 否（CADL 是 ISP 硬件块，不调 `eai_execute`） | **是**（→ `eai_execute`） |
| 与 ADSP 崩溃关系 | 无关 | **就是崩溃那条** |

**⇒ 本硬件上 FD 侧只有 Pro 一条；FaceDetect 基础版（CADL）在本机 HAL 根本不通告，
`client_algo_fd_cadl_*` 是死代码路径。**

### 6.3 SmartVision：结构存在，驱动缺席

- `SmartVision`（`com.zui.camera.ex.av`）= **全 ROM 唯一的 AP 侧 AON 客户端**
- 但它的 FD 订阅驱动**不存在**：全 dex（14 754 类）里对 `AONManager.subscrible(int)` 的调用点**只有两处**：
  - `AONEyeTracking.init` → `subscrible(4)`（**GazeDetect**）
  - `AONGestureDetect.init` → `subscrible(3)`（**HandDetect**）
  - **没有 `subscrible(0)` 或 `subscrible(1)`**
- 但它把 FD/FDPro 的对象**全建好了**（`BaseService`、algoMode 索引表、`FDRegisterInfo` 都已实例化），只是**没有任何代码去订阅它们**

### 6.4 ZUX「注视不息屏」的真实归属

- **不走 AOSP `AttentionService`** —— ZUX 里是**随 AOSP 带进来的死脚手架**（`framework-res.apk` 有 3 个 config 键、jar 里有类与权限名，但**全 ROM 8 分区、双编码扫描都找不到实现体**）
- 走 **`SmartVision`（`com.zui.camera.ex.av`）→ 高通 QSH AON（`IAONService`）**
- 「Smart Attention」= ZUI 对该能力的对外品牌名

### 6.5 事件流的真身（一轮翻案，务必记住）

| 阶段 | 结论 | 状态 |
|---|---|---|
| 早期 | "FD-Pro 消费者是 CamX/ChiX" | ❌ **作废** |
| 中期 | "CamX 从不订阅 FD-Pro"（客户端订阅日志 6 窗口零命中） | ✅ 成立 |
| **最终** | **事件真身 = 本模块 `aon_daemon.bin`**（`daemon.out` 605 MB 满屏 `[AON] EVT FDPro n=7 vals=10,1,16,0,0,0,1`） | ✅ 确定 |

⚠️ **方法论**：**日志 tag（如 `ChiX`）不可作为归属依据** —— HAL 服务端 in-process 跑在 camera provider 内，
格式串属于 `vendor.qti.hardware.camera.aon-service-impl.so`，但 tag 显示为宿主进程名。
**归属看格式串属于哪个库**。

---

## 7. 时间线

### 7.1 2026-09-16 上午：D2 / D4 阶段（问题定位）

| 时间 | 事件 | 结论 |
|---|---|---|
| 10:22 | `fix_module` v1.7 判定 | ❌ **失败**（dump 计数假绿）；模块已禁用，设备恢复稳定 |
| 10:49–10:54 | **D2** Camera 3 常规 camera3 流验证 | ❌ **不成立** |
| 11:45 | `aon_power` 功耗对比 | AON ≈ 35 mW vs 常规 OG0VE ≈ 414 mW vs SC820ACS ≈ 850 mW |
| 11:23 | **D4** 受控单次复现 | ✅ 拿到**确切原因码** |

**D2 细节**：camera 3 的 `createCaptureSession` 恒返 `-10000`（全部 6 种格式/尺寸），**全程零 ADSP SSR**。
根因：CHI 把 camera 3 交给 **MultiCamera (MCX) 用例**，而 `ChiMcxStaticPolicy::CreatePolicy()`
无法构造 `UsecaseTransitionTable` → 用例对象创建失败 → `ConfigureStreams() CHI Module failed`。
**为什么建不出表**：CHI 的 usecase/pipeline 表按平台代号 XML 加载（`com.qti.chi.override.so` 内嵌 `eliza.xml` 等，
`eliza` = SM8750），但 `/vendor/etc/camera/` 里**只有 `camxoverridesettings.txt`**，
全盘找不到任何 `eliza*.xml` / `pakala*.xml` → 回落到编译内置默认表（覆盖 camera 0/1，**没有 camera 3 的 MCX 转换表**）。
**⇒ 这是"设计如此"，不是可修的 bug。唯一通路是 AON 服务路径。**

**D4 细节**：
- 崩溃是**自持循环**，不是 App 重试造成的
- `dumps` 计数再次被证伪：9 次真实 ADSP SSR，`/data/vendor/ramdump` **零新增**
- 依赖文件**全部齐全**（模型 7/7 + `og0ve_4.pb` + 注册表全部成功）→ **不是缺文件**
- 设备暴露 53 个传感器，**没有任何相机类或注视类传感器**
- AP 侧证据 1:1 对应：CamX `camxqseeaonfwintf.cpp:472 SensorErrorCallback` 报 `SSC connectionError` 共 18 次 = 9×(FD+HS)

### 7.2 2026-09-16 中午~下午：三轮实验 + 一次方向大转弯

| 时间 | 阶段 | 结论 |
|---|---|---|
| 12:16 | Exp-1/Exp-2 优势风险分析 | 结论：**先 Exp-2，后 Exp-1，不要并行** |
| 12:35 | `ROLLBACK_PLAN` | 备份 524 MB，三重校验通过（分区双端 sha256 / tar 99 文件 / 还原演练） |
| 12:36 | Exp-2（第一版） | "失败点 = ENOENT，fail-closed，返回 `0xE`" |
| 14:10 | **Exp-2 结论推翻** | `oemconfig.so` 是**反回滚黑名单**，缺失即 **fail-open**；**Exp-1 作废** |
| 14:45 | Exp-3 | fault 落在**框架包装函数的建帧**上，与"句柄表 NULL"无关 |
| 16:04 | **Exp-4：根因锁定配置层** | ✅ `is_island` tuning 开关；修法 = 4 个模型全改 0 |
| 19:19 | island 通路故障点定位 | fault PC = `eai_execute_internal` |
| 19:39 | island 供给链闭合 | DTB ↔ `adsp.b10` 逐字节吻合；观测面清单 8 条 |
| 19:54 | P0'''/P1'''/P2''/P3'' 方案 | 两条必须先纠正的事实 |
| 20:06 | **故障机制判决** | (A)/(C) 坐实，(B) 证伪，(D) 无关 |

**Exp-2 翻案的核心**（值得记住的教训）：
- **找错了二进制** —— sigverify 不在 `adsp_merged.elf`（29.7 MB 固件），在 **`/vendor/dsp/adsp/fastrpc_shell_0`**（1.2 MB，**not stripped**，4103 符号）
- **教训**：`dlopen` 由 DSP 侧发出，但提供 `dlopen`/`rtld`/`sigverify` 的是 **fastrpc shell**，不是 ADSP 固件本身。**找"谁做了 dlopen"应搜字符串消费方。**
- fail-open 三条独立判据：
  - A：`dynconfig_verify_lib_hash` `count <= 0` → 直接跳出口，出口写 `*out = 0`；调用方 `out == 0` → 放行
  - B：`library_version_check_enabled` 只读 `global+0x3c == 1`，该位仅由解析成功置 1 → 文件缺失时整段跳过
  - C：内建黑名单 `num_blacklisted_tcgs` @ `0xdb0c8` 实测 = **0**
- **为什么补文件只会更糟**：`dynconfig_ParseOEMConfig` 成功后**打开版本校验门控**并填入哈希名单 ⇒ 从"不校验（现状）"变成"**校验且拒绝**"

### 7.3 2026-09-16 傍晚~夜：ZUX 原厂对照 + island 深挖

| 时间 | 阶段 | 结论 |
|---|---|---|
| 18:12 | 联想原厂是否用 island | 是（部署齐全），**但供给端"缺"** ← 后被推翻 |
| 18:56 | `island_fixability` | "别修 island"，三条理由 |
| 18:56 | **`island_dtb_bypass_static_verdict`** | **四处推翻**：`EAI_ISLANDPOOL` 真实存在；DTB 从不声明池；`qurt_mem_pool_create` 零调用点；`libeai_service.so` 是转发壳 |
| 18:56 | `island_replication_verdict` | "复制原厂 island 通路"证伪：两侧**逐项相同**，差异为零 |
| 19:39 | 供给链闭合 | 见 §5.2 |

### 7.4 2026-09-17：产物链/tuning 深挖 + 七轮取证收口

| 时间 | 阶段 | 结论 |
|---|---|---|
| 11:17 | 运行期 vote/spec 假说 | ❌ 证伪（见 §3.7） |
| 11:28 | 原厂激活判决 | 厂商没有出厂"不工作的功能"，是"**从未被激活的配置**" |
| 11:50 | 「AI 眼动多窗」路径 | 走 OEM1/Tobii，**不经 `eai_execute`** |
| 13:01 | ZUX「注视不息屏」路径 | **不走 AOSP AttentionService**；走 SmartVision → QSH AON |
| 13:40 | 静态分析边界 | 能定"部署"、不能定"执行"；**七层全同** |
| 14:39 | SmartVision × FD 全链路 | 链路 A 死、链路 B 活 |
| 14:59 | 原厂为何不崩（收口） | 见 §5.3 |
| 15:23 | GD vs island=0 优劣判决 + 缺口闭环 | 见 §5.5 |
| 16:09 | **GitHub 模块 vs 原生方案判决** | 见 §9.1 |
| 16:10 | AON 会话断链诊断 | 部分结论后被推翻，见 §9.2 |

---

## 8. 已作废结论清单（防止重走）

> 本项目的最大风险不是"没找到答案"，而是**回头重走已经证伪的路**。以下每条都曾以"结论"形式写下过。

| # | 曾写下的结论 | 实际 | 错因 |
|---|---|---|---|
| 1 | 缺 `oemconfig.so` / `remote_heap_config.so` 是根因 | ❌ **作废** | fail-open 黑名单；`remote_heap_config.so` 属**音频段**，与相机不同段 |
| 2 | 句柄表 `op=0` 槽为 NULL → 返回 `0xE` → fault | ❌ **作废** | 表 9 项**全合法**，且崩溃在**查表之前**；且 `0xE` 分支本身**不会崩溃** |
| 3 | `EAI_ISLANDPOOL` 不存在 / DTB 不声明岛池 → attach 失败 | ❌ **作废** | 池在 `adsp.b10`；**DTB 从不声明池**；attach 失败是优雅分支不 fault |
| 4 | "OPPO 的申请撞上 Lenovo 的缺口" | ❌ **作废** | 两侧配置**都是原厂状态**，不是移植引入 |
| 5 | 栈耗尽 / 帧指针不可用（机制 B） | ❌ **证伪** | 同线程同 `r29` 的前序调用有逐字节相同序言且成功返回 |
| 6 | dump 计数（`/data/vendor/ramdump`）是崩溃指标 | ❌ **作废** | 后续 SSR **不产生 dump**，计数恒为常量。**有效用法 = 有界窗口内的 delta** + `grep -c PD_ERR /dev/kmsg` 交叉验证 |
| 7 | "userspace 无法消除" | ❌ **证伪** | 只需改配置 |
| 8 | minidump / ramdump 可读 | ❌ **作废** | QBEC 容器 + 随机包裹密钥，无 OEM 私钥即不可能 |
| 9 | `adspua.jsn` / `adspuo.jsn` 是 unsigned ADSP 变体 | ❌ **作废** | 实为 **servreg 服务注册描述符**（root_pd / sensor_pd / audio_pd / ois_pd）；无备用 ADSP 镜像 |
| 10 | "AP 侧 AON 库与联想原厂有同源编译差异" | ❌ **作废**（真相比原结论更强） | 两侧**是同一份二进制**；"原厂文件更小"是**提取工具丢块**造成的假象 |
| 11 | `libeai_service.so` 缺失 | ❌ **排除** | 存在（12 696 B），真问题是它**从未被加载** |
| 12 | 静态哈希校验失败 | ❌ **排除** | 4 个模型 SHA256 在固件与 `adsp_dtb` 中均 `not found` |
| 13 | 文件 mtime 能区分移植者注入的文件 | ❌ **不可用** | 批量刷成 `2009-01-01 08:00`，与来源无关 |
| 14 | "FD-Pro 消费者是 CamX/ChiX" | ❌ **作废** | 见 §6.5；真身是本模块 `aon_daemon.bin` |
| 15 | "抓 `fd_algo_mode_index` 才能闭环原厂为何不崩" | ❌ **伪命题** | 无订阅者 ⇒ 该日志永不产生 |
| 16 | "AON 会话断链不可自愈、只有重启设备可恢复" | ⚠️ **部分推翻** | `cid=-1` + 无 `EVT` 是 **Demand Mode 的正常空闲态**；"每 15 s +100~130 B"实为 **`HBT` 心跳行**（39 B×3） |
| 17 | `attention_ctrl test` 返回 `ABSENT` 证明通道断 | ⚠️ **不可信** | ① 降级路径与"真没人脸"**字节级不可区分**；② 可能读**陈旧文件**（两个 launcher 争抢 daemon 且 IPC 路径不一致） |
| 18 | CPU 累积增量是"最硬判据" | ❌ **实测推翻** | 断链态与恢复态**都是 2 ticks / 25 s**。原因：daemon 主要阻塞在 poll，单事件开销极小 |

---

## 9. 当前状态与待办

### 9.1 方案选择（GitHub 模块 vs 联想原生）

**不是二选一，两条路解决的问题不同**：联想原生那条「安全但没大脑」；GitHub 模块这条「有大脑但要靠配置保险」。

| 维度 | 联想原生（SmartVision → GazeDetect/Tobii） | GitHub 模块（FD-Pro + is_island=0） | 胜 |
|---|---|---|---|
| **能不能真正用起来** | ❌ ZUX 全 ROM **没有 `AttentionService` 实现体** | ✅ 唯一端到端打通 | **模块** |
| **崩不崩（安全来源）** | ✅ **结构性**（非岛段、不经 `eai_execute`、无 `is_island` 键） | ⚠️ **配置性**（OTA/恢复出厂即失效） | **原生** |
| **精度上限** | ✅ gaze **坐标流** + 标定 | ⚠️ bbox + `has_gaze` 布尔 | **原生** |
| **可控/可改/可移植** | ❌ 闭源私有件 + `INJECT_EVENTS` 特权 | ✅ 全开源 | **模块** |
| **待机功耗** | ⚠️ 眼动会话期间常驻出流 | ✅ Demand Mode，实测整夜 **0.167 mAh** | **模块** |
| **工程健壮性（现状）** | ✅ ROM 内建 | ⚠️ 实测 **3 个可修缺陷** | **原生** |

**门槛项优先**：第一项不过后面都没意义 → **现在必须用模块**。
**最优形态是合成**：模块骨架 + 原生那条结构性非岛的 GD 通道 —— 已探针验证
（把 daemon 的 `srv` 换成 4（GazeDetect）注册返回 `transact_-38`（ENOSYS）），
**不是配置级可切，需要改 daemon 代码**。

### 9.2 GitHub 模块的 3 个真实缺陷（都能修）

| # | 缺陷 | 说明 |
|---|---|---|
| 1 | **`supervisor.sh` 的 IPC 路径不一致**（v1.8.0 仍在） | `service.sh` 和 `attention_ctrl` 都用 app 路径 `…/futureharmony.tb522fu.aon/files/`，**只有 `supervisor.sh` 用 `/data/adb/tb522fu_attention/`**，且是 v1.7→v1.8.0 **唯一没跟着改**的组件。daemon 一旦死亡被它重拉，控制面就**永久失联**。**一行修完。** |
| 2 | v1.7 `attention_ctrl` 发**裸 `start`** | 配上"内容不变即忽略"（seq 去重）语义就**静默失效** → 这是 `test` 长期返回「ABSENT (未检测到人脸)」的真因（v1.8.0 已改成完整协议 `state=start 0 1 15 2 480 360 3 seq=N`） |
| 3 | **AON 客户端是单占用** | 刚 kill 一个"已注册"的 daemon 后 3 秒内重拉**必失败**，需 ≥20 秒 |

### 9.3 未决项（按收益排序）

| 项 | 价值 |
|---|---|
| 核心中间件 `CAMERA_FD_QUERY_AVAIL_ALGO_MODES` 处理函数未定位（`isIslandCapable` 由什么算出） | 低（已证明 mode 与崩溃无关） |
| `LenovoXiaoTian` 的 `changeAttentiveDisplay` 发给谁 | 中（可能揭示原厂完整链路） |
| 重启后验证 mode[2]（480×360）实际检测效果（检出率/延迟） | **高**（直接决定功能体验） |
| legacy 路径 vs 岛路径的算法实现是否同一份（精度/帧率差异量化） | **高**（唯一未量化的功能代价） |
| `supervisor.sh` 路径 bug 上报上游 + 加单实例自检 | 中（防复发） |
| OPPO 私有 AON 不可用：`libcsextimpl.so` 的 `closeAON()` 报 `no front camera device path` | 低（依赖 OPPO 私有内核驱动，本机没有） |

### 9.4 已知的独立 ROM 缺陷（与 camera 3 / AON 无关，**别误判**）

1. **camera provider CFI 崩**（9 个 tombstone）：`camx.device-impl.so` 与 `android.hardware.camera.device-V2-ndk.so`
   **CFI 类型哈希不匹配**（blob ABI 不一致）；**连 camera 0 出流也打崩**。
2. `vendor.oplus.hardware.urcc-service` 崩 12 次。
3. OPPO 私有 AON 不可用：依赖 V4L2 `VIDIOC_CAM_AON_POWNER_UP/DOWN`（OPPO 私有内核驱动，本机没有）。
   **与 QTI `IAONService` 是两套独立机制。**

---

## 附录 A：硬数据速查

### A.1 地址与段几何

| 项 | 值 |
|---|---|
| `eai_execute` | `0xb206ff4c` |
| `eai_execute_internal` 桩（fault PC） | `0xb2268d30` |
| 调用它的唯一指令 | `0xb207009c`（LR `0xb20700a4`） |
| 岛代码段 ph32–36 | `0xb200e000` – `0xb20df868` |
| ph[27]（3.6 MB XWR） | 非岛段 |
| ADSP 侧 QSH 相机 island 代码段 | `0xb2067000` |
| `eai_execute` 调用者数 | **5**（eod / fd_enpu / hd / hgd / qrcode，全 NMS） |
| 岛块出站调用总数 | **436，100% 落 ph[27]** |
| 岛页表 API 调用点 | **45，全在 QURT 内核 ph[9]** |
| minidump 结构 | `ADSP_MD0` `0x9ee00000` size 1 312 380；`ADSP_MD1` `0xa0112a18` size 67 MB |

### A.2 字号与哈希

| 项 | 值 |
|---|---|
| `dspso.bin` / `dsp_b.img` | `746fe46863035777980f0e655ea0f8c6e82864ed673eb22e45003a8de7c80896` |
| `dsp_a.img` | `315475380d413ec98fc8df39f048a0e7b762af02bf363b214903420b15d9cc05` |
| `modem_a.img` | `ce688c3501d2c438767da48963397e2446140a00e31987e42f018ff29c746afc` |
| ADSP FDT（`zux_adsp_dtb.b01`） | `6f6f7f84…96211` |
| `vendor_dsp_tree.tar` | `63eda283d5dde53fcb020740c00e50de3b255236d0ad4369b16d7ec4234553e6` |
| `libQnnLpaiIslandV79_v5.so` | `c2a063985b13a75c5d7ec887b2f34c4b6bdda555c187242c982d7ec78cb430ce` |
| `fastrpc_shell_0` | `373404da…` |
| `camera.qcom.so`（ZUX = 设备） | `1861fe08…` |
| `com.qti.chi.override.so`（ZUX = 设备） | `a26351ba…` |
| 唯一构建标签 | `LPAIDSP.HT.1.1-01182-PAKALA-1_20250620_000950` |

⚠️ **哈希口径注意**：备份表（一手 `dd`）记录 `dsp_a=31547538…`、`dsp_b=746fe468…`；
而 HyperOS 移植包的 `images/dspso.bin` = `dsp_b.img` = `746fe468…`。
部分笔记里出现过"设备 dsp_a = 746fe468"的写法，**与备份表冲突，以备份表为准**。

### A.3 关键路径

```
配置源（只读）      /vendor/etc/sensors/config/qsh_camera_common.json
生效覆盖层          /mnt/vendor/persist/sensors/registry/registry/
                    qsh_camera_common.json.qsh_camera.tuning_params.<model>
总闸                pakala_power_0.json.power.island → enable_island
ADSP 签名固件       /vendor/firmware_mnt/image/adsp.b00 .. b59
DSP 侧可加载模块    /vendor/dsp/adsp/（sde9）
池清单权威表        adsp.b10 偏移 0x2602c，18 条 × 60 B
模块目录            /data/adb/modules/tb522fu_attention_keepon/
状态目录            /data/adb/tb522fu_attention/
App                 futureharmony.tb522fu.aon
App 侧 IPC          /data/data/futureharmony.tb522fu.aon/files/{aon_cmd,aon_evt,aon.log}
相机电源域          /sys/bus/i2c/devices/... （OG0VE 与 SC820ACS 分开）
USB 检测点          /sys/class/usb_role/a600000.ssusb-role-switch/role
                    /sys/class/udc/a600000.dwc3/state
```

### A.4 daemon 参数语义

```
--daemon <cmd> <evt> <camIdx> <srv> <mask> <algo> <w> <h> <dps>
```

`(w,h)` 一一对应固件 mode：160×120 = mode[0]（`isIslandCapable=1`）/ 320×240 = mode[1] / 480×360 = mode[2]。
服务类型：FaceDetect=0、**FaceDetectPro=1**、QRCode=2、HandDetect=3、GazeDetect=4。
当前生效命令行：`--daemon … 0 1 15 2 480 360 3`。

### A.5 观测面清单（8 条，来自供给链闭合轮）

| # | 入口 | 宿主 | 预期输出 | 优先级 |
|---|---|---|---|---|
| 1 | `SYSMON_DSP_GET_ISLAND_POOL_MEMORY` | `libsysmondomain_skel.so` | 池名 + 各池内存 | P0（**AP 侧不可直调**，见 §坑） |
| 2 | `qurt_mem_pool_attach success/failed for %s` | 同上 | **逐池 attach 成败** | P0 |
| 3 | `qsh_island_test.c` 自测 | QSH 内建 | `island_percentage` / `island_exits` | P1 |
| 4 | `pre_qurt_island_get_status{2}` | QURT island API | 岛当前域 / 进入计数 | P1 |
| 5 | `island_enter_cnt:` / `island_exit_cnt:` | QSH 调试 | 岛进/出配对计数 | P1 |
| 6 | `sns_pwr_sleep_mgr_npa.c` 的 `is_island_blocked(%d)` | sensors 电源 NPA | 配置是否落到投票层 | P2 |
| 7 | `sysmondomain_island_test_config` | sysmon API | 主动触发岛测试 | P2 |
| 8 | `island_mgr_get_pool_names_count` / `_names` | island_mgr QDI | 岛池枚举 | P2 |

**已排除的观测面**：minidump/ramdump（加密不可解）；内核 dmesg/kmsg（DSP 侧不落）；
`/sys/kernel/boot_adsp/*`（不可读）。

---

## 附录 B：备份与回滚

**备份根目录**：`<backup-root>/tb522fu_backup/2026-09-16/`（总 **524 MB**）

| 文件 | 大小 | sha256 |
|---|---|---|
| `dsp_a.img` | 67 108 864 | `31547538…` |
| `dsp_b.img` | 67 108 864 | `746fe468…` |
| `modem_a.img` | 367 001 600 | `ce688c35…` |
| `vendor_dsp_tree.tar` | 44 205 056 | `63eda283…` |
| `vendor_dsp_files.sha256` | 9 503 | 99 个文件逐文件哈希 |
| `ap_aon_files.sha256` | 16 523 | AP 侧 AON 相关 128 个文件登记 |
| `META.txt` | 981 | boot_id / uptime / 分区布局 / 挂载点 |
| `BACKUP.sha256` | 316 | 汇总校验 |

**三重校验（不是"写完了就算"）**：

1. **镜像双端 sha256**：本地镜像 vs 设备端 `sha256sum /dev/block/sdeX` 直读分区 → 三个分区全部一致
2. **文件树逐文件三方比对**：设备端 `find -exec sha256sum` vs tar 流式解包复算 → **99/99 全一致**
3. **还原演练（已实测）**：从 tar 抽出 `libQnnLpaiIslandV79_v5.so`（`c2a06398…`）→ 与设备在线文件、与备份清单**三方一致**

**重新校验（任何时候）**：

```bash
cd aon_rootcause
./backup_dsp.sh verify        # 本地 == 记录 == 设备，三方比对
```

**三层回滚路径**：

| Tier | 手段 | 是否需重启 | 适用 |
|---|---|---|---|
| 1 | 文件级还原 | **否** | Exp-1 这类只增/换文件的操作 |
| 2 | 分区级还原 | 是 | 兜底 |
| 3 | 设备起不来时（PC 侧） | — | EDL |
| 跨槽兜底 | `dsp_b → dsp_a` dd | 是 | ⚠️ **有损**（两槽哈希不同），最后手段 |

**写操作前的强制检查清单**：先备份 → 先校验 → 先演练还原 → 一次只改一个变量 → 确认目标在设备上确实存在。

---

## 附录 C：工具清单

| 工具 | 用途 |
|---|---|
| `efetch.py` | 从 ADSP 固件提取（VA↔offset） |
| `whoino.py` | 偏移→文件归属（走 inode 表遍历，**不要用目录递归**） |
| `multiscan.py` | 多模式字符串扫描 |
| `elfstr.py` | ELF 字符串/节表 |
| `hexfunc.py` | Hexagon 函数识别 |
| `secmap.py` | 段映射（判断命中是否在可执行段） |
| `strref.py` | 串引用交叉引用（重建 PC 相对引用） |
| `sv_fd.py` | sigverify 分析 |
| `va_read.py` | Hexagon ELF32 的 VA↔offset / dump / str / w32 / phdr |
| `hexagon_xref.py` | **修正版**：补上全部**绝对立即数**寻址（`combine(##imm)` / `rN = ##imm`），支持负号（objdump 把 32 位立即数**按有符号**打印） |
| `backup_dsp.sh` | 备份 / 校验（`verify`） |
| `tarutil.py` | tar 流式解包与比对（**默认只读**） |

以上已同步进 skill `android-remoteproc-ssr-triage/scripts/`。

---

## 附录 D：归档原文索引

> 下表列出的是归并入本文的 **25 份原始取证文档**（原文位于工作区 `aon_rootcause/_archive/`，不随本仓库分发），
> 按主题分组便于定位；另有 5 份独立活文档（未归并）见 §0.1。完整清单见 §D.6。

### D.1 问题定位（4 份）

| 原文 | 内容 |
|---|---|
| `fix_module_readme.md` | v1.7 模块失败判定 + 崩溃链 + 守护进程协议 + 补丁变体实验矩阵 |
| `d2_cam3_test_readme.md` | Camera 3 常规 camera3 流验证（D2）：全部 `-10000`，CHI MCX 建不出表 |
| `d4_qsh_fault_readme.md` | **D4 受控复现**：确切原因码 + USB 断连链 + 分层溯源 |
| `aon_power_readme.md` | AON vs 常规路径功耗对比（35/414/850 mW） |

### D.2 实验与回滚（7 份）

| 原文 | 内容 |
|---|---|
| `readme.md`（aon_rootcause 主索引） | AON 通路根因溯源 v3（含 §11 Exp-2 翻案、§12 Exp-3 收口） |
| `exp12_analysis.md` | Exp-1/Exp-2 优势风险分析 + 对比矩阵 |
| `exp2_result.md` | Exp-2 第一版：ENOENT / fail-closed（**后半被推翻**） |
| `exp2_sigverify_result.md` | Exp-2 翻案：`oemconfig.so` 是反回滚黑名单，fail-open |
| `exp3_result.md` | Exp-3：fault 落在框架包装函数的建帧上 |
| `exp4_result.md` | **Exp-4：根因锁定配置层 `is_island`（最终修法）** |
| `ROLLBACK_PLAN.md` | 回滚方案 + 资产拓扑 + 三重校验 |

### D.3 island 机制深挖（7 份）

| 原文 | 内容 |
|---|---|
| `island_exec_fault_20260916.md` | fault PC = `eai_execute_internal`；ramdump 永久封死 |
| `island_supply_chain_20260916.md` | 供给链闭合 + QURT island 运行时全貌 + **观测面清单 8 条** |
| `island_fixability_20260916.md` | "能不能真修" —— 判断与建议（含 18:50 决定性订正） |
| `island_fault_mechanism_verdict_20260916.md` | **机制判决：(A)/(C) 坐实，(B) 证伪**；为什么设备上修不了 |
| `next_step_p0_p3_20260916.md` | P0'''/P1'''/P2''/P3'' 执行结论与方案 |
| `island_runtime_lever_verdict_20260917.md` | "运行期 vote/spec 可让 ph[27] 进岛" —— **证伪** |
| `island_dtb_bypass_static_verdict_20260916.md` | 静态通路终审：**四处推翻**（池存在、DTB 不声明池等） |

### D.4 ZUX 原厂对照（7 份）

| 原文 | 内容 |
|---|---|
| `zuxos_island_native_20260916.md` | 联想原厂是否用 island 支撑 AON（结论后被 §7.3 修正） |
| `island_replication_verdict_20260916.md` | "复制原厂 island 通路"判决：两侧**逐项相同** |
| `gaze_path_verdict_20260917.md` | 「AI 眼动多窗」走 OEM1/Tobii，**不经 `eai_execute`** |
| `island_native_activation_verdict_20260917.md` | 厂商没有出厂"不工作的功能"，是"**从未被激活的配置**" |
| `zux_attentive_display_verdict_20260917.md` | ZUX「注视不息屏」不走 AOSP AttentionService；走 SmartVision |
| `zux_island_static_analysis_verdict_20260917.md` | 静态分析边界：**能定"部署"、不能定"执行"** |
| `zux_smartvision_fd_chain_verdict_20260917.md` | SmartVision × FD/FDPro 全链路静态判决（42 KB，最详） |

### D.5 方法论

坑清单 `PITFALLS.md` **未归档**（活文档），共 22 条，分 A（设备与运维）/ B（提取与扫描）/ C（Hexagon 反汇编）/ D（AON 会话与判据）四节。

### D.6 归档清单（25 份，完整文件名）

**来自 `aon_rootcause/`（13 份）**

```
island_exec_fault_20260916.md            island_supply_chain_20260916.md
island_fixability_20260916.md            island_fault_mechanism_verdict_20260916.md
next_step_p0_p3_20260916.md              island_runtime_lever_verdict_20260917.md
exp12_analysis.md                        exp2_result.md
exp2_sigverify_result.md                 exp3_result.md
exp4_result.md                           ROLLBACK_PLAN.md
aon_rootcause_readme.md                  （原 aon_rootcause/readme.md）
```

**来自 `aon_rootcause/zuxos_native/`（8 份）**

```
zuxos_island_native_20260916.md          island_dtb_bypass_static_verdict_20260916.md
island_replication_verdict_20260916.md   gaze_path_verdict_20260917.md
island_native_activation_verdict_20260917.md
zux_attentive_display_verdict_20260917.md
zux_island_static_analysis_verdict_20260917.md
zux_smartvision_fd_chain_verdict_20260917.md
```

**来自项目顶层（4 份）**

```
fix_module_readme.md      （原 fix_module/readme.md）
d2_cam3_test_readme.md    （原 d2_cam3_test/readme.md）
d4_qsh_fault_readme.md    （原 d4_qsh_fault/readme.md）
aon_power_readme.md       （原 aon_power/readme.md）
```

**未归档（保留原位）**

```
aon_rootcause/zuxos_native/PITFALLS.md
aon_rootcause/zuxos_native/github_module_vs_native_verdict_20260917.md
aon_rootcause/zuxos_native/zux_gd_vs_island0_verdict_20260917.md
aon_rootcause/zuxos_native/zux_attentive_display_why_not_crash_verdict_20260917.md
aon_rootcause/zuxos_native/aon_session_lost_verdict_20260917.md
PROJECT_HISTORY.md                       （本文）
```

---

*本文由 25 份历史文档归并而成。若发现本文与归档原文冲突，**以原文为准**，并回来修正本文。*

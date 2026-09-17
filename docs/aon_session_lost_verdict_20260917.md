# AON 会话断链不可自愈 —— 第 7 轮取证判决

> ## ⛔ 本文核心判决已被第 8 轮证伪（2026-09-17，`github_module_vs_native_verdict_20260917.md` §4.2）
>
> **原文结论「AON 会话断链不可自愈，只有重启设备可恢复」—— 作废。**
>
> 第 8 轮实机实验：杀掉 daemon 后用正确命令重拉，**12 秒内即产出 94 个 `EVT` 行**
> （`st=1 rep=1 cid=-5476376651825832944`），**全程零重启、零崩溃** ⇒ 会话**可重建**。
>
> 两处误判（方向相反，恰好互相抵消，所以表面上自洽）：
> 1. **把"空闲态"读成"故障态"**：`cid=-1` + 无 `EVT` 行 = **Demand Mode 的正常未注册态**，
>    不是断链。本项目在熄屏超时被框架回调前，本来就不注册流。
> 2. **把"心跳"读成"事件"**：第八节表格里"每 15 s +100~130 B"的增长是 **`HBT` 心跳行**
>    （39 B × 3 行 ≈ 117 B），**不是 `EVT`**。真事件流在健康态是 8 帧/s ⇒ 15 s 应涨约 3.7 KB。
>
> 仍有效的部分：§二 判据 1（daemon 日志 95 B 无事件）、§三（`test` 返回值不可用）、
> §八 修正 1（CPU 判据失效）、§五（supervisor 双实例根因）。
> 已失效：§一 表格中"只有重启可解"、§二 判据 3 的"会话建立日志"解读、
> §四 的"不可重建"机制定性、§七 的"开机一次性资源"表述。

**日期**：2026-09-17
**起因**：第 6 轮为抓 CamX 订阅日志多次 kill `vendor.qti.camera.provider-service_64`，
造成 `aon_daemon.bin` 报 9 次 `IAONService binder died!`。第 6 轮文档给出的处置是
「**需重启设备恢复**」，但未验证"是否真的只有重启可解"。本轮把这条结论验到底。

**判决**：~~结论成立~~ → **已被第 8 轮证伪（见顶部 banner）**。原文判为"重启 daemon / app / provider
三种进程级修复全部无效，只有设备冷启能恢复"；第 8 轮证明真正的原因不是"会话不可重建"，
而是**命令通道没打通**（命令文件"内容不变即忽略" + supervisor 用错 IPC 路径 + 单占用资源接管延迟）。

---

## 一、结论速览

| 修复动作 | 是否恢复 AON 会话 | 证据 |
|---|---|---|
| `attention_ctrl off/on`（重发指令） | ❌ | 只写 `[STATUS]` 日志，**无** `[AON-ULP] … started` |
| kill + supervisor 重拉 `aon_daemon.bin` | ❌ | 新 daemon 25 s 内 CPU 仅 +2 ticks，零 `EVT FDPro` |
| 手工带日志重启 daemon（可观测版） | ❌ | 输出仅 95 B 的 binder 初始化行，无任何事件流 |
| 重启 camera provider（已有新实例） | ❌ | 新 provider PID 10600 + `IAONService/default` 已注册，daemon 仍建不了 |
| 重拉 app `AONAttentionService` | ❌ | 服务活跃（PID 7989），但会话仍不建立 |
| **设备重启（ADSP 冷启）** | ✅ **已验证有效** | 第八节：`started` 日志重现 + `aon_evt` 每 15 s +100~130 B |

---

## 二、证据链（4 条硬判据）

### 判据 1 ── daemon 侧无事件流（最直接）

用带日志的方式重启 daemon 后观测 15 s：

```
$ stat -c %s daemon2.out
95
$ cat daemon2.out
[AON] daemon binder: hBinder=0xc9708b1bb6c6ac87 setPoolMax=0x75325f69c0 startPool=0x75325f6920
```

**仅 95 字节，只有 binder 自注册那一行，零事件。**
对照健康时期同文件是**满屏**：

```
[AON] cb transact code=1
[AON] EVT FDPro n=7 vals=10,1,16,0,0,0,1
[AON] cb transact code=1
[AON] EVT FDPro n=7 vals=10,1,16,0,0,0,1
...
```

### 判据 2 ── daemon CPU 累积近乎为零（⚠️ **后被第八节推翻，勿再引用**）

`/proc/<pid>/stat` 的 utime+stime，间隔 25 s 两次采样：

```
T1 (utime stime) = 4 5
T2 (utime stime) = 4 7
25s 内 CPU 增量 = 2 ticks (0.02 秒)
```

编写本文时认为"健康态每秒处理百余事件 ⇒ CPU 持续累积"，故把 0.02 s 读作"完全空闲"。
**该推理在第八节被实测推翻**：会话恢复后 CPU 仍是 2 ticks / 25 s。
保留此条是为了记录这个**曾误导过一轮的判据**——真正有效的判据见第八节修正 1。

### 判据 3 ── 会话建立日志不再出现

`attention.log` 中唯一那条成功记录：

```
[2026-09-17 15:21:06] [AON-ULP] Camera 3 AON hardware daemon started (algo=2 480x360 non-island)
```

此后所有 off/on（15:32:44、15:36:44、15:36:45）**只写 `[STATUS]`，再无 `started`**。
15:21:06 的 `started` 与旧 daemon PID 13673 的 `/proc/13673` 创建时间**精确同秒**，
说明它是 daemon **建会话成功**时打的点，而非指令触发。

### 判据 4 ── 分层：IPC 活着，AON 订阅死了

```
$ stat -c "%y %s %n" aon_evt aon_cmd
2026-09-17 15:38:46  614  aon_evt     ← 我的 test 指令刚写新内容
2026-09-17 15:38:10    6  aon_cmd
```

daemon 仍能读写 IPC 文件、能返回 JSON 响应 ⇒ **daemon 本体健康**。
死掉的是"daemon ↔ AON HAL"这一层订阅。

---

## 三、❗方法论坑：`attention_ctrl test` 返回 ABSENT **不能**证明通道正常

这是本轮最容易误判的地方，必须写死：

```
{"success": true, "code": 0, "resultName": "ABSENT (未检测到人脸)", "val": "0",
 "action": "维持当前熄屏倒计时"}
```

**通道完全断开时，test 依然返回这个"成功"结果。**
原因：daemon 拿不到 AON 数据时走**降级路径** —— 超时后按"无人脸"上报，
`code=0 / resultName=ABSENT` 与"真的没人脸"**字节级无法区分**。

旁证：连打 5 次，时间戳为 `15:38:01 / :04 / :07 / :10 / :13`，
**每次约 3 秒**（循环内无 sleep）。健康态 FDPro 事件是连续的，不该有这个等长等待
⇒ 这 3 秒就是降级路径的等待超时。

**正确判据（按可靠性排序）**：
1. **事件输出文件的 size 增量**（最硬，见第八节）：
   活跃 daemon 的 evt 文件持续增长（实测 15 s 约 +100~130 B）；断链时完全不变。
2. `attention.log` 有无新的 `[AON-ULP] … started`（判据 3）。**断链期间一条都不出现**，
   恢复后立即出现 —— 这是对比出来的可靠信号。
3. 带日志重启 daemon，看有无 `[AON] EVT FDPro`（判据 1）。
4. ❌ **不可用：daemon 的 CPU 累积增量**。原以为是"最硬"判据，**实测被推翻**：
   断链态与恢复态都是 **2 ticks / 25 s**。原因见第八节的修正说明。
5. ❌ **不可用：`attention_ctrl test` 的返回值**（见上文）。

---

## 八、恢复验证（重启后）与两处判据修正

**执行**：`adb reboot` → `boot_completed=1`（约 90 s）。

**结果：AON 会话恢复 ✅**

| 观测项 | 断链期（15:32–15:41） | 重启后（15:42+） |
|---|---|---|
| `[AON-ULP] … started` 日志 | ❌ 一条无 | ✅ `15:42:40 … (algo=2 480x360 non-island)` |
| 事件输出文件 size | — 不变 | ✅ 1050 → 1182 → 1281 → 1380（每 15 s +100~130 B） |
| daemon CPU（25 s） | 2 ticks | 2 ticks → **判据失效，见修正 1** |
| ADSP 崩溃 | 0 | 0 |
| 进程 | 2 supervisor + 1 daemon | 1 supervisor + 1 daemon |

### 修正 1 ── CPU 累积**不能**作为会话判据

原假设"健康 daemon 每秒处理百余事件 ⇒ CPU 持续累积"**不成立**：断链态与恢复态都是
**2 ticks / 25 s**。原因：daemon 主体阻塞在 poll 上等 binder 回调，单事件处理开销极小；
第 6 轮看到 `daemon.out` 长到 605 MB，增长的是**磁盘 I/O（sys 时间）**，而当时的 daemon
**stdout 重定向到了文件**。本案中 supervisor 把 stdout 丢进 `/dev/null`，
日志不再落盘 ⇒ CPU 差异被抹平。

**正确判据**：看**事件输出端**（IPC evt 文件 / 日志文件）的 **size 与 mtime 增量**。
这与"daemon 是否在收事件"直接同构，且不受 stdout 去向影响。

### 修正 2 ── ~~`attention_ctrl` 读的是**陈旧文件**~~（第 8 轮复核：路径说反了）

> **第 8 轮复核更正**：设备装的 v1.7 `attention_ctrl` 第 10-11 行读的**就是 app 路径**
> （`/data/data/futureharmony.tb522fu.aon/files/aon_{cmd,evt}`），不是 `/data/adb/`。
> 真正用错路径的是 **`supervisor.sh`** 第 64-66 行 —— 它是 v1.7→v1.8.0 之间**唯一**没跟着改的组件。
> ⇒ 原文"attention_ctrl 读陈旧文件"的机制**作废**；那固定 ~3 秒的真因见
> `github_module_vs_native_verdict_20260917.md` §5 缺陷 1/2（supervisor 路径不一致 + 命令语义）。

原（已作废）表述：`attention_ctrl` 固定读写 **`/data/adb/tb522fu_attention/{aon_cmd,aon_evt}`**，
但**开机时 app（`AONAttentionService`）会先用 app 自己的路径拉起 daemon**：

```
# daemon 实际 argv（重启后）
aon_daemon.bin --daemon /data/data/futureharmony.tb522fu.aon/files/aon_cmd \
                          /data/data/futureharmony.tb522fu.aon/files/aon_evt 0 1 15 2 480 360 3

# daemon 的 fd 5 → /data/data/.../files/aon_evt    ← 活跃写入（1380 B）
# 而 attention_ctrl 读  /data/adb/tb522fu_attention/aon_evt  ← 陈旧副本（614 B，不再更新）
```

supervisor 随后 `pidof` 命中已存在的 daemon，**不再拉自己那份** ⇒
`/data/adb/` 路径永远没有活跃 daemon 在写 ⇒ `attention_ctrl test` **永远返回陈旧值**。
（这也解释了那固定的 ~3 秒：等待一个不会更新的门文件。）

**这是模块 v1.7 的真实缺陷（值得修）**：两处启动方**争抢** daemon，
先到者决定 IPC 路径，而 `attention_ctrl` 只认其中一条。
**建议**：让 app 与 `supervisor.sh` 使用**同一组** cmd/evt 路径（推荐统一到 `/data/adb/tb522fu_attention/`），
或让 supervisor 在检测到 daemon 路径不符时重拉。

**结论**：本设备的 AON 会话确实是**开机一次性**资源，且重启是**充分有效**的恢复手段；
`config.json` 的 `enabled=true` 在重启后自动生效，注视不息屏开箱恢复，无需再下指令。

---

## 四、机制定性（观察 vs 推测，分开写）

**已观察到的事实**：
- camera provider 异常死亡后，AON 会话进入**不可重建**状态；
- 该状态**跨进程边界**：换新 daemon、新 app 服务、新 provider 都无济于事；
- 期间 **ADSP 零 fault**（`dmesg` 中 `qsh|aon|og0ve|csiphy|adsp` **零条目**，
  `handling crash = 0`）—— 不是崩溃后遗，是**静默失效**。

**推测（未验证，勿当结论引用）**：
AON 会话可能依赖 ADSP 侧的一手资源（Island pool / sensor subdev 上电状态）。
provider 异常死亡时这些资源未被正常释放，导致后续任何客户端都无法重新分配，
只有 ADSP 冷启（= 重启设备）才能清零。
代码侧对应点见 `PROJECT_HISTORY.md` §3（岛页表 API 全是 `trap0(#0x1e)` 薄包装）。

---

## 五、旁支发现：supervisor 双实例的根因

现象：每次手工 `nohup` 拉起 supervisor，约 15~20 s 后变成**两个**：

```
PID=25673 PPID=1     ETIME=00:36   ← 我 nohup 的那个
PID=25845 PPID=25673 ETIME=00:21   ← 由 25673 fork 出来
```

且常驻的 `service.sh`（PID 32166）一直在跑。链条：

```
service.sh（常驻）
   └─ 拉起 supervisor.sh
        └─ 15s 周期内 am start-foreground-service → app :attention 起来
             └─ app 服务自身也会拉起 supervisor.sh   ← 第二份来源
```

**危害评估：低**（daemon 有 `pidof` 前置检查，不会双起；重复的只有保活循环本身）。
**建议**（未实施，属模块改进）：`supervisor.sh` 开头加单实例锁，
例如 `[ -e /data/adb/tb522fu_attention/.sup.lock ] && exit`，
或在 `while` 前 `exec 9>/data/adb/tb522fu_attention/.sup.lock; flock -n 9 || exit`。

---

## 六、设备状态（收尾：重启前 → 重启后）

| 项 | 重启前（断链期） | 重启后（最终） |
|---|---|---|
| 功能开关 | `enabled: true` / `adaptive_sleep: 1`（**意图开启但因断链不工作**） | 同左，**且实际生效** |
| daemon | 运行中但无事件流 | ✅ 运行中，`aon_evt` 每 15 s +100~130 B |
| AON 会话 | ❌ 未建立 | ✅ 已建立（15:42:40 `started`） |
| ADSP 崩溃 | `handling crash = 0`，dmesg 无 qsh/adsp 条目 | 同左（3 条 `EX:` 命中经查是 init `opex:` 误匹配，非崩溃） |
| SELinux | `Enforcing` | `Enforcing` |
| uptime | 84,888 s（**全程未重启**，ADSP 从未冷启） | 已重启，uptime 归零 |
| 临时文件 | `daemon2.out` 已删；`camxoverridesettings.txt` 已删 | 同左 |
| 日志占用 | `daemon.out` **605 MB** | ✅ **已删除**（释放 605 MB） |
| supervisor | 2 份（低危冗余） | ✅ 1 份 |

**处置记录**：`adb reboot` → `boot_completed=1`（约 90 s）→ AON 会话在
`15:42:40` 由 `service.sh` 开机阶段自动重建，`config.json` 的 `enabled=true` 已就位
⇒ 注视不息屏**开箱恢复，无需再下指令**。

---

## 七、对总结论的影响

- **崩溃修复结论不受影响**：`is_island=0` 的修法、10 个冷启动周期 fault=0、
  以及第 6 轮的"崩只由 `is_island` 决定"全部保持有效。
- **第 6 轮"须重启设备"由推测升级为实证**，并排除了三条更轻的替代路径。
- **新增可用性认知**：本设备的 AON 会话是**开机一次性**资源；
  provider 侧任何异常死亡都会让"注视不息屏"静默失效直到重启 —— 这是模块 v1.7
  之外的**设备层**属性，任何长期持有 AON 会话的客户端都受此约束。

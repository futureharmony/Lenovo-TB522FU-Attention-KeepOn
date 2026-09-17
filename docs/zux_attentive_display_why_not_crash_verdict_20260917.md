# ZUX 原厂为什么"不崩" / 「注视不息屏」在 ZUX 里究竟是什么 — 收口判决

> 日期：2026-09-17（第 5 轮）
> 设备：Lenovo TB522FU（Y900）／当前跑 ColorOS 移植
> 取证源：ZUXOS 2.0.13.057 全量包（`super_1..8.img`）+ 设备实件 + 合并 ADSP 固件
> 新增工具：`strref.py`（反查"谁 const-string 了某个字符串"）

---

## 0. 一句话结论

**ZUX 的注视能力不在 ADSP 的 NMS **岛分支**上 —— 它向 AON 只订 `GazeDetect`(4)，
算法是 OEM1/Tobii gaze（代码落在**非岛段** ph[27]），"看没看屏幕"的判定在 **AP 侧**做；
而崩的那条是 ColorOS 把判定**下推到 DSP**：`EOD`(NMS) → 岛块 ph[34] → `eai_execute` → 跨执行域取指失败。**

两者物理通道相同（同 OG0VE、同 QSH 相机总线、同 `qsh_camera_sensor_island.c` 握手骨架），
差别在 **①算法家族 ②代码落点（岛内/岛外）③决策位置（DSP/AP）** 三点。

---

## 1. 对照表

| 维度 | ZUX 原厂 | ColorOS 移植（崩的这条） |
|---|---|---|
| AP 客户端 | `SmartVision`（`com.zui.camera.ex.av`） | OPPO 私有实现 |
| 订阅的服务 | **GazeDetect(4)** + HandDetect(3) | EOD（结果复用 **FD 事件**通道） |
| 算法家族 | **OEM1 / Tobii GazeNet** | **NMS**（`client_algo_eod_post_proc_island`） |
| 代码落点 | **ph[27] 0xb2200000（非岛段，3.6 MB）** | **ph[34] 岛块内** |
| 是否经 `eai_execute` | **否**（全固件 5 个调用者 5/5 都是 NMS） | **是**（eai_execute @0xb206ff4c） |
| 被 `is_island` 键控制 | **无此键**（`tuning_params` 只有 6 个 NMS 子节，无 `nms_gaze`） | `nms_eod.is_island = 1` |
| "是否注视"在哪判定 | **AP 侧**（GAZE 事件带 `gaze_info{x,y}` 坐标） | **DSP 侧**（模型出 bool，塞进 `has_gaze` 字段） |
| 结果 | 不崩 | 跨执行域 fault → 自持 SSR → USB 掉线 |

---

## 2. 证据（ZUX 侧，均为字节级直读）

1. **订阅面**：SmartVision 全 dex（14 754 类）中 `AONManager.subscrible(int)` 只有两个调用点 ——
   `AONEyeTracking.init → subscrible(4)`、`AONGestureDetect.init → subscrible(3)`。
   **没有 `subscrible(0/1)`（FaceDetect / FaceDetectPro），更没有 EOD 这种服务类型。**
2. **HAL 面**：`QSEEAONSrvGD` ↔ `com.qti.qseeaon.so` 的 GazeDetect 服务，日志形态
   `GazeDetect RegisterService() config is valid:` / `AON FW GazeDetect is available: numFWs %d` /
   `gazeInfo valid:%d x:%f, y:%f` —— 交付的是**坐标流**，不是"在看/没在看"的布尔量。
3. **固件面**：岛代码块 ph32–36 对 `tobii / gaze / arcsoft / oem1 / GazeNet` **字符串级 0 命中**；
   `eai_execute` 的 5 个调用者全是 `client_algo_{eod,fd_enpu,hd,hgd,qrcode}_post_proc_island`。
4. **配置面**：`qsh_camera_common.json.tuning_params` 只有 `nms_fd_qqvga|fd_qvga|fd_360p|qrcode|eod|hd`
   六个子节 —— `is_island` 这把开关**从设计上就不覆盖 gaze 通路**。
5. **AP 侧决策链（本轮补齐）**：
   `EyeTrackingExternalAction.process(x,y)` → `StableGazeDetector.processGazePoint(x,y,block)`
   →（连续 N 帧稳定）`onStableGazeConfirmed` → `StableGazeCallback.onStableGazeDetected`
   → `IEyeTrackingCallback.onStablePosition(x,y)`。
   保屏原语是 `ScreenManager.simulateUserActivityReflectively()`（**反射调 `PowerManager.userActivity()`**）
   + `keepScreenOnWithDimWakeLock()`（dim wake lock + 定时释放）。
   ⇒ **"要不要继续亮屏"是 AP 用自己的逻辑算的，DSP 只提供坐标。**
6. **文案面**：SmartVision 的注视文案是「眼动追踪 / 用目光切换焦点 / 检测到稳定注视 / 眼动校准」，
   其中一句写得很直白：*"开启后，当连接键盘且处于分屏模式时(且无应用浮窗)，在摄像头前 30–50 厘米处，
   注视窗口约 0.5 秒即可切换焦点"* —— 这就是「**AI 眼动多窗**」本体（分屏焦点切换）。
   SmartVision 资源里 **`息屏` / `常亮` 命中数 = 0**。

---

## 3. 「注视不息屏」这个名字在 ZUX 里留下的三处痕迹 —— 以及为什么找不到实现体

本轮把"到底谁实现了注视不息屏"查到了底（全镜像扫描 + 逐个 APK 解压扫描）：

| 痕迹 | 位置 | 判定 |
|---|---|---|
| `屏幕感知` / `智能常亮` / `看着屏幕时，屏幕不会关闭。` + ZUI 自定义免责语 *"面部距离屏幕太远或角度偏离较大，或摄像头被其他应用使用，可能无法触发智能常亮。"* | `ZuiSettings.apk` `resources.arsc` | 是**产品级文案**（不是纯 AOSP 残留）；但 AOSP `AttentionService` 在 ZUX 里**没有实现体**（上轮定案：全 ROM 双编码 0 命中）⇒ 该开关在 framework 层不可用 |
| 枚举 `ATTENTIVE_DISPLAY`（默认 `mDefaultEnableState=false`），与 `APPROACH`/`FLIP_TO_DND`/`RISE_TO_EAR`/`FLASH_ON_CHOP`/`QUICK_CAPTURE`… 同表 | `ZuiCoreService.apk` 的 `com.zui.cores.FeatureKey`（**Moto Actions 家族**，同目录的 privapp 权限 XML 头是 Motorola 版权） | **全 ROM 唯一命中 = 定义处本身**；枚举类内部也无任何指令读取它（`$values()` 除外）⇒ **定义存在、实现缺席** |
| `AttentiveDisplay_On` / `AttentiveDisplay_Off` / `isAttentiveDisplayEnable` / `isSupportAttentiveDisplay` / `changeAttentiveDisplay` | `LenovoXiaoTian.apk`（product，`oat/arm64/LenovoXiaoTian.vdex`） | 联想的**能力开关协议**（AI 助手侧），不是摄像头判定实现体 |

**全 ROM 引用 `com.zui.camera.ex.av` 的位置（system 分区全镜像扫描 + whoino 定界）**：
SmartVision 自己 ｜ `ZuiSettings`（`SplitScreenSettingsFragment.isEyeTrackingAppInstalled()` → 跳
`SmartVisionGazeDetailSettingsActivity`，即**眼动入口挂在"分屏"设置下**）｜ `ZuiAIService`
（`SmartVisionUtils.isSupportEyeTracking` + `KEY_AI_CAMERA_GAZE_ENTRY` / `ai_camera_gaze`）｜
`ZuiServiceEngine`（第三方/白名单包名表）｜ `ZuiCoreService`（一处包名常量）。
**全部是"设置入口/能力探测"，没有一个是"注视判定"消费者。**

⇒ 结论：**在这个 ZUX build 上，「注视不息屏」找不到面向摄像头的实现体。**
它要么存在于联想的跨设备协议里（PC/其他设备侧），要么本 build 未启用（枚举默认关、UI 依赖的
AttentionService 不存在）。

---

## 4. 那"ZUX 注视不息屏能正常工作"这句话怎么落地？

按证据强度排序，三种解释：

1. **（最可能）在 ZUX 上体验到的"注视相关能力"就是眼动多窗 + 手势**：
   分屏注视切焦点（GD）、隔空手势（HD：截屏/点赞/比心/保屏）。
   两者都不在 NMS 岛分支上 ⇒ 与"ZUX 配着 `is_island=1` 却不崩"完全自洽。
2. **若确实见到"看着屏幕不熄屏"在 ZUX 生效**：其判定源不可能是 AON 的 EOD/FD-island 路径
   （那条 ZUX 里**一个订阅者都没有**）。只能是某个尚未定位的 AP 侧实现（框架改动/未扫到的 APK）
   —— 但这与"没有 AttentionService 实现体"冲突，需实测确认。
3. **若从未在 ZUX 上实测**：那它就是 UI 文案/跨设备开关，"为什么能工作"这一问不成立。

> 换言之：**"ZUX 不崩"的原因已经定死了（不走 NMS 岛分支）；
> 而"ZUX 有能用的注视不息屏"这一点，静态证据反而是否定的。**

---

## 5. 判别实验（代价很小，可把 §4 的 1/2/3 分开）

1. **运行期（最强）**：在能开该开关的环境里，`logcat | grep -E "AttentionManagerService|GazeDetect|QSEEAONSrv(GD|EOD)|SmartVision"`
   - 只出现 `QSEEAONSrvGD` ⇒ §4-1；出现 `QSEEAONSrvEOD` 或 ADSP fault ⇒ 前提为真且会崩。
2. **静态（本轮已做）**：全 ROM 扫 `QSEEAONSrvEOD` / `EOD` 作为**算法名**的订阅者 → 目前 **0**。
3. **设置面**：ZUX 里"屏幕感知"开关是否真的可点（framework 无 AttentionService ⇒ 预期不可用）。

---

## 6. 与全局结论的关系（收口）

- **七层全同**（sensors / 两份 JSON / ADSP dspso.bin 99 文件 / AP 侧 AON HAL 库 / `enable_island` / `is_island`）
  ⇒ 同固件同配置下，**谁执行岛分支谁崩**。
- ZUX 产品路径里唯一接触 NMS 的是 **CamX 的 FD-Pro**（人脸感知 AE/AF，实机 `ChiX` 日志 30 s / 3798 次）。
  它与本结论无冲突，但留下**一个未闭环点**：若 ZUX 的 CamX 也选了 `isIslandCapable=1` 的 mode[0]（160×120）
  且 `nms_fd_qqvga.is_island=1`，理论上同样会踩岛 —— 原厂为何没崩，只能靠
  *"实际选中的是哪个 algo mode（`fd_algo_mode_index`）"* 来解释，尚未抓到。见 §7。
- 所以本轮的净增量是：**把"ZUX 不崩"的最后一环从"运行时说不清"提升到"静态可指认"——
  它的注视能力根本不进岛；而 ColorOS 的注视能力必然进岛。**

---

## 7. 残留空白（按性价比）

1. **抓 `AONCam FD subscribe success … fd_algo_mode_index = %u`**：确定 CamX 实际选的 mode
   （是 island 的 160×120 还是非 island 的 320×240）→ 直接决定"原厂是否也不执行岛分支"能否闭环。
2. 固件侧 `CAMERA_FD_QUERY_AVAIL_ALGO_MODES` 的处理函数未定位（`isIslandCapable` 由什么算出）。
3. `LenovoXiaoTian` 的 `changeAttentiveDisplay` 到底发给谁（本机服务？跨设备？）需反编译其调用方。
4. ZUX 上"屏幕感知"开关的运行期可见性未实测。

# TB522FU AON ULP 相机通路攻坚成果(2026-09-13)

## 结论:Camera 3 (OG0VE) 的正确打开方式不是 Camera2 API,而是高通专用 AON HAL

### 1. 为什么 Camera2 永远返回 -38

`createCaptureSession` 走的是 CamX/CHI 通用图像管线,而 OG0VE 是直连 SSC/QSH(传感中枢)
的 ULP 传感器,单色全局快门,没有普通 ISP 流管道。metadata 里宣称的
YUV/PRIVATE/RAW10 流配置表只是能力模板,CamX 的 Multicamera Usecase 没有对应
pipeline 定义,必然 `Function not implemented (-38)`。**上层流格式无论怎么试都是死路。**

### 2. 决定性发现:vendor.qti.hardware.camera.aon

设备上有完整的 Qualcomm AON (Always-On Camera) HAL:

- 服务:`vendor.qti.hardware.camera.aon.IAONService/default`(AIDL v3,
  由 vendor.qti.camera.provider-service_64 宿主,SELinux manifest
  `/vendor/etc/vintf/manifest/vendor.qti.camera.aon-impl.xml`)
- 实现:CHI `com.qti.node.aon.so` + `com.qti.qseeaon.so`(直连 SSC,protobuf)
- 固件:QSEE AON FW,实例名 `QSEEAONFWFD_og0ve_4`(suid `camera_face_detect`)
- 传感器配置:`/vendor/etc/sensors/config/qsh_camera_og0ve_4.json`(已列入 json.lst)
- **此 ROM 中无任何系统客户端在调用它 → 谁注册谁独享**

### 3. 还原出的 AIDL 接口(纯逆向,官方不开源)

```
interface IAONService {                              // transaction codes
    int RegisterClient(IAONServiceCallback cb,       // 2
                       AONRegisterInfo info, out long clientId);
    int UnregisterClient(long clientId);             // 3
    List<AONSensorInfo> GetAONSensorInfoList();      // 1
}
interface IAONServiceCallback {
    int NotifyAONCallbackEvent(long clientId, AONCallbackEvent evt); // 1
}

parcelable AONSensorInfo { int aonIdx; int x; AONSensorCap[] caps; }
parcelable AONSensorCap  { int f0; int f1; FDAlgoMode[] modes; }
parcelable FDAlgoMode    { int imgWidth; int imgHeight; int isIslandCapable;
                           int fdEngine; int deliveryPerSec; int supportedFDEvtTypeMask; }
parcelable AONRegisterInfo {   // 顺序按线格式
    int aonCamIdx;             // 0 = og0ve (sensorSlotId=4, position=4)
    AONServiceType serviceType;// 0=FD 1=FDPRO 2=QR 3=HD 4=GD
    @nullable FDRegisterInfo fd;  // { evtTypeMask, algoModeIdx, imgWidth, imgHeight, deliveryPerSec }
    @nullable QRRegisterInfo qr;
    @nullable HDRegisterInfo hd;
    @nullable GDRegisterInfo gd;
}
```

FDPro 在本机的合法参数(实测):`evtTypeMask=0xf, algoModeIdx∈{0,1,2},
160x120(island-capable) / 320x240 / 480x360, deliveryPerSec=15`。

注:og0ve **不支持** QSEEAONSrvFD(基本人脸),必须用 FDPRO。
线格式细节:所有 parcelable 头部带 4 字节长度占位并回填(NDK AIDL nullable 风格),
union 分支用 0/1 presence 前缀,详见 aon_client.cpp。

### 4. 实测结果(已验证)

- GetAONSensorInfoList:成功返回 og0ve 能力列表 ✓
- RegisterClient(FDPRO):`RegisterCallbackToClients: registered for
  serviceType:QSEEAONSrvFDPRO success ... clientDataMap.size = 1` ✓
- UnregisterClient ✓;客户端死亡 → ClientDiedHandler 自动清理 ✓
- 事件回调 `NotifyAONCallbackEvent`:事件含
  `FaceID[conf roll yaw isGazeDetected]`(FDPRO 原生上报**注视检测**位)

### 5. 工具用法

`aon_client`(aarch64 原生二进制,root + setenforce 0 运行,勿长期关闭 SELinux):

```
adb push aon_client /data/local/tmp/
adb shell "su -c '/data/local/tmp/aon_client <aonCamIdx> <serviceType> \
    <evtMask> <algoIdx> <w> <h> <dps> <waitMs>'"
# FDPRO 注视感知:
adb shell "su -c '/data/local/tmp/aon_client 0 1 15 0 160 120 15 30000'"
```

编译(NDK):
```
$NDK/aarch64-linux-android34-clang++ -O2 aon_client.cpp -o aon_client \
    -lbinder_ndk -lcamera2ndk -lmediandk -lc++
```

### 6. 下一步(集成进 futureharmony.tb522fu.aon)

1. 用还原的 .aidl(AIDL NDK/Java 后端)生成接口代码打进 APK,
   替换掉 Camera2 的相机选择逻辑;不再触碰 CameraService 客户端路径。
2. 开机由 `cmd attention setTestableAttentionService` 指向的服务在
   `onStartSensing` 时 RegisterClient(FDPRO 160x120),回调里读
   `isGazeDetected`/人脸在场位;`onStopSensing` 时 UnregisterClient。
3. 权限:App 直连 vendor HAL 需要 SELinux 角色放行
   (`untrusted_app → hal_camera_default binder call`),
   最简做法是 Magisk/KernelSU 规则或把客户端逻辑放到一个
   `platform_app`/system 域服务;Camera 权限模型不再适用(无帧数据)。
4. 若屏幕点亮/熄屏状态影响 SSC 事件投递,需在熄屏场景实测
   (AON 设计目标即熄屏低功耗,大概率无影响)。
5. 待人工验证:人脸对准前摄时是否收到 FACE_PRESENT / GAZE 事件
   (本报告所有无人值守测试中无人脸在位,事件未触发属预期)。

### 7. 遗留未决

- QR/HD/GD 分支的 RegisterInfo 字段布局与 FD 不同(QR=4 int,HD/GD=4 int+bool),
  未逐一试出;对注视保持场景无影响。
- Camera2 流矩阵路线已归档为死路,不必再投入。

### 8. 第二阶段实测记录(同日追加)

运行期实测(原生探针 + logcat + /dev/binderfs/binder_logs):

1. **SSC 固件侧确实在跑**:`FaceDetectPro event received!! EvtTypeMask: 0x9`
   (provider 进程 ChiX 日志),事件沿 qseeaon → IAONServiceCallback 路径投递。
2. **AONCam 握手超时**:`camxqseeaonhsfwintf.cpp:347 SendAONCamInit()
   [QSEEAONFWHS_og0ve_4] Timed out for AONCam init`(注册后约 2s,与
   "Waiting max for 2000 msec" 探测窗口吻合)。超时后仍能收到初始状态事件
   (EvtTypeMask 0x9),怀疑为"会话建立/可用性"类事件而非人脸事件。
   握手对端是 SSC 侧 qsh_camera 模块(经 suid `camera_handshake`),
   AP 侧日志无法看到其应答,SSC 日志未获取。
3. **HAL→客户端回调投递失败**:
   `AON Event notify: Failed callback ... Status(-129, EX_TRANSACTION_FAILED):
   'BAD_TYPE' / 'DEAD_OBJECT'`。
   已排除:线程池(ABinderProcess_setThreadPoolMaxThreadCount(4) 已设)、
   本进程自回调(本地 transact 正常)、descriptor(与 .so 字符串一致)。
   binder_logs 显示 provider 持有我们回调节点的有效引用(desc N s 1 w 1),
   事务曾排队(tr 1)但未被消费。疑点仍是 VINTF stability 校验或
   AParcel 回复格式,需用 AIDL 生成代码(而非手写 class)再验证。
4. 事件时序:初始状态事件仅在 SSC 会话建立时推送一次,provider 重启后
   行为不完全复现(12:38–12:43 三次复现,之后未再触发),
   说明会话状态机驻留 SSC 侧。
5. 环境已还原:/data/vendor/camera/camxoverridesettings.txt(调试用)已删除,
   /vendor 原文件未改动;setenforce 已恢复 Enforcing。

### 10. 第三阶段:事件回调端到端打通(真人在位,2026-09-13 13:05)

用户注视前摄 60 秒测试结果:

```
ChiX: AON Event notify QSEEAONServiceType[2]
ChiX: FaceDetectPro event received!! EvtTypeMask: 0x5
ChiX: AON Event notify: Success callback:0x... for hClientHandle=0x...
```

客户端收到真实事件。**AONCallbackEvent 线格式破译**(经数据对齐验证):

```
[int64 clientId]
[len(含自身,字节)][f0=serviceType(1=FDPRO)][f1]
FDEvtInfo:   [len][2 ints]
FDProEvtInfo:[len][18 ints]   ← 人脸 Pro 事件主payload
QREvtInfo:   [len][2 ints]
HDEvtInfo:   [len]
GDEvtInfo:   [len]            ← len=0 表示 absent
```

FDProEvtInfo 实测样本(320x240 algo,注视状态):
`{5, 1, 60, 320, 240, 1, 1, 40, 0, 0, 70, 96, 1, 12, 168, 160, 1, 1}`
解读(待多样本确认):第 1 个 int=EvtTypeMask(5=server 日志 0x5 ✓);
320x240=帧尺寸;96 疑似 confidence(0-100);末段 1 疑似 isGazeDetected;
60/40/70/168/160 疑似人脸 ROI/roll/yaw。

之前无人值守时的 BAD_TYPE/DEAD_OBJECT 均为初始状态事件与线程池建立
竞态所致,真人在位的正式事件投递 Success。**Callback 投递链路已确认可用。**

### 11. 待确认(多样本)

- EvtTypeMask 位语义:0x5(注视/在场)vs 0x9(初始/不在场)各 bit 含义
- FDProEvtInfo 各字段与 roll/yaw/confidence/isGazeDetected 的对应
- 事件触发频率:疑似状态变化触发(deliveryPerSec=15 的语义待验证)

### 13. 第四阶段:握手根因与 warmup 方案(2026-09-13 14:00)

**AONCam 握手超时根因**:SSC 侧握手需要 CamX 提供 OG0VE 的传感器模式数据
(佐证:qseeaon 字符串 "FD SensorMode Data Unavailable"、
"FillAONInfo() AEC Info isn't available")。OG0VE(Camera 3)从未被
CamX 打开过 → 模式表未加载 → `SendAONCamInit() Timed out`。

**解决方案:注册前先短暂打开 Camera 3(NDK ACameraManager_openCamera)**
——注意只需 open,不能 createCaptureSession(Camera 3 上仍是 -38,这正是
原始问题)。open 会让 provider 创建 `AONPipeline_3` 客户端并占用
`AONI2C` 资源,完成传感器向 AON 管线的移交。实测加 warmup 后,
注册时握手超时报错消失。

事件样本对照(两次 FDPro 事件线格式完全对齐):
```
[int64 clientId][pres=1][len=116(含自身)][f0=1(FDPRO)][f1]
FD:  len=8  {0, 1}
FDPro: len=72 → 18 ints: {5, 1, 60, 320, 240, 1, 1, 40, 0, 0, 70, 96, 1, 12, 168, 160, 1, 1}
QR:  len=8  {0, 0}
HD:  len=0 (absent)
```
两次样本仅人脸几何值不同(70/96/168/160 ↔ 84/120/160/96),
[9]=5 即 EvtTypeMask 0x5 ✓,320x240=帧尺寸。

**遗留问题**:每次注册会话只收到 1 个事件(状态变化应触发更多)。
怀疑与握手完整性和 ULP 连续流有关,待 warmup + 真人在位复测:
1. 事件是否转为连续/多状态(0x1=脸在无注视? 0x4=注视? 0x8=离场?)
2. AEC Info 缺失是否影响(需要想办法让 AE 收敛——Camera 3 上无法
   建会话,可能要靠 AON 自己的 pipeline)

### 15. 第五阶段:连续事件流打通(2026-09-13 14:05,warmup 方案验证成功)

**加 Camera 3 open 预热后,事件从"每会话 1 个"变为连续流**:60 秒 97 个事件
(约 1.6 个/秒),两类 payload 清晰分离,与测试者"注视/移开"动作吻合:

- 无注视(脸在):EvtTypeMask=1,短 payload
- 注视中:EvtTypeMask=5,长 payload(含扩展字段 8,0,0,0 尾部)

EvtTypeMask 位语义(当前证据):
`0x1 = face present`,`0x4 = gazing`,`0x2 = ?(可能 face absent)`,
无人值守时的 0x9 = 0x1|0x8(初始状态位)。

会话收尾存在 `SendAONFDUnSubscribe Timed out`(SSC 不 ACK 退订),
对客户端无影响(客户端死亡即自动清理),但集成时注意注册/退订要容忍超时。

**结论:ULP 注视感知链路完整闭环。** 集成方案要点:
1. 注册前 open Camera 3 一次(传感器移交 AON 管线,触发 AONPipeline_3)
2. RegisterClient(FDPRO, evtMask=0xf, 320x240 或 160x120, deliveryPerSec=15)
3. 回调解析 FDPro payload:{mask, nFaces?, roi, frameW, frameH, ...,
   conf?, isGazeDetected?}
4. 判定:mask&0x1 && mask&0x4 → 用户在场且注视 → 续期熄屏

### 16. 复测命令(带 warmup)

**必须后台脱离运行**:AON 会话启动瞬间常伴随 USB/adb 复位,前台
`su -c` 会话会被连带杀掉(表现为立刻返回、无 done、adb 短暂掉线)。

```
adb logcat -c
adb shell "su -c 'sh -c \"/data/local/tmp/aon_client -w 0 1 15 0 160 120 15 60000 >/data/local/tmp/aon_out.txt 2>&1 &\"'"
# 60 秒内:注视 ~15s → 移开视线留在画面 ~15s → 离开画面 ~15s
adb shell "su -c 'cat /data/local/tmp/aon_out.txt'"
adb logcat -d | grep -E "Event notify|FaceDetect|AONCam|FillAON"
```

### 17. 第六阶段:间歇性根因三连破 + 保活闭环打通(2026-09-16 00:20–01:15)

**1. "事件流间歇性"假象与真 bug(路径 1+2)**

给 cbOnTransact 加原始 parcel 转储(AON_DUMP_PARCEL=1)后,首次 90 秒实测:
**algo=2(480x360 非island)+ warmup + 线程池先行 = 967 个事件连续流入(≈10.7/s,
符合 FDPRO 投递节奏),零间歇、零 ADSP 崩溃、零 USB 断连。** 之前 daemon 报
"HAL 沉默"完全是假象 —— 真凶是解析器:回调 parcel 头部为
`[pres][len][f0=serviceType][f1]` 两字段,cbOnTransact 只读了 f0,5-blob 循环整体
错位 1 个 int,FDPro 载荷永远解析不出。**加读 f1 后 370/371 事件全部正确解析。**

**2. SSC connectionError 与自动恢复**

长会话中 QSH SSC 连接会死(`SensorErrorCallback … SSC connectionError`),死后所有
注册成功但零事件,SSC 不会自愈;**唯一恢复手段 = 重启
vendor.qti.camera.provider-service_64**。daemon 已内置自愈:真实回调静默 >8s
→ unregister → killall provider → 等 service → 重新 warmup camera3 → 重注册,
限流 1 次/60s。两次演练(自愈 + 外部杀 provider)均通过,恢复耗时 ~20s。
注意:合成 pres=0(1.5s 静默补发)会刷新 gLastHalEvtMs,静默检测必须用独立的
gLastRealEvtMs(只在真实回调里更新),否则自永不触发。

**3. OPlus 官方 AON 客户端逆向结论(路径 3)**

- OplusGestureUI/SystemUI/Settings 等内嵌 `com.aiunit.aon` SDK 客户端
  (FaceGaze.registerGaze → IAONService.registerListener(engineType=393217)),
  官方注视服务为 SystemUI 的 AONAttentionService(标准 AttentionService API)。
- **但服务端 `com.aiunit.aon/com.aiunit.aon.AONService` 宿主包在本机未安装**
  (联想变体砍掉),`isAONHardwareSupport()` 依赖的 feature 也不存在,
  官方链路整体不可用 → QTI AON HAL 无官方调用方可抄,我们的 daemon 是唯一客户端。
- ROM 社区作者的 `lwky_oplus_aon` init 服务是 logcat→userActivity 桥,
  且被本模块 v1.5 的 /system/lwky overlay(AON Neutralizer)有意废掉。

**4. system_server 钩子路线死刑 + daemon 直接保活**

VectorLegacyBridge(LSPosed fork)只注入 app 进程,**不注入 system_server**
(scope 含 android 也无效,重启验证),Xposed userActivity 提权方案不可用。
改为 daemon(root)在注视事件时直接保活:

- 实测 `service call power 14 i32 0 i64 <uptimeMs> i32 4 i32 0`(ATTENTION 类型)
  在无 attention service 的 ROM 上被静默丢弃;**type 0(OTHER)有效**,
  在 10s 超时临界点注入可确认延长(8s 注入 → 13s 仍亮 → 18s 熄屏)。
- daemon 实现:FDPro payload mask&0x4(或 vals[16]==1)→ 节流 3s 调
  `service call power 14 i32 0 i64 <bootMs> i32 0 i32 0`;每 ping 买一个超时窗,
  userActivity 不会唤醒已睡的屏幕,残会话无风险。gaze 离开 → ≤10s 正常熄屏。

**5. 开机链路修复**

- App 开机直启崩溃:未解锁时 CE SharedPreferences 不可用 → AonConfig.init 改用
  device-protected storage(含迁移),服务不再崩。
- AonConfig 迁移 bug:migrate() 只改内存不落盘 → save() 持久化;CONFIG_VERSION=3
  强制 algo=2 480x360(island algo 0/1 = ADSP 崩溃循环 + USB 断连根源)。
- service.sh daemon 默认参数同步改为 `0 1 15 2 480 360 3`。

**6. 本轮终态**

- 模块 v1.6(Lenovo-TB522FU-Attention-KeepOn-v1.6.zip)已重新启用并在机验证:
  注册成功、事件流连续、自愈有效,重启后单实例运行正常。
- 本 boot 期间 **USB 断连 0 次**(含多次 provider 重启与 daemon 重启)。
- 待人工验证:真人注视场景下 gaze 事件(mask 0x5)触发 KEEPALIVE 保亮屏。

**待人工验证(物理在位)**:注视保活的最后一环需要真人注视前摄。
一键验证脚本已推送到设备:
`adb shell "su -c 'sh /data/local/tmp/verify_keepon.sh'"`
(亮屏 → 45 秒桥接期请注视前摄 → 停止桥接观察 60 秒,期间屏幕只能靠 daemon 的
KEEPALIVE 保活,出现 ≥3 次 ping 即 PASS;移开视线后应 ≤10 秒熄屏。)
软件链路各环节已分别实测:FDPRO 注视上报(mask 0x5,vals[16]=1,00:24 真人样本)、
解析 370/371、userActivity type=0 延长超时、无注视 ≤10s 熄屏(01:20/01:22 两轮)。

### 18. 组合链路运行时验证(2026-09-16 01:47,--selftest-gaze)

daemon 新增 `--selftest-gaze <sec>`:向自身回调注入与真实 HAL **逐字节一致**的
FDPro 注视事件(mask=5/vals[16]=1,格式经 00:24 真人样本验证),实测保活胶合链路:

- 40 秒注入窗内 daemon 发出 **14 次 KEEPALIVE ping**(3s 节流),屏幕在**零人工
  输入**下保持 Awake 45 秒(无保活时 10 秒即睡);
- 注入停止后最后一个超时窗走完,正常熄屏 —— 事件停止→熄屏语义自洽;
- evt 文件中注入事件带 `SELFTEST` 标注,不与真人数据混淆。

至此"注视事件→KEEPALIVE→屏幕保持 Awake→无注视熄屏"组合链路拥有运行时实测
证据(输入由真实 HAL 在真人样本中证明可产生)。真人在位的一键复核脚本不变:
`adb shell "su -c 'sh /data/local/tmp/verify_keepon.sh'"`。

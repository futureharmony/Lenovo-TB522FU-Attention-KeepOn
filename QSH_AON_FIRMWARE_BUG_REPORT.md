# ADSP / QSH `AonCam_0` 固件异常 — Bug Report (for OPPO / Qualcomm)

## 摘要 (Summary)

在 **ColorOS 16.1.0 (OPD2409_11.C.34)** 上，一旦有客户端通过
`vendor.qti.hardware.camera.aon.IAONService` 注册 **OG0VE 低功耗 AON 摄像头
(FDPRO)** 会话，ADSP 上的 QSH 进程 (`qsh_process`) 会立即、持续地触发
fatal exception，导致 ADSP 反复 PDR 重启，并连带使 USB / Type-C 状态机失步
（宿主端表现为 ADB / USB 周期性断连）。

该异常与客户端行为无关：**无需 CamX warmup、单次会话、不重复注册** 也会复现，
建立会话后数秒内即崩溃，且每次崩溃签名完全一致。

## 设备与固件信息 (Device / Firmware)

| 项目 | 值 |
|---|---|
| 机型 (ro.product.model) | `OPD2409` |
| 硬件 board | Qualcomm QRD `Sun` (device-tree: `Qualcomm Technologies, Inc. Sun QRD SKU2 V8 Power Grid`) |
| 平台 | `sun` / `pakala`, soc_id `639`, machine `SUNP` |
| SoC | SM8750P (Snapdragon 8 Elite) |
| ROM | `OPD2409_16.0.9.400(CN01)` / ColorOS `V16.1.0` |
| 完整 build | `OPD2409domestic_11_16.0.9.400(CN01)_2026070318430000` |
| OTA 版本 | `OPD2409_11.C.34_1340_202607031843` |
| Security patch | 2026-07-01 |
| ADSP 固件 | `LPAIDSP.HT.1.1-01182-PAKALA-1` |
| Sensors 固件 | `SENSORS.LA.5.0.r2-03600-pakala.0-1` |
| Camera 固件 | `CAMERA.LA.5.0.r1-07900-pakala.0-1` |
| Firmware build | `Pakala.LA.2.0.r1-00160-STD.PROD-1` (2026-07-02) |

## 现象 (Symptom)

宿主端 (ADB/USB) 周期性掉线，设备侧 kernel log：

```
USB_STATE=DISCONNECTED
USB_STATE=CONNECTED
PD_ERR: qsh_process : EX:qsh_process:0x4:AonCam_0:0x10000014:PC=0xb2268d30:LR=0xb20700a4
qcom_q6v5_pas 3000000.remoteproc-adsp: fatal error received: err_qdi.c:1215:EX:qsh_process:0x4:AonCam_0:0x10000014:PC=0xb2268d30:LR=0xb20700a4
remoteproc remoteproc1: crash detected in 3000000.remoteproc-adsp: type fatal error
remoteproc remoteproc1: handling crash #N in 3000000.remoteproc-adsp
fastrpc: fastrpc_pdr_cb: msm/adsp/sensor_pd (sensors_pdr_adsp) is down for PDR on adsp
... (ADSP 恢复) ...
USB_STATE=DISCONNECTED   <-- ADSP 重启连带 Type-C 控制器失步
```

## 崩溃签名 (Crash signature)

每次崩溃完全一致：

```
fault task : qsh_process
error      : EX:qsh_process:0x4:AonCam_0:0x10000014
PC         : 0xb2268d30
LR         : 0xb20700a4
stack      : 0xb2268d30 0xb20700a4 0xb20771e0 0xb2076bc0
             0xb207afec 0xb20704ec 0xb207d4b4 0xb2074074 0xb220ae7c
```

- `AonCam_0` = AON 摄像头实例 0（OG0VE）。
- 建立会话后 **数秒内** 即崩溃，之后每次 ADSP 恢复即再次崩溃，形成 crash loop。
- 关闭 AON 客户端后，ADSP 不再崩溃，USB 恢复稳定。

## 复现步骤 (Reproduction)

前置：root（KernelSU/Magisk），无需修改 SELinux（enforcing 下可复现）。

```bash
# 1. 使用 raw libbinder_ndk 客户端注册 FDPRO 会话（无 warmup，单次会话）
adb shell "su -c 'setsid sh -c \"/data/local/tmp/aon_client 0 1 160 120 3 15 0 120000 0 >/data/local/tmp/aon_out.txt 2>&1 &\"'"

# 2. 观察 ADSP 崩溃
adb shell "su -c 'dmesg | grep -A2 AonCam_0'"
# 或观察 minidump 数量增长
adb shell "su -c 'ls /data/vendor/ramdump/*.elf | wc -l'"

# 3. 停止
adb shell "su -c 'pkill -9 -f aon_client'"
```

注册本身成功（`RegisterClient` 返回 `clientId`），随后即触发固件异常。

参数：`aonCamIdx=0` (og0ve), `serviceType=1` (FDPRO), `w=160 h=120`,
`deliveryPerSec=3`, `evtTypeMask=0xf`。

## 已排除的客户端侧因素 (Ruled out)

| 假设 | 结论 |
|---|---|
| 未在灭屏时 unregister | 否 — 会话保持期间持续崩溃 |
| 高帧率 / 总线负载 | 否 — dps 从 15 降到 3 仍崩溃 |
| CamX warmup 冲突 | **否 — 完全不做 warmup、单次会话、不重复注册，仍 27s 内崩溃 5 次** |
| SELinux | 否 — enforcing 下复现 |
| 注册参数非法 | 否 — 参数与厂商能力表一致，注册成功 |

## 硬件/配置一致性 (Config vs hardware — 已核对一致)

设备树 AON 摄像头节点：
`/proc/device-tree/soc/qcom,qupv3_2_geni_se@8c0000/i2c@884000/qcom,cam-sensor4`

| 项目 | 设备树 | 厂商 QSH 配置 `qsh_camera_og0ve_4.json` |
|---|---|---|
| AON camera id | `aon-camera-id = 0` | `aonCamIdx = 0` ✅ |
| Slot | `cell-index = 4` | 配置名 `_4` ✅ |
| I2C 地址 | `reg = 0x60` | `slave_config = 96` (=0x60) ✅ |
| Bus | `i2c@884000` | `bus_type = 0` (i2c) ✅ |
| SoC | soc_id 639 | `soc_id [618, 639]` ✅ |
| MCLK/Reset | `CAMIF_MCLK4` / `CAM_RESET4` | 有对应 GPIO 配置 |

即：**硬件接线与厂商配置完全匹配**，故排除板级配置错误，问题定位在 ADSP/QSH
固件 `AonCam_0` 处理流程。

相关 blob 均已存在：
`com.qti.sensor.og0ve.so`, `com.qti.sensormodule.qtech_og0ve.bin`,
`com.qti.tuned.qtech_og0ve.sun.bin`, `og0ve_4.pb`,
`com.qti.qseeaon.so`, `com.qti.node.aon.so`,
`vendor.qti.hardware.camera.aon-V1/V2/V3-ndk.so`,
`qsh_camera_model_fd_*.so`, `sns_tppe.so`.

## 影响 (Impact)

- OG0VE 低功耗 AON 摄像头（人脸/注视感知）**完全不可用**。
- ADSP 崩溃连带 USB/Type-C 失步，导致 ADB/外设周期断连。
- 受影响功能：注视不熄屏、在场检测等所有依赖 AON FDPRO 的场景。

## 附件 (Attachments)

- ADSP minidump（Qualcomm Secure Minidump，加密）：
  `/data/vendor/ramdump/remoteproc-adsp-md_<timestamp>.elf`（86 MB/个）
  代表性样本已保留于 `/data/vendor/ramdump/keep/`。
- OLC 原始 dump：`/data/persist_log/DCS/minidump/minidump.bin` (512 MB)。
- kernel 崩溃上下文见上文 `dmesg` 片段。

## 请求 (Request)

1. 确认 `LPAIDSP.HT.1.1-01182-PAKALA-1` 中 `qsh_process` / `AonCam_0`
   路径的 `0x10000014` 异常原因（PC `0xb2268d30`）。
2. 提供修复该 QSH AON 摄像头异常的 ADSP / sensors 固件版本。
3. 如需，可通过 OLC (`minidumpraise2olc` / `sys.oplus.olc.packupminidump`)
   上报加密 minidump 以供 Qualcomm 解密定位。

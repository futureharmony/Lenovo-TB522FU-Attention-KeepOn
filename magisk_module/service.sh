#!/system/bin/sh
##########################################################################################
# Lenovo Legion Y900 (TB522FU) 注视不熄屏增强模块 - 开机常驻自愈与日志服务
# Author: futureharmony
##########################################################################################

# 1. 等待系统完全启动
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done
sleep 3

CONF_DIR="/data/adb/tb522fu_attention"
CONF_FILE="$CONF_DIR/config.json"
LOG_FILE="$CONF_DIR/attention.log"
CTRL_BIN="/system/bin/attention_ctrl"
MODDIR="/data/adb/modules/tb522fu_attention_keepon"

mkdir -p "$CONF_DIR" 2>/dev/null

# 1.5 开机失败自动回退：走到这里 = 本机成功 boot_completed，回退计数窗口重置。
#     （计数逻辑见 post-fs-data.sh §0；连续 3 次开机失败时模块已自我禁用，
#      不会再进本脚本，还原动作由 service.d/tb522fu_rollback_cleanup.sh 接管。）
if [ -f "$MODDIR/boot_fail_count" ]; then
    PREV_CNT=$(cat "$MODDIR/boot_fail_count" 2>/dev/null)
    echo 0 > "$MODDIR/boot_fail_count" 2>/dev/null || true
    [ -n "$PREV_CNT" ] && [ "$PREV_CNT" != "0" ] && \
        echo "[$(date '+%F %T')] [ROLLBACK] boot ok, fail-counter reset (was $PREV_CNT)" >> "$LOG_FILE" 2>/dev/null || true
fi

# 2. 底包 AON 服务净化（仅针对 lwky_oplus_aon 初始化服务——它监听日志做唤醒
#    前置检测并托管 AON 相机，与本模块的 daemon 冲突）。
#    注意：流光灯控制（lwky.FlowingLight.Control，后置模组灯环 aw22xxx LED）
#    与 AON 无关，本模块不做任何干预、不禁用、不卸载（2026-09-17 定案）。
setprop ctl.stop lwky_oplus_aon 2>/dev/null || true
pkill -9 -f lwky-oplus-aon 2>/dev/null || true
pkill -9 -f lwky_oplus_aon 2>/dev/null || true

pkill -9 -f "aon_watchdog" 2>/dev/null || true
pkill -9 -f "aon_daemon" 2>/dev/null || true  # 单实例：清掉本模块旧 daemon，下方重拉

rm -f /data/adb/tb522fu_attention/aon_watchdog.sh 2>/dev/null || true
echo "[$(date '+%F %T')] [SYSTEM] legacy lwky AON service neutralized" >> "$LOG_FILE" 2>/dev/null || true

# 2.5 island registry 漂移自检（防 SSC 回写 is_island 漂回 1；post-fs-data §3
#     已做过修正，此处为二次防线——发现漂移则立即重修并告警）
REG="/mnt/vendor/persist/sensors/registry/registry"
ISLAND_OLD='"is_island":{"type":"int","ver":"0","data":"1"}'
ISLAND_NEW='"is_island":{"type":"int","ver":"0","data":"0"}'
if [ -d "$REG" ]; then
    for _m in nms_eod nms_fd_qqvga nms_fd_qvga nms_qrcode; do
        _f="$REG/qsh_camera_common.json.qsh_camera.tuning_params.$_m"
        [ -f "$_f" ] || continue
        if grep -q "$ISLAND_OLD" "$_f" 2>/dev/null; then
            sed "s/$ISLAND_OLD/$ISLAND_NEW/" "$_f" > /data/adb/tb522fu_attention/.island_tmp \
                && cat /data/adb/tb522fu_attention/.island_tmp > "$_f"
            rm -f /data/adb/tb522fu_attention/.island_tmp 2>/dev/null
            echo "[$(date '+%F %T')] [WARN] island drift detected on $_m, re-fixed (1 -> 0)" >> "$LOG_FILE" 2>/dev/null
        fi
    done
fi

# 3. 确保基础配置与 IPC 管道存在
if [ ! -f "$CONF_FILE" ]; then
    echo "{\"enabled\": true, \"installed_at\": \"$(date "+%Y-%m-%d %H:%M:%S")\"}" > "$CONF_FILE"
fi

AON_DIR="/data/data/futureharmony.tb522fu.aon/files"
mkdir -p "$AON_DIR" 2>/dev/null
chmod 777 "$AON_DIR" 2>/dev/null || true
# Fresh IPC state: never replay a previous session's command or stale events
rm -f "$CONF_DIR/aon.pid" "$CONF_DIR/aon.lock" 2>/dev/null || true
: > "$AON_DIR/aon_cmd" 2>/dev/null || true
: > "$AON_DIR/aon_evt" 2>/dev/null || true
chmod 666 "$AON_DIR/aon_cmd" "$AON_DIR/aon_evt" 2>/dev/null || true

# 4. SELinux 与权限保障
if command -v ksud >/dev/null 2>&1; then
    for rule in \
        "allow untrusted_app hal_camera_default binder call" \
        "allow untrusted_app hal_camera_default binder transfer" \
        "allow untrusted_app hal_camera_default fd use"; do
        ksud sepolicy patch "$rule" >/dev/null 2>&1 || true
    done
elif command -v magiskpolicy >/dev/null 2>&1; then
    magiskpolicy --live \
        "allow untrusted_app hal_camera_default binder call" \
        "allow untrusted_app hal_camera_default binder transfer" \
        "allow untrusted_app hal_camera_default fd use" >/dev/null 2>&1 || true
fi

settings put global hidden_api_policy 2 >/dev/null 2>&1 || true
pm grant futureharmony.tb522fu.aon android.permission.CAMERA >/dev/null 2>&1 || true
pm grant futureharmony.tb522fu.aon android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true

# 5. 启动 Camera 3 AON 硬件守护进程 (单例后台，独占锁保护)
if [ -f "$MODDIR/aon_daemon.bin" ]; then
    chmod 755 "$MODDIR/aon_daemon.bin"
    if ! pidof aon_daemon.bin >/dev/null 2>&1; then
        nohup "$MODDIR/aon_daemon.bin" --daemon \
            "$AON_DIR/aon_cmd" "$AON_DIR/aon_evt" \
            0 1 15 2 480 360 3 </dev/null >/dev/null 2>&1 &
        echo "[$(date '+%F %T')] [AON-ULP] Camera 3 AON hardware daemon started (algo=2 480x360 non-island)" >> "$LOG_FILE" 2>/dev/null || true
    fi
fi

# 6. 清理冗余的旧脚本守护进程
pkill -9 -f "attention_ctrl daemon" 2>/dev/null || true

# 7. 启动常驻感知与防息屏服务 (维持硬件注视续屏与系统设置同步)
am start-foreground-service -n futureharmony.tb522fu.aon/.AONAttentionService >/dev/null 2>&1 || true

# 7.5 Demand Mode 契约：component 必须指向我们的服务，framework 才会在熄屏超时
#     时回调 onCheckAttention。不依赖 LSPosed hook（可能未启用），root 直接写。
if [ "$(grep -o '"enabled"[[:space:]]*:[[:space:]]*true' "$CONF_FILE" 2>/dev/null)" ]; then
    settings put secure adaptive_sleep 1 2>/dev/null || true
    settings put secure attention_service_component \
        "futureharmony.tb522fu.aon/futureharmony.tb522fu.aon.AONAttentionService" 2>/dev/null || true
fi

# 8. 后台服务监督 + ADSP 崩溃风暴熔断（逻辑见 supervisor.sh）：
#    - 保活：enabled 时 :attention 进程消失即重拉（Demand Mode 契约依赖）。
#    - 熔断：ADSP 新增 crash >= 3 → 杀掉全部 AON 客户端并进入 10 分钟冷静期。
#      （AonCam fault 是"客户端活着就无限循环"的自持风暴，只有客户端死才停；
#        这是曾造成一夜 80% 掉电的故障形态，此处作为最后防线。）
if [ -f "$MODDIR/supervisor.sh" ]; then
    chmod 755 "$MODDIR/supervisor.sh" 2>/dev/null || true
    nohup sh "$MODDIR/supervisor.sh" </dev/null >/dev/null 2>&1 &
fi

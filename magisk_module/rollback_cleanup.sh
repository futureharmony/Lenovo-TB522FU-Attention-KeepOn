#!/system/bin/sh
##########################################################################################
# Lenovo Legion Y900 (TB522FU) 注视不熄屏增强模块 - 开机失败自动回退清理脚本
#
# 安装位置: /data/adb/service.d/tb522fu_rollback_cleanup.sh
# 关键特性: service.d 脚本独立于任何模块的启停状态执行 —— 即使本模块已被
#           disable（含 bootloop 自动禁用），本脚本仍会在每次开机被拉起。
#
# 职责: 模块因连续开机失败被自动禁用（模块目录存在 AUTO_DISABLED 标志）后，
#       在系统成功启动时把 secure 设置还原到安全态，避免残留配置指向
#       已停用模块的组件：
#         1) 删除 attention_service_component 覆盖 → framework 回落到 ROM 默认值
#            （AOSP 逻辑: secure 无值时使用 config_attentionServiceComponent）。
#         2) adaptive_sleep 置 0、tb522fu_aon_enabled 置 0（总开关关闭）。
#         3) 清掉可能在跑的 aon_daemon.bin 与无头服务残留。
#       动作幂等（AUTO_CLEANED 标志），且完全可逆：用户在管理器里重新启用模块
#       并重启后，service.sh 会按正常流程重写全部设置。
##########################################################################################

MODDIR="/data/adb/modules/tb522fu_attention_keepon"
CONF_DIR="/data/adb/tb522fu_attention"
LOG_FILE="$CONF_DIR/attention.log"

[ -f "$MODDIR/AUTO_DISABLED" ] || exit 0
[ -f "$MODDIR/AUTO_CLEANED" ] && exit 0

# 等待系统启动完成（最多 5 分钟）
N=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$N" -lt 150 ]; do
    sleep 2
    N=$((N+1))
done
[ "$(getprop sys.boot_completed)" = "1" ] || exit 0
sleep 5

# 1. 还原 secure 设置到模块部署前状态
settings delete secure attention_service_component >/dev/null 2>&1 || true
settings put secure adaptive_sleep 0 >/dev/null 2>&1 || true
settings put secure tb522fu_aon_enabled 0 >/dev/null 2>&1 || true

# 2. 清理运行期残留（模块 disable 后 supervisor 不会再拉起，但保险起见）
pkill -9 -f aon_daemon.bin >/dev/null 2>&1 || true
pkill -9 -f "tb522fu_attention_keepon/supervisor.sh" >/dev/null 2>&1 || true
am force-stop futureharmony.tb522fu.aon >/dev/null 2>&1 || true

touch "$MODDIR/AUTO_CLEANED" 2>/dev/null
echo "[$(date '+%F %T')] [ROLLBACK] cleanup done: attention_service_component deleted, adaptive_sleep=0, tb522fu_aon_enabled=0" >> "$LOG_FILE" 2>/dev/null || true
exit 0

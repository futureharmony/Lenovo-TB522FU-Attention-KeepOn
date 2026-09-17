#!/system/bin/sh
# AON 后台服务监督 + ADSP 崩溃风暴熔断 (v2)
# 由 service.sh §8 开机 nohup 启动；本文件也可手工 nohup 运行。
#
# 职责：
# 1. 保活：enabled=true 时 :attention 进程消失即重拉（Demand Mode 契约依赖进程存活）。
# 2. 熔断：15s 内 ADSP 新增 crash >= 3 → 杀掉全部 AON 客户端（daemon + app）。
#    D4 实证：AonCam fault 是"客户端活着就无限循环"的自持风暴（~5s/次 SSR），
#    state=stop 无效，唯一停法 = 客户端进程死亡。这正是曾造成一夜 80% 掉电的
#    故障形态（ADSP 反复重载 29MB 固件 + USB/UCSI 复位 + ToF 紊乱唤醒手势服务）。
#    熔断后 10 分钟冷静期内不重拉，防止"重拉→再风暴"的功耗循环。
CONF=/data/adb/tb522fu_attention/config.json
LOG=/data/adb/tb522fu_attention/attention.log
FUSE_TS=/data/adb/tb522fu_attention/.fuse_ts
PKG=futureharmony.tb522fu.aon
BASE_CRASH=0
FIRST=1

log() { echo "[$(date '+%F %T')] $1" >> "$LOG" 2>/dev/null || true; }

while true; do
    sleep 15

    EN=$(sed -n 's/.*"enabled"[[:space:]]*:[[:space:]]*\(true\|false\).*/\1/p' "$CONF" 2>/dev/null)
    [ "$EN" != "false" ] || continue

    # ---- 熔断检测：ADSP crash 风暴 ----
    C=$(dmesg 2>/dev/null | grep -c "handling crash")
    [ -z "$C" ] && C=0
    if [ "$FIRST" = "1" ]; then
        BASE_CRASH=$C
        FIRST=0
    fi
    if [ "$C" -lt "$BASE_CRASH" ]; then
        # dmesg 环形缓冲滚动导致计数回落，重置基线
        BASE_CRASH=$C
    fi
    DELTA=$((C - BASE_CRASH))
    if [ "$DELTA" -ge 3 ]; then
        killall aon_daemon.bin 2>/dev/null || true
        am force-stop "$PKG" 2>/dev/null || true
        log "[FUSE] ADSP crash storm (+$DELTA in window) -> killed aon clients, 10min cooldown"
        date +%s > "$FUSE_TS" 2>/dev/null || true
        BASE_CRASH=$C
        continue
    fi

    # ---- 熔断冷静期（10 分钟）：只监测，不重拉 ----
    NOW=$(date +%s)
    FT=$(cat "$FUSE_TS" 2>/dev/null)
    [ -z "$FT" ] && FT=0
    [ $((NOW - FT)) -lt 600 ] && continue

    # ---- 常规保活 ----
    if ! pidof "$PKG:attention" >/dev/null 2>&1 && \
       ! pidof "$PKG" >/dev/null 2>&1; then
        am start-foreground-service -n "$PKG/.AONAttentionService" >/dev/null 2>&1 || true
        log "[SUPERVISOR] attention service respawned"
    fi

    # daemon 保温（is_island=0 下未注册 ≈0 功耗；死了重拉，不注册）
    if ! pidof aon_daemon.bin >/dev/null 2>&1; then
        [ -x /data/adb/modules/tb522fu_attention_keepon/aon_daemon.bin ] && \
            nohup /data/adb/modules/tb522fu_attention_keepon/aon_daemon.bin --daemon \
                /data/adb/tb522fu_attention/aon_cmd /data/adb/tb522fu_attention/aon_evt \
                0 1 15 2 480 360 3 </dev/null >/dev/null 2>&1 &
    fi
done

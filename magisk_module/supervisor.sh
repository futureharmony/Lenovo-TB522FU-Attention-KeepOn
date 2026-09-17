#!/system/bin/sh
# AON 后台服务监督 + ADSP 崩溃风暴熔断 (v3)
# 由 service.sh §8 开机 nohup 启动；本文件也可手工 nohup 运行。
#
# 职责：
# 1. 单实例：mkdir 原子锁（toybox 无 flock），持锁进程已死则自动回收陈旧锁。
# 2. 保活：enabled=true 时 :attention 进程消失即重拉（Demand Mode 契约依赖进程存活）；
#    daemon 同理，但带最小重拉间隔（见 RESPAWN_MIN_GAP）。
# 3. 熔断：15s 内 ADSP 新增 crash >= 3 → 杀掉全部 AON 客户端（daemon + app）。
#    D4 实证：AonCam fault 是"客户端活着就无限循环"的自持风暴（~5s/次 SSR），
#    state=stop 无效，唯一停法 = 客户端进程死亡。这正是曾造成一夜 80% 掉电的
#    故障形态（ADSP 反复重载 29MB 固件 + USB/UCSI 复位 + ToF 紊乱唤醒手势服务）。
#    熔断后 10 分钟冷静期内不重拉，防止"重拉→再风暴"的功耗循环。
# 4. 框架 provider 漂移兜底（每 5 分钟）：cmd attention setTestableAttentionService
#    是 system_server 的内存态，重启即回落 ROM 默认 provider（SystemUI，本 ROM 上
#    不工作）⇒ 静默重绑，否则表现为"开关在但盯着屏幕仍然息屏"。
#
# ── IPC 路径：必须与 service.sh §3 的 AON_DIR 保持一致 ──────────────────────────
#    历史事故（≤ v1.8.0）：本文件用 CONF_DIR（/data/adb/tb522fu_attention/），而
#    service.sh 与 attention_ctrl 都用 app 私有目录（…/futureharmony.tb522fu.aon/files/）。
#    daemon 只由本文件与 service.sh 拉起，谁先拉起谁决定路径 ⇒ 若由本文件拉起，
#    app 写进 app 路径的命令 daemon 永远读不到，功能表现为「开着但完全不工作」，
#    且**不产生任何日志**，直到下次开机 —— 是本模块唯一必然导致静默失效的点。
#    改动此处前请对照 service.sh，两边必须同源。
CONF_DIR=/data/adb/tb522fu_attention
CONF=$CONF_DIR/config.json
LOG=$CONF_DIR/attention.log
FUSE_TS=$CONF_DIR/.fuse_ts
LOCKDIR=$CONF_DIR/.supervisor.lock
AON_DIR=/data/data/futureharmony.tb522fu.aon/files          # ← 必须与 service.sh 一致
CMD_FILE=$AON_DIR/aon_cmd
EVT_FILE=$AON_DIR/aon_evt
MODDIR=/data/adb/modules/tb522fu_attention_keepon
PKG=futureharmony.tb522fu.aon

CRASH_WINDOW_MAX=3          # 窗口内新增 crash 达到该值即熔断
FUSE_COOLDOWN=600           # 熔断后冷静期（秒）
RESPAWN_MIN_GAP=20          # daemon 最小重拉间隔（秒）：AON 客户端是单占用资源，
                            # 紧邻上一个已注册客户端被杀后立即重拉会拿不到传感器
                            # （实测：3s 内重拉 → 0 事件；上一个从未注册时立即可用）
BASE_CRASH=0
FIRST=1
LAST_RESPAWN=0
TICK=0                      # 主循环计数：框架 provider 漂移核验按此节流（见循环内）

log() { echo "[$(date '+%F %T')] $1" >> "$LOG" 2>/dev/null || true; }

##########################################################################################
# 单实例锁
# toybox 没有 flock，用 mkdir 的原子性实现。锁内记 PID：
#   · PID 活着且 cmdline 含 "supervisor"  → 真锁，另一实例在跑 → 自行退出
#   · PID 已死 / 被复用为非 supervisor 进程 → 陈旧锁（如上次 kill -9）→ 回收后重试
#   · pid 文件还没写出来 → 竞态窗口（刚 mkdir 成功的瞬间）→ 5s 宽限期内视为"被占"
# ⚠️ 存活判定靠 cmdline 含 "supervisor" 这个串，因此**不要重命名本文件**
#    （重命名后判定失效，会退化成"后到者夺锁"，两个实例同时跑）。
##########################################################################################
LOCK_GRACE=5                # pid 未落盘时的宽限期（秒）
acquire_lock() {
    _try=0
    while [ "$_try" -lt 3 ]; do
        if mkdir "$LOCKDIR" 2>/dev/null; then
            echo $$ > "$LOCKDIR/pid" 2>/dev/null
            return 0
        fi
        _old=$(cat "$LOCKDIR/pid" 2>/dev/null)
        if [ -n "$_old" ]; then
            if [ -d "/proc/$_old" ] && \
               grep -aq 'supervisor' "/proc/$_old/cmdline" 2>/dev/null; then
                return 1                                    # 真锁：另一实例在跑
            fi
        else
            _age=$(( $(date +%s) - $(stat -c %Y "$LOCKDIR" 2>/dev/null || echo 0) ))
            [ "$_age" -lt "$LOCK_GRACE" ] && return 1        # 竞态窗口：让先到者写完 pid
        fi
        rm -rf "$LOCKDIR" 2>/dev/null                        # 陈旧锁：回收后重试
        _try=$((_try + 1))
        sleep 1
    done
    return 1
}

cleanup() { rm -rf "$LOCKDIR" 2>/dev/null; }

mkdir -p "$CONF_DIR" 2>/dev/null

if ! acquire_lock; then
    log "[SUPERVISOR] duplicate instance detected, self-exit (pid=$$)"
    exit 0
fi
trap cleanup EXIT INT TERM HUP

log "[SUPERVISOR] v3 started (pid=$$, cmd=$CMD_FILE)"

while true; do
    sleep 15
    TICK=$((TICK + 1))

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
    if [ "$DELTA" -ge "$CRASH_WINDOW_MAX" ]; then
        killall aon_daemon.bin 2>/dev/null || true
        am force-stop "$PKG" 2>/dev/null || true
        log "[FUSE] ADSP crash storm (+$DELTA in window) -> killed aon clients, 10min cooldown"
        date +%s > "$FUSE_TS" 2>/dev/null || true
        BASE_CRASH=$C
        LAST_RESPAWN=0                                   # 冷静期结束后允许立即重拉
        continue
    fi

    # ---- 熔断冷静期（10 分钟）：只监测，不重拉 ----
    NOW=$(date +%s)
    FT=$(cat "$FUSE_TS" 2>/dev/null)
    [ -z "$FT" ] && FT=0
    [ $((NOW - FT)) -lt "$FUSE_COOLDOWN" ] && continue

    # ---- :attention 保活 ----
    if ! pidof "$PKG:attention" >/dev/null 2>&1 && \
       ! pidof "$PKG" >/dev/null 2>&1; then
        am start-foreground-service -n "$PKG/.AONAttentionService" >/dev/null 2>&1 || true
        log "[SUPERVISOR] attention service respawned"
    fi

    # ---- 契约与设置巡检（防被系统静默重置）----
    if [ "$EN" = "true" ]; then
        _as=$(settings get secure adaptive_sleep 2>/dev/null)
        _os=$(settings get secure oplus_customize_smart_screen_off 2>/dev/null)
        _cp=$(settings get secure attention_service_component 2>/dev/null)
        if [ "$_as" != "1" ] || [ "$_os" != "1" ] || [ -z "$_cp" ] || [ "$_cp" = "null" ]; then
            settings put secure adaptive_sleep 1 2>/dev/null || true
            settings put secure oplus_customize_smart_screen_off 1 2>/dev/null || true
            settings put system oplus_customize_smart_screen_off 1 2>/dev/null || true
            settings put secure tb522fu_aon_enabled 1 2>/dev/null || true
            settings put secure attention_service_component "$PKG/$PKG.AONAttentionService" 2>/dev/null || true
        fi
    fi

    # ---- 框架 provider 漂移兜底（每 20 tick ≈5 分钟核一次）----
    # cmd attention setTestableAttentionService 只是 system_server 的内存态，
    # 任何一次 system_server 重启都会回落 ROM 默认 provider（本 ROM = SystemUI 的
    # AONAttentionService，实测不工作），表现是"设置开关还在但盯着屏幕照样息屏"。
    # 频率取 5 分钟：每次核验都要 spawn 一个 app_process（cmd），不宜每 15s 一次。
    if [ "$EN" = "true" ] && [ $((TICK % 20)) -eq 1 ]; then
        _bc=$(cmd attention getAttentionServiceComponent 2>/dev/null | head -n1 | tr -d '\r')
        case "$_bc" in
            "$PKG"/*) ;;
            *)
                cmd attention setTestableAttentionService "$PKG" >/dev/null 2>&1 || true
                log "[SUPERVISOR] framework provider drifted ('${_bc:-none}') -> rebind to $PKG"
                ;;
        esac
    fi

    # ---- daemon 保温（is_island=0 下未注册 ≈0 功耗；死了重拉，不注册）----
    # 只在"拉起时刻"更新 LAST_RESPAWN，因此若上次拉起未成功（典型：单占用仍在释放），
    # 后续轮次会自动退避到满 RESPAWN_MIN_GAP 再试，而不会 15s 一轮地空撞。
    if ! pidof aon_daemon.bin >/dev/null 2>&1; then
        if [ $((NOW - LAST_RESPAWN)) -ge "$RESPAWN_MIN_GAP" ]; then
            if [ -x "$MODDIR/aon_daemon.bin" ]; then
                LAST_RESPAWN=$NOW
                nohup "$MODDIR/aon_daemon.bin" --daemon \
                    "$CMD_FILE" "$EVT_FILE" \
                    0 1 15 2 480 360 3 </dev/null >/dev/null 2>&1 &
                log "[SUPERVISOR] aon_daemon respawned (algo=2 480x360 non-island)"
            else
                log "[SUPERVISOR] aon_daemon.bin not executable/missing, cannot respawn"
            fi
        fi
    fi
done

#!/system/bin/sh
# 一键验证注视保活闭环(在设备上以 root 运行):
#   adb shell "su -c 'sh /data/local/tmp/verify_keepon.sh'"
# 流程: 亮屏 → 拉起服务 → 45 秒桥接期(请尽快开始注视前摄)
#       → 停止桥接,观察 60 秒:注视期间应出现 mask=5 事件 + KEEPALIVE ping,
#         且屏幕保持 Awake;移开视线后 ≤10 秒熄屏。
AON_DIR=/data/data/futureharmony.tb522fu.aon/files
EVT=$AON_DIR/aon_evt
input keyevent KEYCODE_WAKEUP
am start-foreground-service -n futureharmony.tb522fu.aon/.AONAttentionService
sleep 6
echo "=== [1/3] 桥接 45 秒: 请现在开始注视设备前摄 ==="
i=0
while [ $i -lt 11 ]; do
    UA=$(awk '{printf "%d", $1*1000}' /proc/uptime)
    service call power 14 i32 0 i64 $UA i32 0 i32 0 >/dev/null 2>&1
    sleep 4; i=$((i+1))
done
P0=$(grep -c 'KEEPALIVE ping' $EVT 2>/dev/null)
echo "=== [2/3] 停止桥接,观察 60 秒(继续注视!此期间屏幕只能靠 daemon 保活) ==="
i=0
while [ $i -lt 12 ]; do
    W=$(dumpsys power | grep -o 'mWakefulness=[A-Za-z]*' | head -1)
    K=$(grep -c 'KEEPALIVE ping' $EVT 2>/dev/null)
    M=$(grep -o 'vals=[0-9]*' $EVT 2>/dev/null | tail -1)
    echo "$(date +%H:%M:%S) $W keepalive=$K mask=${M#vals=}"
    sleep 5; i=$((i+1))
done
P1=$(grep -c 'KEEPALIVE ping' $EVT 2>/dev/null)
echo "=== [3/3] 移开视线,确认 ≤10 秒熄屏 ==="
sleep 12
dumpsys power | grep 'mWakefulness='
echo "=== 判定 ==="
echo "桥接期 KEEPALIVE 数: $P0,观测期新增: $((P1-P0))"
if [ $((P1-P0)) -ge 3 ]; then
    echo "PASS: daemon 在注视期间持续 KEEPALIVE 保活"
else
    echo "FAIL: 观测期无 KEEPALIVE —— 检查是否真的有人注视前摄(需 mask=5 事件)"
    grep 'vals=5' $EVT | tail -3
fi

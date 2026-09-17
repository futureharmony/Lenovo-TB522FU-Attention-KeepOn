#!/system/bin/sh
##########################################################################################
# Lenovo Legion Y900 (TB522FU) 注视不熄屏增强模块 - post-fs-data 阶段净化
# Author: futureharmony
##########################################################################################

##########################################################################################
# 0. 开机失败自动回退（bootloop guard）
#    规则：每次开机 post-fs-data 阶段计数 +1；service.sh 在 boot_completed 后清零。
#    连续计数达到阈值 = 连续开机失败（卡死在 boot_completed 之前），立即：
#      1) 触碰 disable 标志 → 下一周期起本模块不再被管理器加载；
#      2) 本周期直接跳过全部模块动作（本机当作一次"干净启动"验证）；
#      3) 由 /data/adb/service.d/tb522fu_rollback_cleanup.sh（独立于模块启停）
#         在系统起来后还原 attention 相关 secure 设置。
#    阈值语义：前 2 次失败仍带模块重试（可能为偶发），第 3 次开机起模块自禁。
##########################################################################################
MODDIR_BOOT=${0%/*}
BOOT_FAIL_MAX=3
BFC="$MODDIR_BOOT/boot_fail_count"
CNT=$(cat "$BFC" 2>/dev/null)
case "$CNT" in ''|*[!0-9]*) CNT=0 ;; esac
CNT=$((CNT+1))
echo "$CNT" > "$BFC" 2>/dev/null
if [ "$CNT" -ge "$BOOT_FAIL_MAX" ] || [ -f "$MODDIR_BOOT/AUTO_DISABLED" ]; then
    touch "$MODDIR_BOOT/disable" 2>/dev/null
    if [ ! -f "$MODDIR_BOOT/AUTO_DISABLED" ]; then
        echo "auto-disabled after $((CNT-1)) consecutive failed boots (threshold=$BOOT_FAIL_MAX)" > "$MODDIR_BOOT/AUTO_DISABLED" 2>/dev/null
        echo "[$(date '+%F %T')] [ROLLBACK] boot-fail #$CNT, module auto-disabled, skipping all actions this boot" >> /data/adb/tb522fu_attention/attention.log 2>/dev/null || true
    fi
    exit 0
fi
##########################################################################################

mkdir -p /data/adb/tb522fu_attention 2>/dev/null
chmod 755 /data/adb/tb522fu_attention 2>/dev/null

# LwKy 净化（keep 档已裁撤：LwKy 启用会争抢 attention_service_component 绑定，
# 因此无论策略如何，早期阶段一律杀进程并中和 ROM 脚本；运行期策略见 service.sh）。

# 1. 终止底包残留的 LwKy init 服务与进程 (存在则移除，不存在则跳过)
setprop ctl.stop lwky_oplus_aon 2>/dev/null || true
pkill -9 -f lwky-oplus-aon 2>/dev/null || true
pkill -9 -f lwky_oplus_aon 2>/dev/null || true
rm -f /data/local/tmp/lwky* 2>/dev/null || true
rm -f /data/adb/tb522fu_attention/lwky* 2>/dev/null || true

# 2. 如果存在直接挂载点，bind mount 空脚本以确保即使 init 启动也立即退出
EMPTY_SH="/data/adb/tb522fu_attention/empty.sh"
echo "#!/system/bin/sh\nexit 0" > "$EMPTY_SH" 2>/dev/null
chmod 755 "$EMPTY_SH" 2>/dev/null
if [ -f "/system/lwky/lwky-oplus-aon.sh" ]; then
    mount --bind "$EMPTY_SH" "/system/lwky/lwky-oplus-aon.sh" 2>/dev/null || true
fi

##########################################################################################
# 3. AON island registry 修正（exp4 成果固化，幂等；v1.8.0 起内置）
#    厂商 tuning 中 4 个模型的 is_island=1 会启用 ADSP LPai 岛路径，而该路径存在
#    构建期链接缺陷（岛代码 436 个出站调用指向岛外段，跨区取指必 fault）→
#    ADSP 5s SSR 自持风暴（曾致一夜 80% 掉电）。终态修复 = is_island=0 走主域。
#    必须在 post-fs-data 阶段执行：早于 sensors HAL / sscrpcd 读配置。
##########################################################################################
REG="/mnt/vendor/persist/sensors/registry/registry"
ISLAND_OLD='"is_island":{"type":"int","ver":"0","data":"1"}'
ISLAND_NEW='"is_island":{"type":"int","ver":"0","data":"0"}'
ISLAND_LOG="/data/adb/tb522fu_attention/island_fix.log"
ISLAND_FIXED=0
ISLAND_ALREADY=0
# 等 persist 挂载就绪（最多 20s；post-fs-data 阶段 persist 可能尚未挂好）
_try=0
while [ $_try -lt 20 ] && [ ! -d "$REG" ]; do
    sleep 1; _try=$((_try+1))
done
if [ -d "$REG" ]; then
    # 覆盖范围用通配符而非硬编码模型名。本 ROM 的 registry 中实际存在 6 个 nms_* 模型：
    #   nms_eod / nms_fd_qqvga / nms_fd_qvga / nms_fd_360p / nms_hd / nms_qrcode
    # 历史版本（≤ v1.8.0）只列了 eod/fd_qqvga/fd_qvga/qrcode 4 个，漏掉 nms_fd_360p 与
    # nms_hd —— 设备实测这两个当前为 0（从未被本模块改过，即出厂即 0），故未造成故障；
    # 但"覆盖不完整"意味着它们一旦被 OTA / SSC 回写为 1，漂移自检也发现不了。
    # 岛路径的损坏是**全局性**的（岛代码 436/436 出站调用跨段 ⇒ 对所有 NMS 模型同样致命），
    # 因此把任意 nms_* 的 is_island 归零都是必要且安全的；通配遍历还能让厂商将来新增
    # 模型时自动纳入保护，无需再改本文件。
    for _f in "$REG"/qsh_camera_common.json.qsh_camera.tuning_params.nms_*; do
        [ -f "$_f" ] || continue
        _m="${_f##*tuning_params.}"
        if grep -q "$ISLAND_OLD" "$_f" 2>/dev/null; then
            sed "s/$ISLAND_OLD/$ISLAND_NEW/" "$_f" > /data/adb/tb522fu_attention/.island_tmp \
                && cat /data/adb/tb522fu_attention/.island_tmp > "$_f"   # cat 原地覆盖，保留 system:system 0600
            rm -f /data/adb/tb522fu_attention/.island_tmp 2>/dev/null
            echo "[$(date '+%F %T')] fixed $_m (1 -> 0)" >> "$ISLAND_LOG" 2>/dev/null
            ISLAND_FIXED=$((ISLAND_FIXED+1))
        else
            ISLAND_ALREADY=$((ISLAND_ALREADY+1))
        fi
    done
    echo "[$(date '+%F %T')] island fix run: fixed=$ISLAND_FIXED already0=$ISLAND_ALREADY" >> "$ISLAND_LOG" 2>/dev/null
else
    echo "[$(date '+%F %T')] WARN: persist registry not mounted after 20s, island fix skipped" >> "$ISLAND_LOG" 2>/dev/null
fi

##########################################################################################
# 4. 预制 Framework-res 静态 RRO idmap 与 ColorOS 系统设置首选项 (早于 system_server 启动)
#    确保 OverlayManagerService 与 Settings 启动时即可命中有效 idmap 与 keep_on_looking 配置
##########################################################################################
mkdir -p /data/resource-cache 2>/dev/null
chmod 771 /data/resource-cache 2>/dev/null
chown system:system /data/resource-cache 2>/dev/null

for idmap_name in "my_product@overlay@lwky.oplus.aon.frameworkres.overlay.product.apk@idmap" "product@overlay@lwky.oplus.aon.frameworkres.overlay.product.apk@idmap" "product@overlay@aon_frameworkres_overlay.apk@idmap"; do
    TARGET_OVERLAY="/product/overlay/lwky.oplus.aon.frameworkres.overlay.product.apk"
    [ -f "$TARGET_OVERLAY" ] || TARGET_OVERLAY="/product/overlay/aon_frameworkres_overlay.apk"
    idmap2 create --target-apk-path /system/framework/framework-res.apk \
        --overlay-apk-path "$TARGET_OVERLAY" \
        --idmap-path "/data/resource-cache/$idmap_name" \
        --policy product --policy system 2>/dev/null || true
    if [ -f "/data/resource-cache/$idmap_name" ]; then
        chmod 644 "/data/resource-cache/$idmap_name" 2>/dev/null
        chown system:system "/data/resource-cache/$idmap_name" 2>/dev/null
    fi
done

SETTINGS_PREF_DIR="/data/user/0/com.android.settings/shared_prefs"
if [ -d "$SETTINGS_PREF_DIR" ]; then
    cat << 'EOF' > "$SETTINGS_PREF_DIR/keep_on_looking.xml"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="keep_on_looking" value="true" />
</map>
EOF
    chmod 660 "$SETTINGS_PREF_DIR/keep_on_looking.xml" 2>/dev/null || true
    chown system:system "$SETTINGS_PREF_DIR/keep_on_looking.xml" 2>/dev/null || true
fi


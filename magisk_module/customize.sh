#!/sbin/sh
##########################################################################################
# Lenovo Legion Y900 (TB522FU) 注视不熄屏增强模块 - 安装与初始化脚本
# Author: futureharmony
##########################################################################################

ui_print "***************************************************"
ui_print "  联想拯救者 Y900 (TB522FU) 注视不熄屏增强模块     "
ui_print "  作者: futureharmony                              "
ui_print "***************************************************"

# 1. 净化底包遗留的 LwKy AON 初始化服务（监听日志做唤醒前置检测，与模块 daemon 冲突）。
#    注意：流光灯（lwky.FlowingLight.Control，后置模组灯环 LED）与 AON 无关，
#    不做任何干预、不禁用、不卸载（2026-09-17 定案）。
CONF_DIR="/data/adb/tb522fu_attention"
mkdir -p "$CONF_DIR" 2>/dev/null
chmod 755 "$CONF_DIR" 2>/dev/null

ui_print "- 正在净化底包遗留的 AON 服务脚本..."
setprop ctl.stop lwky_oplus_aon 2>/dev/null || true
pkill -9 -f lwky-oplus-aon 2>/dev/null || true
pkill -9 -f lwky_oplus_aon 2>/dev/null || true
pkill -9 -f "aon_watchdog" 2>/dev/null || true
pkill -9 -f "aon_daemon" 2>/dev/null || true
rm -f /data/local/tmp/lwky* 2>/dev/null || true
rm -f /data/adb/tb522fu_attention/aon_watchdog.sh 2>/dev/null || true

# 2. 初始化持久化配置与运行目录
CONF_DIR="/data/adb/tb522fu_attention"
mkdir -p "$CONF_DIR" 2>/dev/null
chmod 755 "$CONF_DIR" 2>/dev/null

# 2.5 island registry 原件备份（首次安装时归档厂商原始 is_island=1 配置，
#     供回退/比对用；幂等——已有备份不覆盖）
REG="/mnt/vendor/persist/sensors/registry/registry"
if [ -d "$REG" ]; then
    ISLAND_BAK="$CONF_DIR/backup/island_ORIG"
    mkdir -p "$ISLAND_BAK" 2>/dev/null
    # 通配遍历全部 nms_* 模型（本 ROM 实有 6 个；历史版本硬编码 4 个，漏了
    # nms_fd_360p / nms_hd）。此处为安装时快照，供回退/比对用；已有备份不覆盖。
    for _f in "$REG"/qsh_camera_common.json.qsh_camera.tuning_params.nms_*; do
        [ -f "$_f" ] || continue
        _m="${_f##*tuning_params.}"
        if [ ! -f "$ISLAND_BAK/$_m" ]; then
            cp -p "$_f" "$ISLAND_BAK/$_m" 2>/dev/null || true
        fi
    done
    [ "$(ls "$ISLAND_BAK" 2>/dev/null | wc -l)" -gt 0 ] && \
        ui_print "- [✓] island registry 原件已备份至 $ISLAND_BAK"
fi

# 2.6 安装期兼容性自检（不阻断安装，仅诊断——把"静默失效"变成"显式告知"）
#     本模块依赖三件外部事实：SystemUI 原厂注视组件、SSC registry 的 is_island 键、
#     高通 AON vendor HAL。任一不满足时明确警告，便于异底包用户定位问题。
COMPAT_WARN=0
ui_print "- 正在执行兼容性自检..."

# a. SystemUI 原厂注视组件（overlay 的 config_defaultAttentionService 指向它；
#    缺失时设置开关校验失败、attention binder 不发布 → 功能静默失效）
if pm dump com.android.systemui 2>/dev/null | grep -q "keyguard.attention.AONAttentionService"; then
    ui_print "- [✓] SystemUI 原厂注视组件存在"
else
    ui_print "! [警告] SystemUI 中未找到 keyguard.attention.AONAttentionService"
    ui_print "!        当前底包可能不含原厂注视功能，开关可能无法保持打开"
    COMPAT_WARN=$((COMPAT_WARN+1))
fi

# b. SSC registry 的 is_island 键（island=0 修正的作用对象；缺失说明固件
#    tuning 格式不同——若该固件本无 island 缺陷则无碍，否则崩溃风险仍在）
REG_HIT=$(grep -l "is_island" "$REG"/qsh_camera_common.json.qsh_camera.tuning_params.nms_* 2>/dev/null | head -n 1)
if [ -n "$REG_HIT" ]; then
    ui_print "- [✓] island registry 含 is_island 配置（修正目标在位）"
else
    ui_print "! [警告] registry 未找到 is_island 键，固件 tuning 格式可能不同"
    ui_print "!        island 修正将无对象可改，请反馈固件版本以确认风险"
    COMPAT_WARN=$((COMPAT_WARN+1))
fi

# c. 高通 AON vendor HAL（感知层唯一硬件通道）
if service list 2>/dev/null | grep -q "vendor.qti.hardware.camera.aon.IAONService"; then
    ui_print "- [✓] 高通 AON vendor HAL 正常注册"
else
    ui_print "! [警告] 未发现 AON vendor HAL（vendor.qti.hardware.camera.aon.IAONService）"
    ui_print "!        硬件感知不可用，固件可能过旧/过新或经过深度裁剪"
    COMPAT_WARN=$((COMPAT_WARN+1))
fi

if [ "$COMPAT_WARN" -gt 0 ]; then
    ui_print "! 兼容性自检发现 $COMPAT_WARN 项异常：模块仍会安装，"
    ui_print "! 但注视功能可能无效。请带本安装日志到 GitHub Issues 反馈。"
else
    ui_print "- [✓] 兼容性自检全部通过"
fi

if [ ! -f "$CONF_DIR/config.json" ]; then
    ui_print "- 正在初始化默认配置 (默认开启注视保护、日志默认关闭)..."
    echo "{\"enabled\": true, \"log_enabled\": false, \"installed_at\": \"$(date "+%Y-%m-%d %H:%M:%S")\"}" > "$CONF_DIR/config.json"
fi
# 旧版安装遗留的 overlay 副本与 App 端历史日志裁撤（幂等）
rm -f "$CONF_DIR/aon_overlay.apk" "$CONF_DIR/aon_frameworkres_overlay.apk" 2>/dev/null || true

# 3. 部署系统级 Framework-res 静态 RRO Overlay（承重组件，勿裁撤！）
#    config_defaultAttentionService 指向原厂 systemui AONAttentionService：
#    - ColorOS 设置 KeepOnLookingController 依赖 getAttentionServicePackageName()
#      （MATCH_FACTORY_ONLY + CAMERA 校验）非空才允许「注视时不熄屏」开关保持打开；
#    - OPLUS 定制 AttentionManagerService 解析不到合法 provider 时不发布 attention
#      binder，`cmd attention setTestableAttentionService` 也就无从执行。
#    v1.8.6 曾误判该 overlay 从未生效并移除，导致重启后 attention 服务消失——已证伪恢复。
mkdir -p "$MODPATH/my_product/overlay" "$MODPATH/system/product/overlay" "$MODPATH/system/overlay" 2>/dev/null
if [ -f "$MODPATH/aon_frameworkres_overlay.apk" ]; then
    cp -f "$MODPATH/aon_frameworkres_overlay.apk" "$MODPATH/my_product/overlay/lwky.oplus.aon.frameworkres.overlay.product.apk" 2>/dev/null
    cp -f "$MODPATH/aon_frameworkres_overlay.apk" "$MODPATH/system/product/overlay/lwky.oplus.aon.frameworkres.overlay.product.apk" 2>/dev/null
    cp -f "$MODPATH/aon_frameworkres_overlay.apk" "$MODPATH/system/product/overlay/aon_frameworkres_overlay.apk" 2>/dev/null
    cp -f "$MODPATH/aon_frameworkres_overlay.apk" "$MODPATH/system/overlay/aon_frameworkres_overlay.apk" 2>/dev/null
    cp -f "$MODPATH/aon_frameworkres_overlay.apk" "$CONF_DIR/aon_overlay.apk" 2>/dev/null
fi

# 清理旧版 idmap 缓存，OMS 重启后按新 Overlay 重新生成
rm -f /data/resource-cache/*aon* 2>/dev/null || true

# 4. 安装 futureharmony.tb522fu.aon 后台感知服务应用
if [ -f "$MODPATH/aon.apk" ]; then
    ui_print "- 正在安装后台感知服务应用..."
    pm install -r -g "$MODPATH/aon.apk" >/dev/null 2>&1 || true
fi

# 4.5 同步初始化系统设置（AOSP + ColorOS 双表对齐 + 首选项直通）
if [ "$(grep -o '"enabled"[[:space:]]*:[[:space:]]*true' "$CONF_DIR/config.json" 2>/dev/null)" ]; then
    settings put secure adaptive_sleep 1 >/dev/null 2>&1 || true
    settings put secure oplus_customize_smart_screen_off 1 >/dev/null 2>&1 || true
    settings put system oplus_customize_smart_screen_off 1 >/dev/null 2>&1 || true
    settings put secure tb522fu_aon_enabled 1 >/dev/null 2>&1 || true
    settings put secure attention_service_component \
        "futureharmony.tb522fu.aon/futureharmony.tb522fu.aon.AONAttentionService" >/dev/null 2>&1 || true

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
fi

# 5. 设置权限与 SELinux 上下文
ui_print "- 正在设置模块执行权限与安全上下文..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/attention_ctrl" 0 0 0755
[ -f "$MODPATH/aon_daemon.bin" ] && set_perm "$MODPATH/aon_daemon.bin" 0 0 0755
[ -f "$MODPATH/system/lwky/lwky-oplus-aon.sh" ] && set_perm "$MODPATH/system/lwky/lwky-oplus-aon.sh" 0 0 0755
[ -f "$MODPATH/post-fs-data.sh" ] && set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
[ -f "$MODPATH/service.sh" ] && set_perm "$MODPATH/service.sh" 0 0 0755

# 5.5 开机失败自动回退（bootloop guard）
#     - 计数/禁用逻辑内置于 post-fs-data.sh §0 与 service.sh §1.5；
#     - 本步把清理脚本放入 /data/adb/service.d/ —— 该目录脚本独立于模块启停
#       状态执行，模块被自动禁用后仍能还原 secure 设置；
#     - 重装/更新时重置回退计数与清理标记（AUTO_DISABLED 属于旧实例，不保留）。
if [ -f "$MODPATH/rollback_cleanup.sh" ]; then
    mkdir -p /data/adb/service.d 2>/dev/null
    cp -f "$MODPATH/rollback_cleanup.sh" /data/adb/service.d/tb522fu_rollback_cleanup.sh 2>/dev/null
    chmod 755 /data/adb/service.d/tb522fu_rollback_cleanup.sh 2>/dev/null
    rm -f "$MODPATH/boot_fail_count" "$MODPATH/AUTO_DISABLED" "$MODPATH/AUTO_CLEANED" 2>/dev/null || true
    ui_print "- [✓] 开机失败自动回退已部署：连续 3 次开机失败将自动禁用模块并还原设置"
    ui_print "-     回退后恢复方法：管理器中重新启用模块并重启"
fi

# Overlay APK 安全上下文对齐 (防止 system_app / PMS 拒绝访问)
chcon -R u:object_r:system_file:s0 "$MODPATH/system" 2>/dev/null || true
chcon u:object_r:system_file:s0 "$CONF_DIR/aon_overlay.apk" 2>/dev/null || true

ui_print "***************************************************"
ui_print "✅ 模块安装完成！"
ui_print "👉 基于联想 Y900 原生低功耗 Camera 3 (OG0VE) AON FDPRO 硬件感知运作"
ui_print "👉 视线注视屏幕时持续保持常亮，移开视线/转头时准时变暗熄屏"
ui_print "***************************************************"
ui_print "***************************************************"

# 排障坑清单（每条都曾**静默**给出错误结论）

> 从 `MEMORY.md` 拆出（2026-09-17，为给记忆文件腾空间）。这些坑跨轮复用，别删。
> 工具默认在 `aon_rootcause/zuxos_native/`，多数已同步进 skill `android-remoteproc-ssr-triage/scripts/`。

## A. 设备与运维

1. 崩溃指标用 **dmesg 时间戳序列** + `adb devices -l` 的 `transport_id` 递增；
   `dmesg | grep -c` 不可靠（环形缓冲滚动，计数会变小）。`ramdump` 只在**有界窗口内看 delta**
   （先故意崩一次标定基线）+ `grep -c PD_ERR /dev/kmsg` 交叉验证。ADSP minidump **AES 加密不可读**。
2. `su -c` **只对单个 token 生效**（复合串会掉回 `u:r:shell:s0`）→ 一律
   `adb shell su -c 'sh /data/local/tmp/x.sh'`。与 `/data/vendor/*` 交互**必须走脚本文件**；
   直接 `su -c 'printf … > /data/…'` 即使拿到 `u:r:ksu:s0` 也报 Permission denied（SELinux Enforcing）。
3. `grep` 对 log / .dis 必须加 `-a`；恒速事件流不能 `tail -c | grep -c`，用 `wc -l` 全文件增量。
4. 固件归属看**签名链 / `CRMBuilds` 标签，别看 mtime**（被刷成 2009-01-01）。功耗实测**必须拔 USB**。
5. **Hexagon 常量串换算：`VA = packet 首地址 + imm`**，不是指令地址。

## B. 反汇编 / 字节码

6. ⚠️ **Hexagon 函数切分**：llvm-objdump 常把 `{` 打在**上一行末尾** ⇒ 用 `{ allocframe` 匹配会漏 ~90% 函数
   （曾误得「`eai_execute` 0 个调用者」，实际 5 个）。判据放宽为「该行含 `allocframe`」；
   且函数切分必须返回**行号**而非地址，否则切片为空、**静默「无命中」**。工具 `hexfunc.py`。
7. ⚠️ **androguard 4.x 姿势**（三条都会静默出错）：
   - `d.get_strings()` 返回 **`str`**，不是对象（别写 `s.get()`）；
   - 取指令必须 `code.get_bc().get_instructions()`（每条 `ins.get_name()` / `ins.get_output()`）；
     `DalvikCode.get_instruction()` **需要 idx 参数**，直接调用会 TypeError；
   - **`sv_fd.py xref` 只匹配 `invoke`**（方法/字段**引用**）。
     查「谁 const-string 了某字符串」必须用 **`strref.py`**（字符串常量不在 invoke 里）；
     查 `sget-object`/`iget` 等**字段读**也用全指令扫描 —— 否则会得出"没人用它"的假结论。
   - `cls` 正则**别用 `$` 收尾**（类名以 `;` 结尾会全不中）。
8. ⚠️ **魔数陷阱**（`hexfunc.py` / `secmap.py` 的输入）：核对 `ph34.elf` 时曾把
   结构体字节偏移当元组下标（ELF64 shdr: name=0/type=1/flags=2/addr=3/off=4/size=5；
   ELF32 `e_shoff` 是 4 字节需 `<I`）。自写解析器**必须当场用已知答案自校验**。

## C. 镜像取证

9. ⚠️ **文件大小一律读 inode `i_size`**，别信目录遍历：`ext4lib.read_file` / `ext4walk.py` **都会静默截断**。
   指纹 = **恰好 4096 整数倍**，会**伪装成「同源不同编译产物」**
   （`com.qti.qseeaon.so` 报 319,488 / 真实 **333,464**；`com.qti.node.aon.so` 报 65,536 / 真实 **85,016**）。
10. ⚠️ **vendor 的 ELF/APK 大量是稀疏文件** ⇒ 拼接**必须按逻辑块补零**，硬约束 `written == i_size`，
    并对 ZIP 做 EOCD/条目数自校验。工具 `efetch.py`（取代 vread/xget）。
11. ⚠️ **extent 内节点 `eh_entries` 上限 340** `=(bs-12)/12`；卡成 4~6 会让**高碎片化文件被整体丢弃**
    （`framework.jar`、`ZuiSettings.apk` 都曾因此"查不到归属"）。
12. 偏移→文件归属用 **inode 表遍历**（`whoino.py`），别用目录递归（会静默漏，且完全跳过非 extent 文件）。
13. ⚠️ **整镜像 sha256 不同 ≠ 内容不同** ⇒ 判内容用 `dircmp.py` 逐文件 sha256（`dsp_a` vs `dsp_b` 就是这种情况）。
14. 分区身份看超级块卷标 `sb[120:136]`，别猜文件名。
15. ⚠️ **ZUX 的 AXML 清单串池是 UTF-16LE** ⇒ 只扫 ASCII 对**所有清单完全盲**，必须**双编码**（`u16scan.py`）。
16. ⚠️ **短 ASCII 命中先判「是不是字符串」**：落在 ELF `.text`（`flags=AX`）= **机器码字节巧合**
    （5 处 `"EOD"` 全在 TFLite `.text`）；dex **串池粘连碎片**（`seod`）同理。工具 `secmap.py <elf> <off…>`。
    **不做这步会「证明」出不存在的消费者。**
17. ⚠️ **裸镜像扫描看不到被 deflate 压缩的 APK 条目**（dex 常是 STORED 能看到，arsc/resources 常在压缩流里）。
    ⇒ 镜像扫描 **0 命中 ≠ 不存在**；要下"某字符串全 ROM 只有 N 处"的结论，必须**先把候选 APK 解压再扫**。
18. ⚠️ **配置文件提取后带前置文本头**（`vendor_a: super …` + ext4 信息）⇒ `json.load` 前先 `raw.index('{')`。

## D. AON 运行期 / 模块运维

19. ⚠️ **`attention_ctrl test` 有两类假结果，都不该当通断证据。** 两类都实机复现、逐条定性过：
    **(a) 假 `ABSENT`**（`code=0`，每次**固定约 2.4~3 s**）—— 旧版发**裸 `start`**，撞上命令通道
    「内容不变即忽略」语义（见 23）⇒ 流从未注册，等满窗口后按"无人脸"上报。
    原文"读陈旧文件"的机制**作废**：v1.7 的 `attention_ctrl` 第 10-11 行读的**就是** app 路径，
    与 daemon 一致。
    **(b) 假 `-1`「无事件（流未出帧）」** —— v1.8.1 及以前用「`aon_evt` 文件变大 + 8 s 窗口」判新帧，
    有三个独立失效模式，2026-09-17 用**逐轮打点的复刻脚本**（`aon_rootcause/scripts/t7_test_repro.sh`）实测确认：
    ① **daemon 启动会重写 `aon_evt`**（实测 55804 B → 61 B、`EVT` 1534 → 0）。窗口若跨越一次
       daemon 重启，`sz1 > sz0` **恒假** ⇒ 必然假报"未出帧"。**← 本轮失败场景的真因**
    ② **8 s 窗口太短**：常规冷启出帧只要 1~2 s，但 AON 客户端交接后**首次 start 偶发不生效**
       （见 24）⇒ 窗口内零帧 ⇒ 假报。
    ③ 邻接写法（`if sz1>sz0` 才 `break`）在"文件长了但还没有 EVT 行"（只有 `STARTING`）时空转：
       `found` 为空，既不匹配任何分支、也不更新基准。
    ✅ **v1.8.2 起 `test` 的正确判据**：用「**新增字节里有没有 `^EVT b=1 `**」判帧、「**2 s 内是否
    新增 EVT 行**」判流在不在跑、「**size 回落即 re-baseline**」容忍 daemon 重写；窗口 20 s +
    零帧**重发一次 `state=start`** 再等 10 s；**只在本函数自己拉起流时才 `stop`**
    （⚠️ 重试分支发了 start 就必须置 `started=1`，否则流会一直挂着不恢复）。
    手工复核仍推荐：`state=start … seq=<uptime_ms+10>` + **数 `^EVT` 行**（见 23）。
    验收脚本：`aon_rootcause/scripts/v182_verify.sh`（V1 冷路径 / V2 空闲 / V3 窗口内重启 / V4 流在跑 / V5 status）。
19b. ✅ **模块内部 IPC 路径不一致（v1.8.0 缺陷，已在 v1.8.1 修复）**：`service.sh` 与
    `attention_ctrl` 都用 **app 路径**（`/data/data/futureharmony.tb522fu.aon/files/`），
    唯独 **`supervisor.sh` 用 `/data/adb/tb522fu_attention/`** —— 它是 v1.7→v1.8.0 之间
    唯一没跟着改的组件 ⇒ daemon 被 supervisor 重拉后控制面**永久失联**，表现为"开关是开的但
    完全不工作"，直到下次开机。**v1.8.1 已把两处路径统一到 app 路径并加同源注释。**
20. ⚠️ **daemon 的 CPU 累积增量**判据**不成立**（曾被它误导一轮）：断链态与恢复态实测**都是
    2 ticks / 25 s**。原因：daemon 主体阻塞在 poll 上等 binder 回调，单事件开销极小；
    第 6 轮 `daemon.out` 涨到 605 MB 增长的是**磁盘 I/O**，且当时 stdout 是**重定向到文件**的。
    supervisor 把 stdout 丢进 `/dev/null` 后，CPU 差异被抹平。
    ✅ **正确判据（可靠性降序）**：① **`^EVT` 行数增量**（活跃态约 8 帧/s ⇒ 15 s 涨 ~3.7 KB 的
    事件行；空闲/断链时**一条不涨**）——⚠️ **不要用"文件 size 增量"代替**，daemon 每 5 s 写一条
    `HBT` 心跳，流没注册时文件照样在长（见 27）；
    ② `attention.log` 有无新的 `AON hardware daemon started`（断链期一条不出、恢复后立即出现）；
    ③ 带日志重启 daemon 看有无事件行。**别用 test 返回值，也别用 CPU 增量。**

21. ❌ **~~AON 会话是"开机一次性"资源，只能重启设备恢复~~** —— **第 8 轮已证伪**。
    实测：杀掉 daemon 后用**正确命令**重拉，**12 秒内即产出 94 个 `EVT` 行**
    （`st=1 rep=1 cid=-5476376651825832944`），**全程零重启、零崩溃** ⇒ 会话**可重建**。
    重启只是"最省事的重建方式"，不是唯一方式。**两个把上一轮带偏的坑**（方向相反、恰好抵消）：
    - **`cid=-1` + 无 `EVT` 行 ≠ 断链** —— 那是 **Demand Mode 的正常未注册态**
      （`HBT <ts> st=0 rep=0 cid=-1`）。框架回调 `onCheckAttention` 前本来就不注册流。
    - **`HBT` 心跳增长 ≠ 事件恢复** —— 心跳 39 B × 3 行/15 s ≈ 117 B，恰好长得像"每 15 s +100~130 B"。
      真事件流（`EVT b=1 n=7 …`）在健康态约 8 帧/s ⇒ 15 s 应涨 ~3.7 KB。**数事件必须数 `EVT` 行。**
    ⚠️ 顺带：`dmesg | grep -iE "EX:"` 会**误匹配** init 的 `opex:` 日志（`-i` 让 `EX:` 命中 `ex:`），
    把正常启动日志当成崩溃 —— 用精确大小写或 `grep -a "qsh_process"`。

22. ⚠️ **supervisor 会被拉起两份**（低危但会误判"进程泄漏"）：常驻 `service.sh` 与其拉起的
    app `:attention` 服务**都会**启动 `supervisor.sh` ⇒ 一份 `nohup` 约 15~20 s 后变两份
    （可用 `/proc/<pid>/stat` 第 4 字段看 PPID 确认父子关系）。daemon 有 `pidof` 前置检查
    不会双起，故危害仅限多一个保活循环。检测脚本自身**必须避开自匹配**（见 MEMORY 坑）。

23. ⚠️ **命令通道是"内容不变即忽略"语义**（`aon_daemon.bin`）：往 `aon_cmd` 写入**与上一次
    完全相同**的内容，daemon **不会**处理（实测：裸 `start` 重复写 → `EVT=0`；换成
    `state=start … seq=7` → 12 s 内 94 个 `EVT`）。很可能按 `seq` 去重。
    ✅ 触发命令用 **`state=start <camIdx> <srv> <mask> <algo> <w> <h> <dps> seq=<自增>`**
    （v1.8.0 `attention_ctrl` 的写法）；手工调试时先 `: > aon_cmd` 清空再写。
    参数语义：`srv 1=FaceDetectPro ｜ algo 0=160×120 岛capable / 1=320×240 / 2=480×360 非岛`。

24. ⚠️ **AON 客户端是单占用资源，接管有延迟**：在上一个 daemon **已注册**（`cid` 有效、正在出流）
    时把它 kill，3 s 内重拉新 daemon + 发命令 ⇒ **`EVT=0`**（拿不到传感器）；
    上一个 daemon **从未注册**时，同样操作**立刻成功**。机制未定（释放延迟 / sensor 未清理），
    但现象两次复现。✅ 规避：重拉前留 **≥20 s** 间隔；supervisor 重拉失败要做退避重试。

25. ⚠️ **`dumpsys <服务名>` 返回空 ≠ 服务不存在**：`vendor.qti.hardware.camera.aon.IAONService/default`
    是 **lazy AIDL 服务**（in-process 跑在 camera provider 内）。`dumpsys -l` 能列出它、
    但直接 `dumpsys <名字>` 返回空是常态（未被拉起/无客户端时不实例化）。
    别据此判"HAL 丢了"。要判 HAL 是否可用，看**客户端注册结果**（`REG ok=` / `cid`）。

26. ⚠️ **GD（GazeDetect, `srv=4`）不是配置级可切换的通道**：把 daemon 的 `srv` 换成 4，
    注册返回 **`transact_-38`（ENOSYS）**。想在模块里走原生那条"结构性非岛"的 GD 路，
    需要 daemon 侧**新增代码**（GD 注册负载与 FD 分支不同：`[present=0]`、无 mask/algo/w/h）。

27. ⚠️ **`aon_evt` 有两条方向相反的陷阱，任何"文件在不在长"的判据都会被各骗一次**（2026-09-17 实测）：
    - **心跳使它增长**：daemon 每 ~5 s 写一条 `HBT <ts> st=? rep=? cid=?` ⇒ 流**没注册**时文件
      照样在长（V2 实测：空闲态被"比大小"判活误判为"在跑"，白等 20 s 才靠重试兜住）。
    - **重启把它清零**：daemon 启动会重写（`55804 B → 61 B`、`EVT 1534 → 0`）⇒ 任何以绝对
      偏移/旧基准为依据的判据在跨重启时**反向失效**。
    ✅ 判"流在不在出帧"只能**数 `^EVT` 行**；对"新增的那段字节"数即可（`tail -c +N` 会 seek，
    代价 O(增量)），既躲开心跳、又不必全文件 grep。

28. ⚠️ **`HBT` 不能用来判"流注册了没有"**：它每 ~5 s 一条、**最多滞后 5 s**；而事件峰值可达
    ~30/s ⇒ `tail -n 20 "$EVT" | grep '^HBT'` 在热流下可能**一条都取不到**（实测空闲时 20 行里
    20 条 HBT，热流时可能 0 条）⇒ 把**正在跑的流误判为停**，进而多发一条 `state=start`（在这台
    daemon 上等于重启流，白白多花几秒）。取法改为**有界尾块** `tail -c 8192`（≈5~8 条 HBT）。
    另：`st=1` 要等**下一个 5 s 心跳**才可见 —— 别拿刚 `start` 完那一刻的 `st=0` 当失败。

29. ⚠️ **验收脚本要"确定性地"压分支，别指望时序凑巧**：想验证"窗口内 daemon 重启"这条分支，
    在真机上杀 daemon **很难卡准**（实测 `attention_ctrl test` 2.6 s 就命中，而杀手 3~4 s 才动手，
    连跑两次都没压到该分支，`re-baseline` 命中数 = 0）。
    ✅ 有效做法：用 `sed` 把 `attention_ctrl` 的 `AON_DIR` 一行改指到 **mock 目录**，用假 `aon_evt`
    **精确编排时序**（心跳期 → 重写 → 出帧），即可 100% 走到该分支，且完全不碰真机状态。
    脚本：`aon_rootcause/scripts/v182_v3c_mock.sh`。**"没压到分支"≠"分支没问题"**。

30. ⚠️ **模块 `system/` 是「开机时快照」—— 改 `system/bin/*` 必须重启才生效**（2026-09-17 实测闭环）：
    模块的 `system/` 树**不是**活动文件的 bind mount，而是**只读 overlay 的 lowerdir**：

    ```
    KSU on /system/bin type overlay (ro,seclabel,relatime,
      lowerdir=/mnt/<id>/<id>/<mod>/system/bin:/system/bin,redirect_dir=on)
    ```

    - **实测**：把新 `attention_ctrl`（md5 `1f3a2122…`）覆盖进模块目录后，`/system/bin/attention_ctrl`
      仍解析到**开机快照的旧 inode**（md5 `ec918348…`）；`cp` 进 `/system/bin/` 直接报
      **`Read-only file system`** —— 运行期无解。
    - **影响面**：**WebUI 的「立即测试」调的就是 `/system/bin/attention_ctrl`** ⇒ 改 `system/bin/*`
      后不重启，UI 跑的还是旧代码。而 `service.sh` / `post-fs-data.sh` 由管理器直接执行，
      改完**立即**生效，不受此限。⇒ 凡是"必须下次开机前就生效"的东西，要显式调用**模块目录副本**
      （`/data/adb/modules/<mod>/system/bin/<tool>`）来验证。
    - **验收口径**：重启后双侧 `md5sum` 必须相等，才算"部署完成"。复验脚本
      `aon_rootcause/scripts/v182_postreboot_verify.sh`：P0 断言覆盖层一致性（两侧 md5 相等），
      P1~P3 用 **`/system/bin` 路径**（= WebUI 的真实调用路径）跑冷/空闲/热三类 `test`，P4 看 ADSP 健康。
      2026-09-17 实测结果：P0 PASS（两侧 `1f3a2122`）、P1 冷路径 ×2 = 4300 / 4050 ms、
      P2 空闲 2370 ms、P3 热流 2340 ms 且 `startedByTest=false`（未误停）、ramdump 7 份无新增、`PD_ERR=0`。

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

19. ⚠️ **`attention_ctrl test` 返回 `ABSENT` 不能证明通道正常** —— 它等不到事件就按"无人脸"上报。
    表观特征：`code=0 / resultName="ABSENT (未检测到人脸)"`，每次**固定约 2.4~3 秒**（固定等待窗）。
    **第 8 轮定性的真因**（原文"读陈旧文件"的机制**作废**，v1.7 的 `attention_ctrl` 第 10-11 行读的
    就是 app 路径，与 daemon 一致）：
    ① v1.7 `attention_ctrl` 发的是**裸 `start`**，而命令通道是"**内容不变即忽略**"语义（见 24）
    ⇒ 重复调用时命令被静默丢弃，流从未注册；
    ② `supervisor.sh` 用错 IPC 路径（见 19b），daemon 一旦被它重拉就彻底收不到命令。
    ✅ **正确判据**见 20 / 23。**别用 test 的返回值当通断证据。**
19b. ⚠️ **模块内部 IPC 路径不一致（v1.8.0 仍在，属真实缺陷）**：`service.sh` §100 与
    `attention_ctrl` §20 都用 **app 路径**（`/data/data/futureharmony.tb522fu.aon/files/`），
    唯独 **`supervisor.sh` §64-66 用 `/data/adb/tb522fu_attention/`** —— 它是 v1.7→v1.8.0 之间
    唯一没跟着改的组件 ⇒ daemon 被 supervisor 重拉后控制面**永久失联**（app 写 app 路径、
    daemon 读 adb 路径），表现为"开关是开的但完全不工作"，直到下次开机。
    修法：把 supervisor 的两处路径改成 app 路径（一行）。
20. ⚠️ **daemon 的 CPU 累积增量**判据**不成立**（曾被它误导一轮）：断链态与恢复态实测**都是
    2 ticks / 25 s**。原因：daemon 主体阻塞在 poll 上等 binder 回调，单事件开销极小；
    第 6 轮 `daemon.out` 涨到 605 MB 增长的是**磁盘 I/O**，且当时 stdout 是**重定向到文件**的。
    supervisor 把 stdout 丢进 `/dev/null` 后，CPU 差异被抹平。
    ✅ **正确判据（可靠性降序）**：① **事件输出文件的 size/mtime 增量**
    （活跃 daemon 的 evt 文件每 15 s 涨 100~130 B；断链时纹丝不动）——与"是否在收事件"直接同构；
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

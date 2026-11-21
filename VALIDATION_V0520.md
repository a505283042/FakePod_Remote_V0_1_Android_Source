# V0.5.20 交付检查记录

基线：云端 `/esp32/android/Android_V0519_FAKEPOD_Connection_Help_Source.zip`。
参考扫描器：云端 R46138 完整 ESP32 源码中的 `tools/nas`。

## 已完成

- 核心回归测试 9/9 通过。
- MP3、FLAC、WAV、Opus 示例下，手机适配层生成的 V2 索引、manifest、meta 与原电脑工具逐字节一致。
- 短 ID 脚本与原工具一致；创建后的音频链接与源文件 inode 相同。
- 增量新增、删除统计、未变化标签复用、人工别名保留检查通过。
- 文件在扫描后变化、曲库版本被其他发布者修改、空曲库、大小写冲突路径均停止发布。
- 模拟索引部分替换后命令失败，旧索引逐字节恢复、旧音频别名 inode 恢复，新别名撤回。
- 锁内再次检查原曲库版本，冲突发布被拒绝。
- 新增及接线的四个 Kotlin 文件使用 Android API 35、真实 JSch/OkHttp/Chaquopy API 编译通过。
  此项为独立 Kotlin 类型及代码生成检查，不等同于完整 Gradle APK 构建。
- 两个复用的 Python 扫描器文件与 R46138 原文件字节一致。

## 未完成

- `assembleDebug` 已尝试，但当前执行环境无法建立 Gradle 依赖下载连接，未生成 APK。
- 没有 Android 手机、模拟器或真实 NAS 的 SSH/SFTP 联调结果。
- 没有对老 NAS 的具体 SSH 算法、SFTP 子系统、BusyBox 命令版本做实机确认。
- 没有进行手机端首次整库扫描的耗时、网络流量及 APK 体积测量。

## 本地首次验证

先编译安装，在 NAS 设置页选择目录，打开新增扫描入口。
建议先使用独立小测试曲库验证 SSH/SFTP、标签、歌词、封面、短链接和设备更新，
再用于正式曲库。完整操作及目录映射说明见 `NAS_MOBILE_UPDATE_V0520.md`。

ESP32 固件和原有遥控协议未修改；本版不需要刷固件。

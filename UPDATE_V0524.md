# Android V0.5.24：SMB 2.0.2 手机曲库管理

基于最新已交付 V0.5.23，versionCode 38。此前试写的 stat -t 扫描改版未交付，本版不依赖 stat。
ESP32 固件和设备的 WebDAV 播放方式不变。

## 改动

根据用户 Windows 查询结果，NAS `192.168.1.105` 的 `music`、`麦田广告` 共享均协商 SMB 2.0.2。
本版用 SMBJ 0.14.0，明确指定 SMB_2_0_2；不降级到 SMB1。

- SMB：遍历目录、读取大小与修改时间、按偏移读取标签、创建暂存目录、读取缓存、上传索引和回读校验。
- SSH：验证真实路径、检查符号链接、创建映射探针、创建短 ID 硬链接、执行发布/备份/失败回滚。
- WebDAV：保留设备原有播放配置；手机扫描不调用目录 PROPFIND，不通过 HTTP Range 读取音频。
  开始扫描前仅用一个小探针文件 GET，验证设备播放目录与 SMB/SSH 目录一致。
- 保留自动带入目录、扫描结果预览、进度条、手机索引兼容性与增量缓存。
- SMB 使用 445 端口；SSH 端口仍单独填写，这台 NAS 是33。NAS 不需要 SFTP、Python、Docker 或新服务。
- SMB 账号和共享路径在映射验证成功后记住，密码不保存。SSH 账号与 SMB 账号分开。
- 新传输绑定的扫描缓存标识有所变化，首次 SMB 扫描会重新解析标签，之后可复用。

## 填写

1. 外层先选择音乐目录，再进入“扫描并更新 NAS 曲库”。
2. SMB 账号/密码默认带入外层 NAS 设置。若不同，请填写电脑访问共享目录时实际使用的账号和密码，
   不要因为 SSH 使用 root 就把 SMB 账号也改成 root。
3. SMB 路径默认从外层路径带入，格式为 `/共享名/共享内目录`。
   如果使用“麦田广告”共享下的 Music，填写 `/麦田广告/Music`。
   如果使用“music”共享根目录，填写 `/music`；该共享是否指向同一物理音乐目录，要以映射验证为准。
   若需修改，勾选“修改 SMB 共享路径”。不填写 Z:、Windows 盘符或 /volume1。
4. SSH：192.168.1.105，端口33，账号root，使用实际密码。
5. SSH 真实路径继续自动预填；例如 `/volume1/麦田广告/Music`。其他存储卷需修改。
6. APP 会验证 SMB、SSH 与 WebDAV 是否对应同一目录。若映射不同，停止扫描并提示修改路径。
7. 扫描完成核对结果后确认发布，最后在设备点击“NAS曲库更新”。

更新期间暂停 FakePod 播放，保持 APP 页面和 Wi-Fi 开启，不同时运行电脑管理工具。

## 安装

补丁适用于 V0.5.23，不覆盖已经修好的 settings.gradle.kts。继续使用 Gradle 8.9 + JDK 17。

```powershell
cd "D:\ESP32\FakePod_Remote_V0_1_Android_Source"
git apply --check "C:\Users\YCB\Downloads\Android_V0524_NAS_SMB202_Catalog.patch"
git apply "C:\Users\YCB\Downloads\Android_V0524_NAS_SMB202_Catalog.patch"
git diff --check
.\gradlew.bat :app:installDebug
```

新增 SMBJ 及其依赖首次需要联网下载。已处理 JSch/SMB 依赖中重复的许可证和 OSGi 元数据打包规则。
完整源码无需再次应用补丁。

## 验证和限制

- 使用真实 JVM、SMBJ 与本地 Impacket SMB2 服务进行了实际协议读写，客户端只启用 SMB 2.0.2。
- 验证中文目录、空格/加号/井号/百分号文件名、文件大小和修改时间、随机读取末尾短段、
  不存在文件、限量读取、建目录、1MB 二进制上传与逐字节回读、取消扫描。
- 联调发现并处理旧服务器 NO_SUCH_FILE 与 OBJECT_NAME_NOT_FOUND 差异，
  读取按已知长度截断，不为探测结尾额外请求超出文件末尾的数据。
- 原有索引/发布核心回归9/9通过；接线 Kotlin 使用 API35 和真实依赖编译检查通过。
- 补丁在原版 V0.5.23 文件树回放、修改文件字节对比、ZIP完整性检查通过。
- 尚未执行完整 APK 构建、Android 手机运行或用户真实 NAS 的 SMB 联调。
- 按原索引流程使用秒级修改时间；同秒同大小的原地修改仍可能无法识别。
- 旧别名不自动清理。断电或发布中网络中断时，仍需依据原有 COMMITTED 标记及备份核对恢复。

SMB协议联调测试入口为 tests/SmbTransportCheck.kt；测试服务端仅在本地运行，不属于 APP 或 NAS 安装内容。

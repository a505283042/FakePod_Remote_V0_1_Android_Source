# Android V0.5.21：自动目录与扫描进度条

基于 V0.5.20，versionCode 35。ESP32 固件不变。

- 从上一页已选 WebDAV 音乐目录自动预填 SSH 路径。例如 `/麦田广告/Music` → `/volume1/麦田广告/Music`。
- 优先使用同一 NAS、同一已选目录上次验证成功的 SSH 路径；已经以 `/volumeN` 开头的路径不会重复加前缀。
- 默认只显示路径，勾选“修改 SSH 路径”后才显示编辑框。不同存储卷或自定义映射仍需修改；连接时会实际验证映射。
- SSH 账号使用上次保存的账号，未保存时沿用填写的 NAS 用户名；不强行默认 root。
  SSH 账号、端口和路径在目录映射验证成功后保存，SSH 密码不保存。
- 扫描目录时显示动态进度条；总曲目数确定后显示处理首数、总首数、百分比。
- 扫描完成显示100%，等待用户确认上传。上传、校验及硬链接发布阶段使用动态进度条，不虚构百分比。
- 更新时需要保持手机 APP 和网络开启。

## 应用补丁

适用于 V0.5.20。补丁不改 settings.gradle.kts，保留你本地已经验证成功的仓库设置。
完整源码包含 Maven Central 优先、Google 仓库排除 org.jetbrains 组的配置。
继续使用 Gradle 8.9 与 JDK 17。

```powershell
cd "D:\ESP32\FakePod_Remote_V0_1_Android_Source"
git apply --check "C:\Users\YCB\Downloads\Android_V0521_NAS_AutoPath_Progress.patch"
git apply "C:\Users\YCB\Downloads\Android_V0521_NAS_AutoPath_Progress.patch"
git diff --check
.\gradlew.bat :app:installDebug
```

完整源码无需再次应用补丁。

## 验证范围

新增界面代码使用真实 Android API35、JSch、OkHttp、Chaquopy API 完成独立 Kotlin 编译检查。
补丁在原版 V0.5.20 文件树上检查并回放，修改文件字节对比通过，源码 ZIP 完整性检查通过。
本版尚未执行完整 Gradle APK 构建或手机实测；V0.5.20 已由用户在 Windows 上构建成功。
扫描、索引、上传及 NAS 发布算法未改动。

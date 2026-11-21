# Android V0.5.28 · NAS 扫描后遥控连接恢复

基于已交付 V0.5.27。修补断线后的恢复缺口：优先直连上次成功通信的播放器 IP，失败后仍保留 UDP 发现；返回控制页或关闭 NAS 页面时检查连接；重连线程已退出时重新启动；流初始化/连接回调异常不再永久终止重连。健康连接保留。

这针对 APP 端恢复逻辑，暂不能证明真实断线的唯一原因。若播放器 Wi-Fi 本身离线或路由器阻断，仍需排查网络。

## 应用补丁

在 Android 工程目录打开 PowerShell。补丁假设放在下载目录；需以 V0.5.27 为基线，覆盖源码安装和应用补丁二选一。

```powershell
cd D:\ESP32\FakePod_Remote_V0_1_Android_Source
git apply --check "$env:USERPROFILE\Downloads\Android_V0528_NAS_Wifi_Recovery.patch"
git apply "$env:USERPROFILE\Downloads\Android_V0528_NAS_Wifi_Recovery.patch"
$env:JAVA_HOME = "C:\Users\YCB\.jdks\ms-17.0.20.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:installDebug
```

手机开启 USB 调试并连接电脑、允许调试授权。也可用 Android Studio 的 Run 安装。保留原应用安装，不要卸载，避免丢失应用数据。

## 验证

自动化网络测试通过：无 UDP 响应时使用已知 IP、连接回调异常后恢复、正常连接返回页面不重建、TCP 断线后重连、重连线程已退出后重新启动。全部 Kotlin 源码通过 Android 35 API 类型编译（资源 ID 使用验证桩），不等同于完整 APK 构建。补丁应用后文件一致性已验证。

请在手机安装后：连接播放器 → 扫描 NAS → 返回控制页 → 等待约几秒并操作播放/音量。再测试扫描期间切到后台后返回。无需预先开关手机 Wi-Fi；若仍失败，请提供控制页状态文字、APP 版本以及同一时刻播放器串口 Wi-Fi/遥控日志。真实手机与 NAS 场景尚待验证。

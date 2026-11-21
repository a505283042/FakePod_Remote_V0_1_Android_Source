# Android V0.5.30 · Wi-Fi接管后的BLE状态冲突修复

基于V0.5.29。用户截图显示“没有找到FakePod，请开启BLE”，不能单凭它证明Wi-Fi TCP仍在或已经断开。源码确认Wi-Fi接管后原BLE扫描定时器/自动连接未取消，BLE失败可覆盖Wi-Fi READY，使控制禁用；后续收到Wi-Fi状态也沿用错误state。本版针对这个确定的状态管理缺口，不能宣称已解决先前真实TCP反复断开。

Wi-Fi事件在主线程按顺序处理，避免与UI/连接状态竞争；接管后停止BLE扫描、取消超时与重连定时器、关闭旧GATT；Wi-Fi优先期间不重新发起BLE扫描和连接，迟到BLE回调不能降级Wi-Fi状态。真实Wi-Fi断开时保留V0529的具体断开原因，后台BLE回退搜索结果不再覆盖原因；BLE回退实际成功后仍可接管，或Wi-Fi恢复后继续以Wi-Fi为主。正常BLE单独使用保留。

V0529半帧恢复和3秒无完整状态诊断保留。本次不修改播放器固件、首次Wi-Fi关联、NAS扫描或音频逻辑。

## 应用安装

补丁放下载目录，在V0529工程执行。完整源码和补丁二选一。

```powershell
cd D:\ESP32\FakePod_Remote_V0_1_Android_Source
git apply --check "$env:USERPROFILE\Downloads\Android_V0530_Wifi_BLE_State_Handoff.patch"
git apply "$env:USERPROFILE\Downloads\Android_V0530_Wifi_BLE_State_Handoff.patch"
$env:JAVA_HOME = "C:\Users\YCB\.jdks\ms-17.0.20.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:installDebug
```

手机USB调试授权后直接覆盖安装，不卸载。播放器保留当前固件。

## 验证

提取实际连接状态函数，模拟BLE扫描中Wi-Fi接管、取消定时器/GATT、迟到BLE超时/错误/就绪、真实Wi-Fi断线原因保留、Wi-Fi重连、BLE实际回退成功及BLE单独使用，主机测试通过。半帧/自动重连回归通过。Android35全部Kotlin源码类型编译通过（资源ID使用验证桩），不是完整Gradle APK构建，真机待验证。

## 实测

连接后静置60秒，观察顶部是否仍出现BLE提示；再测试NAS播放、切歌、音量。如果还断开，截取顶部具体原因；Android Studio Logcat可按FakePodWifiRemote过滤，或adb在PATH时运行：

```powershell
adb logcat -v time -s FakePodWifiRemote
```

若出现“连续3秒无完整状态”，继续定位播放器状态发送/网络间隔；若为SocketException等，结合同时刻串口日志区分对端关闭或网络错误。请勿把曲名/进度仍显示当作连接仍有效的证据，它们可能是保留的上次状态。

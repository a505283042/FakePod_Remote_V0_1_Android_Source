# Android V0.5.29 · Wi-Fi TCP 半帧接收修复

基于云端当前 V0.5.28 源码。用户V0528实测反复断线/自动重连；播放器日志显示recv返回<=0，但未包含具体errno，因此不能据此证明手机主动关闭、链路复位等具体来源。本版修复已发现的APP代码缺口，不宣称已证实唯一根因。

旧版readFrame仅容忍帧头首字节250ms读超时；长度或payload读到一半超时会关闭整个会话。新WifiFrameReader保留半帧状态，读超时后继续接收，固定最大255字节payload。正常EOF/网络错误仍断开并自动重连；原3秒无完整状态期限保持不变，避免半帧或垃圾输入无限续命。不改变线格式或播放器协议。

断开状态显示原因；Android日志tag FakePodWifiRemote记录断开原因、最后完整帧间隔、设备地址，不记录NAS凭据或歌曲内容。没有修改首次Wi-Fi连接失败或FLAC首PCM超时。

## 安装

先下载补丁到“下载”目录，关闭Android Studio正在进行的构建，在V0528工程执行。覆盖完整源码与补丁二选一。

```powershell
cd D:\ESP32\FakePod_Remote_V0_1_Android_Source
git apply --check "$env:USERPROFILE\Downloads\Android_V0529_Wifi_Frame_Reassembly.patch"
git apply "$env:USERPROFILE\Downloads\Android_V0529_Wifi_Frame_Reassembly.patch"
$env:JAVA_HOME = "C:\Users\YCB\.jdks\ms-17.0.20.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:installDebug
```

手机开启USB调试，直接覆盖安装，避免卸载丢失设置。保留播放器当前固件测试，无需先修改固件。

测试连接后静置1分钟、NAS MP3/FLAC播放、连续切歌及音量控制。若仍断线，请记录APP断开原因；可在Android Studio Logcat按FakePodWifiRemote过滤，或电脑adb已在PATH时执行：

```powershell
adb logcat -v time -s FakePodWifiRemote
```

记录“连续3秒无完整状态”、发送/接收异常等文字，以及相同时刻播放器日志，继续定位。开机Wi-Fi reason204/201和遥控会话反复断开是不同阶段，不应混为一谈。

## 验证

半帧超时覆盖magic后、length后、payload中间；连续帧、EOF、255字节上限测试通过；原已知IP无UDP直连、回调异常恢复、健康连接不重建、socket断开重连、已退出线程重启测试通过。Android35 API全部Kotlin源码类型编译通过（资源ID验证桩），不等同完整Gradle APK构建，真机效果待确认。

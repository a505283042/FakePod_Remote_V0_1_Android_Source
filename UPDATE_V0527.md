# Android V0.5.27：记住 NAS 登录信息

基于 V0.5.26。只改 APP，固件无需更换。

- WebDAV、SMB、SSH 分别记住账号和密码，重新打开时自动填写。
- WebDAV 按服务器地址和端口保存；SMB 按服务器的 445 端口保存；SSH 按服务器地址和 SSH 端口保存。
- WebDAV 点击浏览目录、检查曲库、打开扫描或发送设置时保存；SMB/SSH 点击开始扫描时保存。均先做输入格式检查；网络登录失败时也保留本次输入，修改后再次操作会覆盖。
- 扫描结束后不再清空密码框。关闭扫描窗口仍会清理显示字段，但不删除本机保存的记录。
- 编辑服务器地址或端口时，只填入匹配的记录；没有记录则清空密码，避免把旧服务器密码自动用于新地址。
- 密码使用 Android Keystore AES-256-GCM 加密，密文保存于 APP 私有的 no-backup 目录。不使用明文密码偏好文件，不新增第三方依赖。卸载或清除 APP 数据后需重新输入，保留数据的正常覆盖安装可继续使用。
- 密码仍以掩码显示。扫描、检查点、SMB 权限、SSH 发布和设备配对流程不变。

## 安装

已有 V0.5.26 的源码项目，下载补丁并连接开启 USB 调试的手机：

```powershell
cd "D:\ESP32\FakePod_Remote_V0_1_Android_Source"
$env:JAVA_HOME = "C:\Users\YCB\.jdks\ms-17.0.20.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
git apply --check "C:\Users\YCB\Downloads\Android_V0527_NAS_Saved_Logins.patch"
git apply "C:\Users\YCB\Downloads\Android_V0527_NAS_Saved_Logins.patch"
.\gradlew.bat :app:installDebug
```

如果尚未应用 V0.5.26，请先升级到该源码版本，或使用本版完整源码到新目录打开。不要卸载 APP 再安装，以免清除既有设置和扫描检查点。

## 验证

已通过修改涉及的 Kotlin API 35 编译、JVM 加密测试（中文和特殊字符往返、随机 IV、协议/主机/端口隔离、防篡改、无效密文拒绝）、补丁试应用及源码 ZIP 校验。扫描/发布核心文件与 V0.5.26 保持一致。

此环境未运行手机 Android Keystore、APP 界面或完整 Gradle APK 构建。请在手机输入一次并操作后，关闭重开或重启 APP，确认三种账号密码自动填入；切换地址/端口后确认不会带入其他端点的密码。

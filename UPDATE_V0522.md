# Android V0.5.22：兼容没有 SFTP 子系统的 NAS

基于 V0.5.21；versionCode 36。ESP32 固件不变。
本说明取代旧版本说明中“必须开启 SFTP”的要求。

## 修复原因

用户电脑已验证：`sftp -P 33 root@192.168.1.105` 输入密码后返回
`subsystem request failed on channel 0`。这表明服务器拒绝启动 SFTP 子系统。
V0.5.20/21 的手机扫描依赖 SFTP，因此会出现 `failed to send channel request`。

## 新实现

- 不再请求 SFTP 子系统，也不依赖 SCP、NAS Python 或后台服务。
- 目录遍历和属性检查：WebDAV PROPFIND，处理成功的 propstat，要求文件大小和修改时间有效。
- 标签与封面定位：WebDAV HTTP Range；核对 Content-Range，限制读取量，不下载完整音乐。
  服务器不支持非零偏移的 Range 时会停止并明确提示。
- 索引、脚本和缓存上传：普通 SSH exec 通道的标准输入传输原始二进制，不依赖 NAS base64 命令。
- 上传后通过 SSH 原样回读，并在手机比较 SHA-256。
- 创建硬链接、发布锁、备份与失败回滚继续使用原有普通 SSH 命令流程。
- 继续保留主机指纹检查、自动预填音乐路径、可修改路径入口、密码不保存和扫描进度条。
- NAS 需支持普通 SSH 远程命令、WebDAV、HTTP Range、普通文件读写和同文件系统硬链接。
  需要的 NAS 命令在原发布命令基础上增加 `cat`、`pwd -P`、`find`。
- 连接时通过 SSH 检查真实根目录，并拒绝音乐目录树中的符号链接。

## 安装后填写

- NAS 地址：`192.168.1.105`
- WebDAV 端口：保持原来可浏览和播放的配置（与 SSH 端口分开）。
- SSH 端口：`33`
- SSH 账号：`root`
- SSH 密码：该账号实际密码，不保存。
- 音乐路径：沿用外层选择自动预填的路径，原目录通常为 `/volume1/麦田广告/Music`。
  连接时仍会验证 WebDAV 与 SSH 是否指向同一目录。

先扫描，确认数量及变化后再发布。更新时保持 APP 页面和 Wi-Fi 开启，暂停 FakePod 播放。
完成后在设备点击“NAS曲库更新”。

## 应用补丁

补丁基于 V0.5.21；保留你本地已经修好的 settings.gradle.kts。
完整源码包含之前验证过的仓库设置。继续使用 Gradle 8.9 与 JDK 17。

```powershell
cd "D:\ESP32\FakePod_Remote_V0_1_Android_Source"
git apply --check "C:\Users\YCB\Downloads\Android_V0522_NAS_WebDAV_SSH_NoSFTP.patch"
git apply "C:\Users\YCB\Downloads\Android_V0522_NAS_WebDAV_SSH_NoSFTP.patch"
git diff --check
.\gradlew.bat :app:installDebug
```

完整源码无需重复应用补丁。

## 验证与限制

- 新增 WebDAV 传输层在真实 JVM/OkHttp/XML 解析器与本地 HTTP fixture 上运行通过，覆盖中文、空格、加号、井号、百分号文件名，
  目录及文件属性、成功/失败 propstat 分离、非零偏移读取、末尾短分段、错误偏移拒绝、服务器忽略 Range 时的处理、越界目录拒绝与限量读取。
- 原索引/发布核心回归 9/9 通过。
- 新增及接线 Kotlin 文件完成 API35 与实际依赖 API 的独立编译检查；不等同于完整 APK 构建。
- 补丁试应用、源码对比和 ZIP 完整性检查通过。
- 未执行本版完整 Gradle APK 构建、Android 设备实测或真实 NAS 的新传输联调。
  普通 SSH 命令权限及该 NAS 的 WebDAV Range 支持仍需安装后确认。
- WebDAV 修改时间按秒处理，同秒同大小的原地修改仍可能不能识别。
- 断电或 SSH 发布中断后，仍按旧版备份/COMMITTED 标记流程核对，不承诺掉电事务恢复。

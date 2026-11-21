# Android V0.5.23：旧 WebDAV 连接兼容

基于 V0.5.22，versionCode 37。ESP32、SSH 上传、索引和硬链接发布逻辑不变。

用户反馈 unexpected end of stream，表示 HTTP 响应在读取结束前连接关闭；
目前没有完整请求日志，不能确认是连接复用还是服务端特定请求处理问题。
本版针对这两类兼容性做最小调整，并增加失败阶段提示。

- WebDAV 请求使用 HTTP/1.1，显式 Connection: close，不保留空闲连接。
- 所有扫描读取请求禁用压缩，保持字节偏移一致。
- 目录 PROPFIND 改为与外层浏览页相同的无请求体形式。
- 遇到读取 IO 异常时，使用新连接重试一次；不会把半段目录或音频数据当成完整内容。
- 最终错误包含目录属性读取、歌曲文件名与字节偏移、或 SSH/WebDAV 目录映射验证等阶段。
- 自动重试仅用于 WebDAV 只读操作，SSH 发布操作不增加自动重试。
- 独立连接可能降低扫描速度，但避免复用已被旧服务器关闭的连接。

## 应用

基于 V0.5.22，保留现有仓库配置、Gradle 8.9 和 JDK 17。

```powershell
cd "D:\ESP32\FakePod_Remote_V0_1_Android_Source"
git apply --check "C:\Users\YCB\Downloads\Android_V0523_NAS_Legacy_WebDAV_Connection.patch"
git apply "C:\Users\YCB\Downloads\Android_V0523_NAS_Legacy_WebDAV_Connection.patch"
git diff --check
.\gradlew.bat :app:installDebug
```

NAS、WebDAV 和 SSH 信息保持原样。再次扫描若失败，请提供本版完整的阶段提示。

## 验证

- 真实 JVM/OkHttp/XML 解析器与 HTTP/1.1 fixture 测试通过。
- 请求的 Connection: close、Accept-Encoding: identity、无请求体 PROPFIND 已由 fixture 断言验证。
- 模拟首次响应截断后成功重试，连续截断后明确失败并含阶段提示；不把截断数据当成功。
- 原有目录、中文特殊路径、属性、Range 边界和限量读取测试继续通过。
- 接线 Kotlin 文件使用 Android API35 与真实依赖完成独立编译检查。
- 补丁回放、文件对比和 ZIP 完整性检查通过。
- 尚未执行本版完整 APK 构建或真实手机/NAS 联调，不保证仅凭当前报错即可解决所有断流原因。

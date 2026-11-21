# FAKEPOD Android V0.5.19

Android 原生 BLE / Wi-Fi 配网控制端，对应 FakePod 固件 **R46.0.56 及后续 Wi-Fi Remote V1 版本**。

## 当前范围

- 保留 V0.1 已验证的 5 个手机控制命令，并新增绝对音量单次提交命令。
- 首次未配对时建立 Bond；保存 FakePod MAC。
- App 进程存活期间，FakePod 重启后继续使用 GATT autoConnect 自动重连。
- 订阅 FakePod Event Characteristic，实时显示：
  - 播放 / 暂停等 Audio 状态
  - 用户音量 / 静音
  - 当前歌曲名 / 歌手
  - 播放进度 / 总时长
  - FakePod 电量
- 状态同步改为事件驱动：播放状态、曲目、Seek、音量、电量等变化时立即发送；稳定播放仅每 15 秒校时一次。
- Android 收到位置锚点后使用 `SystemClock.elapsedRealtime()` 本地推进播放时间；App 前台每 1 秒只刷新本地 UI，不产生 BLE 流量。
- 歌曲元数据仍只在 Track / Catalog 变化时发送。
- 不依赖 HID，不要求扩大 ATT MTU。

这一版仍不包含后台常驻 Foreground Service、封面传输和播放进度拖动；音量已支持拖动调节。

## BLE 协议

Service UUID:

`7d7a9c10-2b4f-4b68-8f3a-0e6d46506f64`

Command Characteristic UUID:

`7d7a9c11-2b4f-4b68-8f3a-0e6d46506f64`

Event Characteristic UUID:

`7d7a9c12-2b4f-4b68-8f3a-0e6d46506f64`

### 手机 → FakePod

| HEX | 功能 |
|---|---|
| `01` | 播放 / 暂停 |
| `02` | 下一曲 |
| `03` | 上一曲 |
| `04` | 音量 +1% |
| `05` | 音量 -1% |
| `06 VV` | 绝对音量设置，`VV=0~100` |

`01`~`05` 仍是 1-byte 命令；`06` 为 2-byte `[0x06, volume_percent]`。音量滑块只在松手时提交一次 `06`，不再把目标值拆成几十次 `04/05`。Command Characteristic 使用普通 Write Request，FakePod 端要求链路加密。

### FakePod → 手机

Event Characteristic 使用 Read + Notify；Read 要求加密。Android 连接后写 CCCD 开启 Notify，再读取一次 Event Characteristic，既取得初始状态也恢复已 Bond 链路的加密。

所有多字节整数都是 little-endian。

#### `0x01` Status，固定 20 bytes

| Offset | Size | 内容 |
|---:|---:|---|
| 0 | 1 | `0x01` |
| 1 | 1 | 协议版本 `1` |
| 2 | 1 | `AudioPlaybackState`，无效=`0xFF` |
| 3 | 1 | 音量 0~100 |
| 4 | 1 | 电量 0~100，无效=`0xFF` |
| 5 | 1 | flags：bit0=静音，bit1=电量有效，bit2=Track有效，bit3=Video上下文，bit4=NSF上下文 |
| 6 | 4 | Track index，无效=`0xFFFFFFFF` |
| 10 | 4 | position_ms |
| 14 | 4 | duration_ms |
| 18 | 2 | metadata sequence |

#### `0x02` Metadata Chunk，6~20 bytes

| Offset | Size | 内容 |
|---:|---:|---|
| 0 | 1 | `0x02` |
| 1 | 1 | 协议版本 `1` |
| 2 | 2 | metadata sequence |
| 4 | 1 | chunk index，0-based |
| 5 | 1 | chunk count |
| 6 | 0~14 | UTF-8 payload |

所有分片重组后文本格式为：

`标题\n歌手`

FakePod 最多发送 196 bytes 元数据，超长文本只截断尾部，并保证不发送半个 UTF-8 字符。

## Android 权限

- Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`
- Android 11 及以下：BLE 扫描需要位置权限

## 本地更新

1. FakePod 刷入 R46.0.21。
2. Android Studio 打开本目录，等待 Gradle Sync。
3. 手机 USB 调试连接电脑。
4. Run `app`，Android Studio 会覆盖安装 V0.4.4。
5. 正常情况下无需删除旧 Bond；若升级后 Android 缓存旧 GATT 表、App 明确提示“控制服务版本不匹配”，再删除一次系统里的 FakePod 后重新配对。

## V0.2 验收

连接成功后 FakePod 日志应新增：

```text
BLE状态订阅：notify=1
```

App 应实时看到歌曲、播放状态、音量、电量和进度；原有 5 个控制按钮继续正常工作。FakePod 关机再开机后，App 保持运行时应自动重连并重新恢复状态 Notify。


## V0.3 连接恢复

- 已保存且系统仍为 Bonded 的 FakePod：打开 App 不再重复配对，直接建立/恢复 GATT。
- `autoConnect=true` 会话在距离断开后保持打开，不在 `STATE_DISCONNECTED` 时主动 `close()`；设备重新进入范围后由 Android 自动恢复连接。
- Activity 重新打开时复用 Application 中已有 GATT 会话，不重复 `connectGatt()`。
- 首次配对或“重新搜索 FakePod”仍使用直接连接；若该连接断开，再切换到持久 autoConnect 会话。


## V0.3.1 手机蓝牙开关恢复

- 监听 `BluetoothAdapter.ACTION_STATE_CHANGED`。
- 手机蓝牙关闭时立即把 UI 切到离线状态，并关闭已经失效的 GATT 会话。
- 等到系统进入 `STATE_ON` 后，使用已保存且仍 Bonded 的 FakePod 地址重新建立 `autoConnect=true` 会话。
- 不删除 Bond、不重新配对；距离断开逻辑保持 V0.3 行为不变。

## V0.4.0 正式版界面

- 只重做 UI，不修改 BLE、Bond、自动重连、状态 Notify 和 5 个控制命令逻辑。
- 统一为深色播放器遥控界面：品牌头部、连接状态卡、Now Playing 卡、进度条、音量/电量指标、播放控制区和设备管理区。
- 连接状态按 READY / 连接中 / 离线 / 错误使用不同状态色；命令发送提示仍沿用 V0.3.3 的 2 秒自动恢复逻辑。
- 控制按钮在未连接时统一弱化，连接 READY 后恢复完整视觉状态。


## V0.4.1 界面精修

- 设备连接管理从首页移入右上角设置面板：首页只保留连接状态。
- 音量与电量改为图标 + 数值，不再使用文字标签占空间。
- 播放控制按钮缩小，去掉按钮下方说明文字，控制区更紧凑。
- 音量改为 0~100% 拖动条；松手后 App 基于现有 `0x04/0x05` 每次 ±1% 命令串行补齐目标值，因此 FakePod 固件和 BLE 协议无需修改。
- 拖动音量期间只在最终目标完成后显示一次确认，不会为每个 1% 步进刷“已发送”提示。


## V0.4.2 极简播放器界面

- 去掉首页 `NOW PLAYING`、`CONTROLS` 和底部版本号，减少模板化标签。
- 连接状态移除外层大卡，只保留紧凑状态条；品牌头部、歌曲卡与指标卡同步压缩留白。
- 播放控制进一步缩小，取消控制区分割线，让传输控制和音量调节通过留白分组。
- 音量滑块去掉右侧重复百分比；滑块左右保留 10dp 安全区，并关闭 splitTrack，修复 100% 时 thumb 右侧被裁切。
- 拖动音量时上方音量数值实时跟随，松手后仍沿用 V0.4.1 的串行 ±1% BLE 调节，不修改 FakePod 固件协议。
- 设置面板去掉 `DEVICE & CONNECTION` 英文标题，缩小间距与按钮，并将版本更新为 V0.4.2。


## V0.4.3 BLE State V2 / 本地播放时钟

- 配套 FakePod R46.0.15：取消稳定播放时每 500ms 的状态 Notify，改为“事件变化立即同步 + 15 秒位置校时”。
- `0x01 Status` 的 20-byte 格式和协议版本保持不变，因此没有新增 GATT UUID。
- Android 将每个 Status 的 `position_ms` 作为时间锚点，并记录 `SystemClock.elapsedRealtime()`；播放中由手机本地单调时钟推进进度。
- Activity 仅在前台每 1 秒刷新一次本地时间/进度条；退到后台立即停止这个 UI ticker。
- Pause / Resume / Track change / Seek / Volume / Battery 等状态仍由 FakePod 事件驱动同步；15 秒周期包负责长期漂移校正。


## V0.4.4 音量单次提交

- 配套 FakePod R46.0.21，新增 `0x06 VV` 绝对音量命令。
- 音量滑块拖动期间仍只做本地预览；松手后只发送一次 2-byte GATT Write。
- 删除 V0.4.1~V0.4.3 的逐 1% 串行补齐队列，避免一次大幅音量调整产生几十条 BLE 写请求与日志。
- 旧的 `0x04/0x05` ±1% 命令继续保留，兼容既有控制入口。


## V0.4.6 NSF Remote Context

- 配套 FakePod R46.0.50。
- 复用现有 `0x01/0x02/0x03` 播放/下一曲/上一曲命令，不新增 GATT UUID 或命令。
- FakePod NSF 会话激活时，三个传输控制命令优先路由到 NSF：播放/暂停、下一 Track、上一 Track。
- Status `flags.bit4` 标记 NSF 上下文；手机自动切换 Now Playing 为电子音流标题、作者/Track、进度与总时长。
- NSF 返回 `/synth` 列表但仍在后台播放时，手机继续控制 NSF；真正离开电子音流 APP 后恢复 Music 控制。
- 音量继续使用现有全局音量命令，不修改 NSF Synth、Strict Loop、Linear Timeline 或 Event-only。


## V0.4.7 Wi-Fi 配网

- 设置页自动读取手机当前连接的 Wi-Fi SSID。
- Android 对 SSID 属于受保护的 Wi-Fi 信息；App 会请求对应运行时权限，密码仍只由用户输入且不在手机端保存。
- 用户只输入一次 Wi-Fi 密码；App 不保存密码副本。
- SSID + 密码通过现有加密 Command Characteristic 以 <=20B 分片下发，不新增 GATT Characteristic。
- FakePod 接收完整配置后保存 NVS，先关闭 BLE，再启动 Wi-Fi STA。
- Wi-Fi 连接失败时固件会清理 Wi-Fi 并恢复 BLE；App 保持自动重连以接住失败回退。
- 本版只完成配网与 BLE->Wi-Fi handoff；Wi-Fi 媒体控制和 NAS 播放在后续版本接入。


## V0.4.8 当前 Wi-Fi SSID 读取恢复

- BLE 运行权限与 SSID 读取权限解耦；未授权位置权限不会阻塞 FakePod BLE 控制。
- 当前 SSID 按 NetworkCapabilities active network、全部 Wi-Fi network、WifiManager.connectionInfo 三层回退读取。
- Android 将 SSID 视为位置敏感字段；配网时若缺少 ACCESS_FINE_LOCATION，会明确要求用户授权并选择“精确”。
- 系统位置服务关闭时，配网按钮直接引导到位置设置；返回 APP 后自动刷新当前 SSID。
- 固件协议与 R46.0.55 不变。


## V0.4.9 SSID 网络状态权限修复

- 补充 `ACCESS_NETWORK_STATE`，允许 ConnectivityManager 读取当前网络状态。
- ConnectivityManager 与 WifiManager 两条 SSID 读取路径分别捕获权限异常；第一条失败不会再跳过第二条兜底。
- 固件协议与 R46.0.55 不变。


## V0.5.0 Wi-Fi Remote Control

- FakePod Wi-Fi 接管后，App 通过 UDP 自动发现设备并建立 TCP 控制长连接。
- 播放/暂停、上一首、下一首、音量继续使用原 0x01~0x06 语义。
- Status 20B 与 Metadata Chunk payload 与 BLE V1 保持一致，歌曲/视频/NSF 状态解析无需分叉。
- Wi-Fi 控制掉线后继续尝试局域网发现；固件若恢复 BLE，原 autoConnect 链路继续接管。
- 本版仍不包含 NAS 音频流。


## V0.5.1 Wi-Fi Reboot Auto Reconnect

- 修复 FakePod 硬重启后旧 TCP 连接可能长期处于 half-open，App 误判为仍在线的问题。
- Wi-Fi 状态正常每 500ms 到达；连续约 3 秒没有收到任何 FakePod frame 时，App 主动关闭旧 TCP。
- 旧连接收口后自动回到既有 UDP discovery 循环，FakePod 重启上线或 IP 变化后无需手动“重新连接”。
- 不修改 UDP/TCP 协议，不修改固件；兼容 R46.0.56 及后续 R46.0.57。

## V0.5.2 Wi-Fi Last-IP Discovery Fallback

- 保留 V0.5.1 的 3 秒 stale TCP 回收，不改变 BLE/Wi-Fi 自动选择策略。
- 每次收到首个有效 FakePod frame 后记住本次 Wi-Fi Remote IPv4 地址。
- 后续 UDP discovery 同时向“上次成功 IP”单播和原有广播地址发送查询；路由器/Android 偶发丢广播时可直接重新发现。
- 单播仍必须收到 `FAKEPOD_REMOTE_V1` 回复后才建立 TCP；IP 变化时原有子网广播仍可重新发现，不会盲连旧地址。
- UDP/TCP 端口和协议帧保持不变。

## V0.5.3 Wireless Status UI

- BLE READY is shown explicitly as `FakePod 蓝牙已连接`, matching the existing Wi-Fi connection wording.
- Wi-Fi behavior and transport protocol are unchanged.

## V0.5.4 Stage 1：NAS WebDAV 可视化浏览

- 本次基于**V0.5.3**，保留 BLE Bond、Wi-Fi 46320/46321 V1 和全部现有播放按钮。
- 「设置 → NAS 音乐服务器 · 浏览目录」新增 NAS IP、端口、用户名、密码输入；通过 WebDAV `PROPFIND Depth:1` 获取 NAS 文件夹，逐层选择 Music 根目录。
- 选择目录后读取 `Music/_FakePod/music-index/fakepod_catalog.meta`，并检查 `Music/_FakePod/track/` WebDAV 路径可访问；仅把 IP、端口、用户名、音乐路径保存在**Android 本机设置**。
- NAS 密码在当前打开的界面内使用，不存入 SharedPreferences，也**不会传给 FakePod**。
- **尚未完成**：安全设备配对、带认证的配置发送、ESP32 端持久化与热切换。当前 R46.0.117 继续使用 TF 卡 `nas_catalog.conf`；本阶段点击「保存」不会改变设备正在使用的 NAS。
- 当前固件仅支持 HTTP WebDAV 5005 等自定义 HTTP 端口，Android 为在可信局域网使用启用了 HTTP cleartext。此模式 HTTP Basic 凭证在 LAN 上不加密；请用专门只读 NAS 账号，不要开放至互联网。
- 新增 OkHttp 4.12.0 依赖，用于正确发送标准 WebDAV `PROPFIND` 请求；Android Studio 首次 Gradle Sync 时需要下载依赖。
- 工程**未在云端编译 Android APK**，由用户在 Android Studio 编译并验证。下一阶段在收到实机回报后完成设备配置通道。


## V0.5.5：NAS 文件夹列表 UI 小修

- 使用独立文件夹卡片，最小行高 58dp，每行间隔 8dp，长文件夹名最多两行并省略，防止重叠。
- 「上一级」「选择此目录」按钮精简，小屏显示宽度分配，滚动区限制为屏高 43%。
- 目录路径显示最多两行，动态加载期间丢弃旧路径响应。保留原 WebDAV 访问与验证逻辑。
- **没有设备端配置下发**；保存只在手机偏好设置里保留非密码字段。Android Gradle 编译仍需本地验证。


## V0.5.12：FAKEPOD 品牌图标与名称

- 桌面应用名称统一为 `FAKEPOD`。
- Launcher 图标替换为新的 FAKEPOD NANO 蓝色设备图标，兼容 legacy mipmap 与 Android 8+ adaptive icon。
- 主界面及设置页应用标题统一为 `FAKEPOD`。
- BLE、Wi-Fi、NAS、遥控协议与交互逻辑不变。

## V0.5.13：Launcher 安全边距

- 基于 V0.5.12 品牌版，APP 名称继续保持 `FAKEPOD`，包名与 BLE/Wi-Fi/NAS 协议不变。
- 原始 FAKEPOD NANO 图标整体按约 86% 缩小并居中；外圈用原图蓝色系背景延展补齐，不使用透明/白色安全边。
- 重新生成 legacy mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi 与 Android 8+ adaptive foreground。
- adaptive icon 背景色同步调整为图标蓝色系，降低不同 Launcher 圆形/圆角遮罩裁掉蓝色边框的概率。


## V0.5.14：Launcher 80% 安全边距 + 干净背景

- FAKEPOD NANO 主体调整为约 80% 居中，四周留出更完整的 Launcher 安全区。
- 重做蓝色连续渐变背景，原图外围白色/阴影不再被带入前景，避免上一版的白色毛边。
- Android 8+ adaptive icon 的 background 也改为同源渐变图，legacy mdpi~xxxhdpi 同步重生成。
- APP 名称仍为 `FAKEPOD`；BLE、Wi-Fi、NAS 和遥控协议不变。


## V0.5.15：用户指定 512×512 正方形图标

- 直接采用用户提供的 512×512 FAKEPOD NANO 正方形图作为图标视觉母版，不再人为绘制外层圆角或额外补边。
- 新增 `branding/play_store_icon_512.png`：512×512、32-bit RGBA、sRGB，供 Google Play 使用。
- legacy launcher 的 mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi 只做等比例缩放，不预裁圆角。
- Android 8+ adaptive icon 前景使用同一图像；背景仅为全铺蓝色渐变兜底，最终形状由 Launcher 动态遮罩。
- APP 名称保持 `FAKEPOD`；BLE、Wi-Fi、NAS、遥控协议和交互逻辑不变。


## V0.5.16：用户指定较小主体的正方形图标

- 基于 V0.5.15，继续采用用户新提供的 512×512 FAKEPOD NANO 正方形图作为视觉母版。
- 新母版主体比上一版更小，安全边距更大，更适合 Android Launcher 的圆形、圆角方形和 squircle 动态裁切。
- `branding/play_store_icon_512.png` 更新为新的 512×512、32-bit RGBA、sRGB 图标。
- legacy launcher 的 mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi 与 Android 8+ adaptive foreground 全部由新母版重新生成。
- adaptive icon 的 background 继续保留蓝色渐变兜底背景；APP 名称保持 `FAKEPOD`；BLE、Wi-Fi、NAS、遥控协议与交互逻辑不变。


## V0.5.17：NAS 设置页填写提示

- 明确 NAS IP 不加协议前缀、WebDAV 端口以 NAS 配置为准、账号需具备目录读取权限。
- 将索引检查、设备配对和发送步骤改成短句，引导按正确顺序操作。
- 仅修改 NAS 设置页可见文字和版本，不改网络连接、账号保存、加密配对或任何协议逻辑。


## V0.5.18：NAS 操作顺序与提示颜色

- 设置页底部操作顺序改为 ① 选音乐目录 → ② 检查索引 → ③ 设备配对 → ④ 填码发送。
- 底部提示文字使用浅蓝色 `#72BFFF`；普通字段说明保持原样。
- 不修改 NAS 配对、存储、通信或其他页面功能。


## V0.5.19：NAS 步骤按钮序号与连接入门说明

- NAS 浏览、索引检查和发送按钮分别标注 ①②④；设备配对文字标注 ③，和底部步骤一致。
- 设置面板增加简短的蓝牙与 Wi-Fi 用途说明，提示配网使用 2.4GHz Wi-Fi。
- 保留蓝色操作提示；不修改 BLE / Wi-Fi / NAS 协议、业务逻辑与图标。

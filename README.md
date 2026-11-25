# FAKEPOD Remote（Android）

Android 原生 BLE / Wi-Fi 遥控端，配合 FakePod 播放器固件使用。当前版本 **0.5.44**。

- 包名：`com.fakepod.remote`
- minSdk 26 / targetSdk 35 / JDK 17
- 蓝牙 BLE 与 Wi-Fi 双通道自动选择：BLE 优先建立加密链路，设备接管 Wi-Fi 后自动切换到 TCP 长连接，掉线回退 BLE

## 功能

- **播放控制**：播放 / 暂停、上一曲、下一曲、绝对音量（0~100 拖动条，松手单次提交）
- **实时状态**：播放状态、曲目名 / 歌手、播放进度与总时长、电量、静音；进度由手机本地时钟推进，仅事件变化与 15 秒校时产生流量
- **设备快捷控制**：唤醒解锁、锁屏、息屏显示（AOD）、熄屏、循环模式、音乐视图、频谱样式、磁带封面偏移
- **睡眠关机定时**：关闭 / 30 / 60 / 90 / 120 分钟，到点设备保存现场进入 Deep-sleep；唤醒（K1 点按）或复位后自动归零
- **屏幕亮度**：5~100% 远程调节，立即生效并持久化
- **音频输出模式**：普通耳机 / 高阻耳机 / 线路输出远程切换，先切硬件再保存，失败自动回滚
- **NAS 音乐目录**：WebDAV / SMB 浏览、索引检查、设备配对与配置下发
- **Wi-Fi 配网**：读取当前 SSID，把 2.4GHz Wi-Fi 凭据加密下发给设备

## 构建

- Android Studio（Gradle 8.11+ / AGP 8.9+），JDK 17
- 首次 Gradle Sync 需联网：下载 Chaquopy Python 运行时，以及 smbj（SMB）、jsch（SSH/SFTP）、OkHttp（WebDAV）依赖
- `gradlew :app:assembleDebug` 或 Android Studio Run `app`

## 通信协议

BLE 与 Wi-Fi 共用同一套命令 / 事件字节语义。

### BLE GATT

| 角色 | UUID |
|---|---|
| Service | `7d7a9c10-2b4f-4b68-8f3a-0e6d46506f64` |
| Command（Write Request，需加密） | `7d7a9c11-2b4f-4b68-8f3a-0e6d46506f64` |
| Event（Read + Notify，需加密） | `7d7a9c12-2b4f-4b68-8f3a-0e6d46506f64` |

### 命令（手机 → 设备）

多字节整数为 little-endian。

| HEX | 载荷 | 功能 |
|---|---|---|
| `0x01` | — | 播放 / 暂停 |
| `0x02` | — | 下一曲 |
| `0x03` | — | 上一曲 |
| `0x04` | — | 音量 +1% |
| `0x05` | — | 音量 -1% |
| `0x06` | `[0x06, volume]` | 绝对音量，volume = 0~100 |
| `0x10` | `[0x10, mode]` | 循环模式 |
| `0x11` | `[0x11, view]` | 音乐视图切换 |
| `0x12` | — | 唤醒并解锁 |
| `0x13` | — | 锁屏 |
| `0x14` | — | 息屏显示（AOD） |
| `0x15` | — | 熄屏 |
| `0x16` | `[0x16, dir]` | 磁带封面偏移 |
| `0x17` | `[0x17, style]` | 频谱样式 |
| `0x18` | `[0x18, ...]` | 本地播放定位 |
| `0x19` | `[0x19, minutes]` | 睡眠关机定时，minutes ∈ {0, 30, 60, 90, 120}，0=关闭 |
| `0x1A` | `[0x1A, level]` | 屏幕亮度，level = 5~100 |
| `0x1B` | `[0x1B, mode]` | 音频输出模式：0=普通耳机，1=线路输出，2=高阻耳机 |
| `0x20` | 分片 | Wi-Fi 配网（SSID + 密码加密下发，≤20B 分片） |

`0x19` / `0x1A` / `0x1B` 为系统设置，不要求音乐前台即可下发；值域在设备端协议层与服务层双重校验。

### 事件（设备 → 手机）

连接建立后设备会推送一次全量状态，之后变化即推并周期性重发。

| HEX | 内容 |
|---|---|
| `0x01` | Status，固定 20 字节：播放状态、音量、电量、flags、Track index、position_ms、duration_ms、metadata sequence |
| `0x02` | Metadata Chunk，分片重组为 `标题\n歌手` |
| `0x03` | Remote UI 控件状态 |
| `0x04` | 可视化控件状态 |
| `0x05` | 本地定位反馈 |
| `0x06` | Wi-Fi 媒体库帧 |
| `0x07` | BLE Seek 反馈 |
| `0x08` | `[0x08, version, minutes]` 睡眠定时回读 |
| `0x09` | `[0x09, version, level]` 屏幕亮度回读 |
| `0x0A` | `[0x0A, version, mode]` 音频输出模式回读 |

`0x01` Status 20 字节布局：

| Offset | Size | 内容 |
|---:|---:|---|
| 0 | 1 | `0x01` |
| 1 | 1 | 协议版本 `1` |
| 2 | 1 | `AudioPlaybackState`，无效 = `0xFF` |
| 3 | 1 | 音量 0~100 |
| 4 | 1 | 电量 0~100，无效 = `0xFF` |
| 5 | 1 | flags：bit0=静音，bit1=电量有效，bit2=Track 有效，bit3=Video 上下文，bit4=NSF 上下文 |
| 6 | 4 | Track index，无效 = `0xFFFFFFFF` |
| 10 | 4 | position_ms |
| 14 | 4 | duration_ms |
| 18 | 2 | metadata sequence |

## Android 权限

- Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`
- Android 11 及以下：BLE 扫描需要位置权限
- Wi-Fi 配网读取当前 SSID 需要 `ACCESS_FINE_LOCATION`（精确）与位置服务开启

## 版本演进

| 版本 | 关键变化 |
|---|---|
| 0.5.44 | 睡眠关机定时、屏幕亮度、音频输出模式支持 APP 远程设置（命令 0x19/0x1A/0x1B，事件 0x08/0x09/0x0A）；输出模式顺序调整为普通耳机 / 高阻耳机 / 线路输出 |
| 0.5.4x | NAS 独立浏览、音乐库空选修复、电源传输策略 |
| 0.5.12~0.5.16 | FAKEPOD 品牌名称与 Launcher 图标（含 512×512 母版） |
| 0.5.4~0.5.5 | NAS WebDAV 可视化浏览与目录选择 UI |
| 0.5.0~0.5.2 | Wi-Fi Remote 控制、掉线回收与 Last-IP 发现回退 |
| 0.4.7~0.4.9 | Wi-Fi 配网与 SSID 读取权限修复 |
| 0.4.4 | 绝对音量单次提交（`0x06`） |
| 0.4.3 | BLE State V2：事件驱动同步 + 15 秒位置校时 + 手机本地播放时钟 |

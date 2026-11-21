# FakePod Android Phone Cover Art — experimental branch

Branch: `feat/phone-cover-ble-fallback`
Base: `main` at `640ff29f4f6e6d63a6f8e95121820d241d29163e`
Android version: `0.5.46-cover-art-speed` (versionCode 59).
ESP32 peer: `FakePod_S31/feat/phone-avrcp-cover-art` (`7c56126` or later).

## Scope

- Reuse the existing **encrypted BLE Command characteristic** (UUID ending `...9c11`), not a second GATT connection.
- Read artwork only after the user explicitly enables the feature and grants Android Notification Access to `FAKEPOD 手机音乐封面`.
- Prefer active QQ Music (`com.tencent.qqmusic`) MediaSession; if none is playing, use a single unambiguous active player.
- Try `METADATA_KEY_ART`, `ALBUM_ART`, `DISPLAY_ICON`; then corresponding content URI; then the large icon of a media notification.
- Never scrape private QQ Music files, cookies, account data, streaming audio or remote APIs.
- Scale and encode JPEG in a single background thread, limit to 192 KiB; keep last JPEG for BLE reconnection.
- Ignore changes to TITLE when computing artwork identity, because some players publish lyric lines through the title field. Prefer Media ID or album/artist/duration and playback-position reset.
- Commands `0x40` BEGIN, `0x41` DATA, `0x42` FINISH are sent with Write Request, in order, waiting for each write callback.
- Request BLE MTU 247 *after* GATT initialization; fall back to MTU 23 if needed. Max frame = `min(MTU-3,244)`.
- BLE cover transfer yields to existing library/Wi-Fi provisioning transfers; existing remote-control write callbacks remain intact.
- UI: Settings → `手机音乐封面同步 · QQ 音乐诊断（实验）`, opt in/out, check Notification Access, inspect current status.
- A visible `FakePodPhoneCoverService` foreground notification keeps the original app-owned BLE session alive while QQ Music is in the foreground; no separate BLE connection.

## Build

Open repository root in Android Studio (JDK 17 / Android SDK 35) and run `app`, or Windows PowerShell:

```powershell
git fetch origin
git switch -c feat/phone-cover-ble-fallback --track origin/feat/phone-cover-ble-fallback
.\gradlew.bat :app:assembleDebug
```

Protocol test (if local `kotlinc` is available):

```powershell
kotlinc app/src/main/java/com/fakepod/remote/cover/FakePodCoverPackets.kt app/src/main/java/com/fakepod/remote/cover/FakePodCoverUploader.kt tests/PhoneCoverPacketSmoke.kt -include-runtime -d cover-smoke.jar
java -jar cover-smoke.jar
```

## Real phone test

1. Flash S31 Cover Art firmware and select `蓝牙音频接收`. Pair `FakePod Audio` (Classic A2DP).
2. Start the Android Remote App and connect its existing `FakePod` BLE remote (this is a distinct BLE connection using the same device).
3. In App Settings open `手机音乐封面同步`, enable it, then grant Notification Access in system settings.
4. Start QQ Music. Reopen the diagnostic dialog. Expect QQ media session and image status, then BLE upload.
5. S31 serial should eventually contain `Android album cover complete bytes=...` and `AVRCP_COVER surface requested...`. Open cover or cassette UI to check actual image.
6. Switch song quickly and test pause/resume, disconnected/reconnected BLE, MTU 247/23, and volume/next controls while a cover is being uploaded.

## Limitations / verification

- QQ Music might expose no image through MediaSession, content URI or media notification. This depends on the installed Android/QQ versions; the App reports this condition rather than inventing a cover.
- MediaSession TITLE may contain lyrics; without a stable Media ID, two different tracks sharing artwork, album/artist/duration may need an elapsed-position reset to trigger an upload. This is a heuristic, not guaranteed track ID.
- A GATT write ACK confirms successful transfer of that fragment, **not that CoverSurface decoded or displayed the JPEG**. Verify S31 serial and UI.
- **Background sync**: when the user enables cover syncing, the App starts a visible `connectedDevice` foreground service so the existing BLE GATT stays alive while QQ Music occupies the screen. Disabling cover sync stops the service and restores the original 30-second background grace period. Android can still stop background services under system resource limits.
- Firmware's native AVRCP Cover Art and Android fallback share the same Phone CoverSurface cache. Do not enable automatic transmission through Wi-Fi command protocol; this branch uploads over encrypted BLE only.
- Android Gradle APK build and hardware upload require local SDK/phone: not executed by this GitHub-only change.

## 0.5.46 Speed & observability update

Previous experimental builds kept the BLE connection at `CONNECTION_PRIORITY_LOW_POWER`
even during image transfer and inserted 12ms after **each** acknowledged GATT packet.
With hundreds of packets, total artwork latency can be tens of seconds.

- Remove the fixed 12ms delay, leaving normal Handler asynchronous message dispatch.
- Request `CONNECTION_PRIORITY_HIGH` **only during cover transfers**, then restore
  the existing low-power profile afterwards; the existing library transfers continue
  using balanced mode.
- Keep `WRITE_TYPE_DEFAULT` (acknowledged) and the original 0x40/0x41/0x42
  protocol; **do not** switch to Write Without Response without changing both ends.
- Retain the original JPEG quality/resolution until measured results justify a
  separate bandwidth/quality trade-off.
- Record `FakePodCoverPerf` logcat for artwork encode time and BLE transfer:
  JPEG bytes, number of acknowledged packets, transfer duration and MTU.
- S31 logs `Android album cover complete bytes=...` when the frame stream finishes,
  and `AVRCP_COVER surface requested...` when the surface work is queued.
  Completion of a GATT write only means a packet was acknowledged.

For diagnostic capture in PowerShell (use the Android SDK adb path if necessary):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s FakePodCoverPerf FakePodBle
```

Observe whether the log shows `BLE 封面 MTU=247`. With smaller negotiated
MTU, the same JPEG requires vastly more packets. Also check the S31's
`A2DP_RX drop` while temporarily using high connection priority; if Bluetooth
audio starts dropping samples, lower the transfer priority to BALANCED.

**Performance target, not guarantee:** aim for approximately 2–5 seconds for
moderate-sized JPEGs on compatible phone/BLE links; actual time depends on
MTU, connection interval, phone stack and JPEG size. The full Android Gradle
build and phone-to-S31 performance validation remain local tasks.

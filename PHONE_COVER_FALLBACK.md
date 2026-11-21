# FakePod Android Phone Cover Art — experimental branch

Branch: `feat/phone-cover-ble-fallback`
Base: `main` at `640ff29f4f6e6d63a6f8e95121820d241d29163e`
Android version: `0.5.45-cover-art-experimental` (versionCode 58).
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
- MediaSession observation can remain active via Notification Listener after the app is backgrounded, but the existing BLE client still obeys its original background power policy; no new permanent background BLE foreground service was introduced.
- Firmware's native AVRCP Cover Art and Android fallback share the same Phone CoverSurface cache. Do not enable automatic transmission through Wi-Fi command protocol; this branch uploads over encrypted BLE only.
- Android Gradle APK build and hardware upload require local SDK/phone: not executed by this GitHub-only change.

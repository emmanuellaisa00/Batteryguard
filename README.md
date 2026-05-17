# BatteryGuard 🔋🔒

**by laisadevstudio**

A native Android kiosk-style battery guardian app. When battery drops below a configurable threshold, the device is locked into a fullscreen overlay that cannot be dismissed until the battery is sufficiently charged.

## Features
- 🔴 **Kiosk lock** via Android Lock Task Mode (Device Admin)
- ⚡ **Only triggers after authentication** — does NOT appear on lock screen; fires after user enters PIN/password
- 🛡️ **Blocks all navigation** — back, home, recents, volume keys all disabled while locked
- ⚙️ **Configurable threshold** — set your own lock percentage (5–80%)
- 🔁 **Persistent service** — survives app kills, restarts on boot
- 📱 Automatically registers as HOME launcher

## Setup
1. Install APK
2. Grant **Device Administrator** permission (required for kiosk mode)
3. Grant **Display Over Other Apps** permission
4. Set your desired battery threshold in the app
5. Done — the service runs silently in the background

## How it works
```
Battery drops below threshold
         ↓
Guard flag = true (service running)
         ↓
User enters PIN/password (ACTION_USER_PRESENT)
         ↓
OverlayActivity launches → startLockTask()
         ↓
Fullscreen kiosk shown — all controls blocked
         ↓
Battery reaches threshold → stopLockTask() → device unlocks
```

## Build
GitHub Actions builds both debug and release APKs on every push.

## Making the lock UNBREAKABLE (Device Owner mode)

By default, `startLockTask()` with Device Admin can be dismissed by pressing **Back + Recents** simultaneously. To make it truly unkillable, set BatteryGuard as **Device Owner** via ADB:

```bash
# 1. Enable USB Debugging on the device
# 2. Run once via ADB (no other accounts needed):
adb shell dpm set-device-owner com.laisadevstudio.batteryguard/.DeviceAdminReceiver
```

Once set as Device Owner:
- `startLockTask()` is **silent** — no confirmation dialog
- The user **cannot** manually unpin
- Back + Recents combination is completely disabled
- Only auto-releases when battery reaches threshold

> To remove Device Owner later: `adb shell dpm remove-active-admin com.laisadevstudio.batteryguard/.DeviceAdminReceiver`

## Triple-layer lock enforcement

Even without Device Owner, BatteryGuard uses three layers:
1. **`startLockTask()`** — pins the screen
2. **`OverlayWindowService`** — `TYPE_APPLICATION_OVERLAY` WindowManager view drawn over recents, notifications, Settings
3. **`onWindowFocusChanged`** — OverlayActivity relaunches itself within 200ms if it ever loses focus

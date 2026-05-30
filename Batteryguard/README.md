# BatteryGuard 🔋🔒

**by laisadevstudio**

BatteryGuard is a native Android battery discipline and device safety app. It can lock the phone when the battery drops too low, during sleep/focus windows, or after the user's daily phone-time budget is exhausted.

## What it does now

### BatteryGuard engine
- Monitors battery in a persistent foreground service
- User-configurable battery lock threshold
- Low-battery warning before lock
- Once the device is battery-locked, unlock requires **20%+** or the configured threshold if that threshold is higher
- Fullscreen lock overlay with kiosk enforcement

### KeepSafe engine
- Allowed-use windows (window 1 + window 2)
- Bedtime / focus lock window
- Daily phone-time budget
- Automatic relock when a rule becomes active again

### Emergency bypass
- 2-minute emergency bypass
- Protected by biometric auth on the lock overlay, with device-credential fallback where supported
- After 2 minutes, BatteryGuard re-checks all rules and locks again if any rule is still active

### Diagnostics + testing
- Liquid-glass dashboard UI
- Diagnostics section for permissions and lock readiness
- Lock preview mode for safe visual testing
- Lock history and last lock reason tracking

## Lock reasons
BatteryGuard can stack multiple lock reasons at once:
- Low Battery
- Outside Allowed Time
- Sleep / Focus Lock
- Daily Limit Reached

The device unlocks only when all active lock reasons are cleared, unless the temporary emergency bypass is used.

## Permissions used
- Device Admin — stronger lock control
- Display Over Apps — fullscreen overlay lock
- Accessibility — helps block quick settings / notification shade in lock mode
- Notifications — low-battery warnings and persistent service status
- Biometric — emergency bypass authentication

## Notes
- Torch control is implemented on the lock overlay.
- Mobile data and airplane mode are shown in the overlay UI, but direct control is restricted on modern Android unless the device has system/OEM-level privileges.
- Device Owner mode still gives the strongest kiosk behavior.

## Build
GitHub Actions builds debug and release APKs on push.

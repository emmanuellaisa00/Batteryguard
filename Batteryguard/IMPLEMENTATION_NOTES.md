# BatteryGuard implementation notes

## Implemented in this pass
- Liquid-glass inspired UI refresh for the dashboard and lock overlay
- Battery unlock floor logic: unlock at `max(20%, configured threshold)`
- KeepSafe schedule engine:
  - allowed window 1
  - allowed window 2
  - bedtime / focus window
  - daily usage limit
- 2-minute emergency bypass
- Biometric auth on the lock overlay for emergency bypass
- Diagnostics dashboard
- Preview / test overlay mode
- Lock history (count, last lock, last unlock, last reasons)
- Torch quick action on the lock overlay
- Mobile data / airplane status tiles with Android restriction messaging

## Important platform constraints
- Direct airplane-mode toggling is blocked on most modern Android devices for normal apps.
- Direct mobile-data toggling is also restricted on most modern Android devices.
- The overlay still shows both controls, but they act as status / restricted-action tiles unless special OEM or system privileges exist.

## Main rule model
- Battery, schedule, bedtime, and daily-limit locks can stack.
- The device only unlocks when all active lock reasons clear.
- Emergency bypass temporarily suppresses lock for 2 minutes, then rules are checked again.

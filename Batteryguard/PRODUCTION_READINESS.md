# Production readiness notes

This codebase has been hardened for the current feature scope:

## Hardened behaviors
- Battery lock now latches until the unlock floor is reached (`max(20%, threshold)`).
- Emergency bypass is temporary and auto-expires; the service schedules a refresh when it ends.
- Boot/package-replaced restart support is wired through `BootReceiver`.
- Overlay notification text reflects active lock reasons.
- Torch is turned off when the overlay is destroyed.
- Battery level retrieval has a sticky-broadcast fallback.
- Emergency auth supports biometric and device credential fallback where platform support exists.

## Remaining real-device verification to do in Android Studio
Because this sandbox cannot complete a full Gradle dependency build, the following still must be verified on a real development machine:
- app sync/build succeeds
- biometric/device credential prompt works on target devices
- torch behavior works on actual hardware
- lock overlay still behaves correctly under different OEM Android skins
- data / airplane tiles remain status-only unless special OEM/system privileges are available

## Release recommendation
Treat the repo as production-hardened code that still needs final Android Studio build verification and device QA before publishing.

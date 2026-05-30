# BatteryGuard repo review

Repo cloned from: `https://github.com/emmanuellaisa00/Batteryguard`
Local path: `/home/user/Batteryguard`

## What the app currently does well

### Core idea
BatteryGuard is a native Android kiosk-style battery protection app that locks the device when the battery drops below a user-defined threshold, then unlocks once the battery has recovered.

### Implemented features
- Foreground battery monitoring service (`BatteryGuardService`)
- Configurable battery lock threshold (5%–80%)
- Low-battery warning notification before the lock threshold
- Fullscreen lock overlay UI built with Jetpack Compose
- Lock Task / screen pinning support
- Device Admin support for `lockNow()` and stronger kiosk behavior
- Overlay window service for extra lock enforcement over other apps
- Accessibility service to collapse Quick Settings / notification shade while guard is active
- Boot receiver to restart monitoring after reboot
- Current battery display and charging state on setup screen
- Animated battery / progress UI on the lock screen
- Foreground notifications for both monitoring and warning states
- Attempts to reclaim focus if another window steals focus
- Puts screen to sleep after a delay during lock mode
- HOME launcher intent filter, so it can behave like a launcher in kiosk-style setups

## Strong points
- The main concept is clear and differentiated.
- The UI is much better than a typical utility app; the lock screen has personality.
- The app already combines multiple enforcement layers instead of relying on only one API.
- Setup screen explains the important permissions reasonably well.
- The codebase is still small enough to improve quickly.

## Biggest gaps / missing features

### Product / user-facing gaps
1. **No explicit onboarding flow**
   - Permissions are shown, but the app would benefit from a guided step-by-step setup wizard.

2. **No “armed / disarmed” switch**
   - The service starts immediately when the app opens.
   - Users may want a clear toggle to enable/disable protection.

3. **No emergency bypass / owner override**
   - This may be intentional, but from a usability standpoint a trusted PIN, biometric owner override, or timed emergency bypass could prevent lockout disasters.

4. **No hysteresis / recovery buffer**
   - The app unlocks as soon as battery reaches the threshold.
   - A better UX is often: lock below X%, unlock at X+3% or X+5%.

5. **No test mode / simulation mode**
   - A “Preview lock screen” or “Simulate low battery” button would help users verify setup safely.

6. **No diagnostics screen**
   - A page showing service running status, admin status, overlay status, accessibility status, default launcher status, and device-owner status would help a lot.

7. **No OEM battery-optimization guidance**
   - Many Android phones kill background apps aggressively.
   - Users likely need guidance for Autostart / battery optimization exemptions.

8. **No user education around HOME launcher behavior**
   - Since the app registers for the HOME category, users may need clear explanation and a way to revert to their normal launcher.

9. **No localization / multi-language support**
   - All strings are effectively English-only.

10. **No settings export / backup**
   - Useful if this is used on multiple devices.

### Technical / engineering gaps
1. **No tests**
   - No unit tests or instrumentation tests are present.

2. **No DataStore / reactive settings layer**
   - Threshold is stored in `SharedPreferences`; DataStore would be cleaner.

3. **State shared through service companion object**
   - `isGuardActive`, `isCharging`, and battery level are shared as static mutable values.
   - This works, but a more robust architecture would use a repository + Flow/StateFlow.

4. **No logging / debug export feature for support**
   - Troubleshooting kiosk apps is hard without logs.

5. **No landscape / tablet optimization**
   - Activities are forced to portrait.

6. **No explicit Play Store compliance flow for Accessibility**
   - If distributed on Google Play, the app will likely need very clear disclosure and consent UX.

## Likely UX improvements

### High-impact UX wins
- Add a **first-run setup wizard**:
  1. Welcome / what the app does
  2. Set threshold
  3. Grant Device Admin
  4. Grant Overlay
  5. Grant Accessibility (optional but recommended)
  6. Test lock screen
  7. Finish

- Add an **Enable Protection** switch with clear states:
  - Protected
  - Partially protected
  - Not ready

- Add a **Test Lock** button.
- Add a **Why am I locked?** section on the overlay.
- Add **time-to-unlock estimate** while charging.
- Add **sound/vibration options** for the pre-lock warning.
- Add **custom warning margin** instead of fixed 10%.
- Add **unlock buffer** (example: lock at 20%, unlock at 25%).
- Add **clear launcher revert instructions**.
- Add **permission explanation dialogs before opening system settings**.

### Nice-to-have UX improvements
- Battery history chart
- Dark/light/system theme support
- Better accessibility text for screen readers
- Optional emergency call access
- Optional allowlist for certain apps while locked (enterprise mode)
- Optional schedule-based behavior (e.g. only active at night or during work hours)

## Important code-level observations

### 1. Warning card condition in overlay appears unreachable
In `OverlayActivity`, the warning card is shown only when:
- `!charging`
- `battery in threshold..(threshold + WARN_MARGIN)`

But the overlay itself only appears when the guard is active, and the guard becomes active when battery is **below** threshold.

So that warning card logic likely never appears in practice and should probably be revised.

### 2. README wording may overstate launcher behavior
The README says the app “Automatically registers as HOME launcher.”
Technically the manifest declares the HOME category, but that does not necessarily mean the app becomes the default launcher automatically on a user’s device.

### 3. Service starts immediately on app launch
This may surprise users. It would feel better if the app only starts monitoring after setup is completed or when protection is explicitly enabled.

### 4. Kiosk strength depends heavily on setup quality
Without Device Owner mode, the app is more breakable.
That should be made much clearer in the UI, not just in README.

## Suggested roadmap

### Version 1.1
- Add onboarding wizard
- Add enable/disable protection toggle
- Add test mode
- Add diagnostics screen
- Add unlock buffer / hysteresis
- Fix unreachable warning card logic

### Version 1.2
- Add DataStore + state refactor
- Add logs / diagnostics export
- Add localization support
- Add OEM battery optimization help
- Add improved permission disclosures

### Version 1.3
- Add optional owner override
- Add battery history / charging insights
- Add enterprise mode / allowlist options
- Add tablet and landscape support

## Build note
I attempted a local Gradle build in the sandbox, but dependency downloads failed due a TLS/network issue in the environment, so I could not use that result to judge the app itself.
The repo does include a GitHub Actions workflow for debug and release APK builds.

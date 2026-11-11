# Validation and phone checklist

Final automated results are recorded below. No physical Android device or full emulator was available; JVM tests do not prove hardware/display/vendor behavior.

## Phone acceptance checks

1. Install the APK on Android 15, open it, set the 10-second test and enable floating mode. Grant overlay permission and return. Check the notification can open settings, hide/show and stop.
2. Confirm the timeline is absent above 3 seconds. In the last 3 seconds it reveals all five labels: 2.5, 2.0, 1.5, 1.0, 0.5. Markers activate sequentially; zero hides the timeline and never goes negative.
3. Set upcoming midnight, next minute, and an exact date with seconds/milliseconds. Compare against the phone's local clock. Test one/two/three fractional digits. One-time completion should remain at zero after reopening.
4. Set a daily time close to now. It must hold zero briefly and show the next day. Change timezone while running and while stopped; the target should retain its local hour. An absolute date target must retain its instant.
5. Set size to 180 dp, then 700 dp. Drag to corners, pinch to resize, rotate portrait/landscape. The full expanded card should remain within available bounds. Reopen the app and confirm size/position persist.
6. Change opacity; lock movement; enable touch-through. Verify taps reach the app beneath. Recover through the notification or main application and turn touch-through off. Check effective opacity may be limited by Android.
7. Enable network speed and transfer a file over Wi-Fi/mobile data. Values should change with real traffic, return to zero when counters are stationary, and show dashes when unavailable. VPN/vendor accounting may differ.
8. Enable Hz and measured draw interval. Change render caps and power saver. Draw intervals should change with actual draws, while Hz follows the active display mode. Disable optional details for the cleanest widget.
9. Turn the screen off for 20 seconds, then wake it. The countdown should immediately reflect device time. Try backgrounding, reopening, changing device time, notification denial and overlay permission revocation. Verify no crash and restart via the app if Android terminates it.
10. Confirm secure apps/system permission screens may hide the overlay. This is an Android restriction, not something the app bypasses.

## What cannot be verified here

- Installation and launch on a physical phone, real hardware refresh/traffic/frame performance, touch pass-through routing to another real app, vendor battery restrictions, physical cutouts, notification drawer presentation, vibration, and actual presented-frame timing.
- Hardware validation: use the device checklist above. Current cloud build status and downloadable reports are available in the repository’s Actions tab.

## Final automated results — 11 November 2026

| Check | Result |
| --- | --- |
| Gradle `assembleDebug lintDebug testDebugUnitTest` | BUILD SUCCESSFUL, exit 0 |
| Pure Kotlin timing tests | 14 passed, 0 failures |
| Robolectric Android API35 tests | 12 passed, 0 failures |
| Android lint | 0 errors, 21 warnings |
| APK signature | Verified APK Signature Scheme v2, one Android Debug signer |
| APK alignment | Verified by zipalign |
| Manifest | package com.elith.precision, min SDK26, target SDK35, version1.0.0 |
| Runtime permissions | Overlay, foreground service/specialUse, notifications, optional vibration; no INTERNET permission |
| Visual QA | Actual native Canvas widget and phone-size dashboard renders inspected; no digit clipping in final renders |
| GitHub workflow | Live build status and reports: [Build Android APK](https://github.com/ELith03/precision-floating-clock-android/actions/workflows/android.yml) |

Coverage includes midnight/date transitions, millisecond targets, strictly future selection, timezone changes including while stopped, DST gap/overlap, wall-clock steps and resume, no early zero, completion latching, daily recurrence, all five exact checkpoint thresholds, adaptive height, persisted size/opacity/target settings, overlay permission denial, foreground notification and hide/show lifecycle, touch-through opacity policy, stale completion cancellation, landscape bounds, Activity recreation, date-editor mode preservation and traffic-rate formatting/unavailability.

Robolectric virtual vsync is paused explicitly so a continuously scheduling frame callback does not make the test framework drain an infinite stream of immediate virtual frames. Rendering snapshots are drawn through the real Android Canvas software implementation with Robolectric native graphics. Activity recreation is modeled by saving state, destroying and recreating the controller because headless focus management is not a physical window manager. These are test-environment controls, not production behavior changes.

Lint's remaining warnings concern untranslated/dynamic English strings, legacy inset lookup only on Android26–29, allocations in drawing, click accessibility/RTL guidance, backup metadata, and two stopService SAM-analysis false positives. They do not block this debug build; the warnings are included in the report.

Original locally validated APK SHA-256 (cloud builds differ because of debug signing): `bde85bdffdcbe014cd30a2de2593e9b8d5daa920f70148b34fb2f791a3b8ac48`

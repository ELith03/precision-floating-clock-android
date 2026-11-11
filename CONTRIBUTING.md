# Contributing

Thanks for helping improve Precision Clock.

## Report a problem

Open an issue with your phone model, Android version, app version, reproduction steps, and expected/actual behavior. For overlay problems, mention whether lock, touch-through, compact mode, or power saver was enabled. Redact personal information from screenshots and logs.

## Develop

1. Fork and clone the repository, then create a branch for your change.
2. Install JDK17+ and Android SDK platform35/build-tools35.0.0.
3. Run `bash ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`.
4. For timing, recurrence, or overlay changes, add a focused behavioral test and use the physical-device checklist in `docs/VALIDATION.md` where applicable.
5. Open a pull request explaining the behavior change and what you verified. State physical-device limitations honestly.

Keep the app offline and lightweight. Display measured statistics only, preserve the final-three-second timeline behavior, and avoid adding unnecessary permissions or dependencies. Do not commit local SDK paths, passwords, API tokens, signing keys, or generated build output.

## Release maintenance

Main-branch builds attach the versioned APK and checksum to GitHub Releases after tests and lint pass. Existing downloads are kept unchanged.

Before publishing another version, update the app's version, artifact filename, release tag and README download together. Debug keys are generated per build environment and do not provide a stable update identity. For a long-term release channel, configure a consistent signing key using repository secrets; never store a private key in source control. Do not silently replace an existing version with an APK signed by a different key.

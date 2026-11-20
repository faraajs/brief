# Contributing

Bug reports, reproducible audio edge cases, and focused pull requests are welcome.

## Before opening a pull request

1. Keep audio processing local. Do not add network permissions, telemetry, or external upload endpoints.
2. Run the host signal tests (`./tools/test.sh`) and a release build (`./gradlew :app:assembleRelease`) before pushing.
3. Use synthetic or public-domain test clips in issues and tests. Never commit private recordings, keystores, passwords, or local paths.
4. Keep UI strings synchronized across `app/src/main/res/values/strings.xml` (English) and `app/src/main/res/values-in/strings.xml` (Indonesian).

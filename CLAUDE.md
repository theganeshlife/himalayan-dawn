# Himalayan Dawn: notes for Claude

Android TV launcher (Kotlin, plain Android Views, no AndroidX or third-party runtime libraries) with a Nepali calendar. One module: `tv/`.

## Build and test

```bash
chmod +x gradlew
./gradlew :tv:testDebugUnitTest    # calendar tests; must pass before any fix is pushed
./gradlew :tv:assembleDebug        # must compile
```

## Rules that must never be broken

- **Privacy:** the app must not request `android.permission.INTERNET` or add analytics, ads, accounts, crash reporting or third-party runtime libraries. Settings stay in the app's private storage, and `android:allowBackup` stays `false`.
- **No personal data** in the repo: no real IP addresses, MAC addresses, Wi-Fi names, people's names or device identifiers. Use `192.168.1.50` in examples.
- **Never commit signing material:** `signing/`, `*.jks`, `keystore.properties`, `local.properties`.
- **Calendar accuracy:** `tv/src/main/java/app/himalayandawn/tv/calendar/` drives dates shown to families for religious observances.
  - Keep `CalendarTest` passing; it checks the 2083 Dashain, Tihar and Chhath dates against Hamro Patro.
  - When changing festival rules, add a test with dates from a published Nepali patro, and say in the pull request where the dates came from.
- **Photos:** only use freely licensed images, and add each one's credit to `scenes.json` and `THIRD_PARTY_NOTICES.md`.
- **Names and spellings:** use Fewa Lake and Rara Lake (not Phewa Tal or Rara Tal) in captions and documentation. Wikimedia source URLs keep their original filenames.

## Style

- Match the surrounding code: Kotlin, short KDoc comments where behaviour isn't obvious, UI built for D-pad/remote navigation (focusable views, visible focus state, nothing that needs touch).
- The TV may have system animations switched off: animate with the view's own clock (see `SceneView`), not `ViewPropertyAnimator`.
- Keep the README's install steps simple and accurate when behaviour changes.

## When fixing an issue

1. Reproduce the problem from the report, or explain why it can't be reproduced.
2. Make the smallest fix that solves it, with a test for any calendar change.
3. Run the build and tests above.
4. Reply on the issue with what was wrong, what changed, and anything the maintainer should check on a real TV before releasing.

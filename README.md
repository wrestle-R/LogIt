# LogIt

A little progress, every day. Log activities more than once a day, see the history, and log straight from your Android home screen.

- `next/`: Next.js website with shadcn controls. Original React client is preserved in `next/legacy/`.
- `kotlin/`: native Android app and three resizable widgets: quick log, heatmap, and heatmap with logging.

```sh
cd next
npm ci
npm run dev
```

For Android, put the Firebase Android client config at `kotlin/app/google-services.json`, then:

```sh
cd kotlin
./gradlew :app:assembleDebug
```

Requires JDK 17 and Android SDK 37. The app uses the existing LogIt Firebase project and Google accounts. Firebase config, signing files and private local notes stay out of Git.

See [compatibility and verification](docs/implementation.md) for data behaviour and test coverage.

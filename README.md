# Whispr

A privacy-first, end-to-end encrypted Android messenger. No ads, tracking,
feeds, or phone numbers. All protocol cryptography comes from
[libsignal](https://github.com/signalapp/libsignal). Licensed AGPL-3.0, the
same licence as libsignal.

```
android/   Kotlin + Jetpack Compose client
server/    Go backend (REST, later WebSocket), PostgreSQL, S3-compatible storage
docs/      Architecture and threat model
```

## Backend

```sh
docker compose up --build     # Postgres, MinIO, server on 127.0.0.1:8080
curl http://127.0.0.1:8080/healthz
```

The first build compiles libsignal's C library from source, which takes a few
minutes. Later builds are cached.

Server tests:

```sh
cd server
go test ./...                                   # unit tests (fake verifier)
WHISPR_TEST_DATABASE_URL=postgres://... go test ./...   # + Postgres store tests
docker build --target test .                    # full suite with real libsignal
```

The server binary only runs when built with `-tags libsignal`. A build
without it refuses to start rather than run without signature verification.

## Android

```sh
cd android
./gradlew assembleDebug testDebugUnitTest ktlintCheck lintDebug
./gradlew :core:designsystem:recordRoborazziDebug   # design-system screenshots
```

Create `android/local.properties` with `sdk.dir=...` if `ANDROID_HOME` is not set.
JVM unit tests run on JDK 21 (libsignal's classes target Java 21); Gradle
downloads it automatically if it isn't installed.

Run the app against the local backend (emulator or USB phone):

```sh
docker compose up -d
adb reverse tcp:8080 tcp:8080     # the debug build talks to http://127.0.0.1:8080
./gradlew installDebug
```

`adb reverse` is needed because Android 17 blocks local-network addresses
(such as `10.0.2.2`) without a runtime permission. See docs/ARCHITECTURE.md.

On-device tests (Keystore, SQLCipher) and the live end-to-end test:

```sh
./gradlew :data:connectedDebugAndroidTest
WHISPR_SERVER_URL=http://127.0.0.1:8080/ ./gradlew :data:testDebugUnitTest
```

### Chatting between two devices

Install the debug app on two emulators or phones, run `adb -s <device> reverse
tcp:8080 tcp:8080` for each, and onboard both. On one, tap **New chat** and paste
the other's account ID (Settings → Account ID, or shown on the New chat screen).

### Push (optional)

Without Firebase configuration everything works while the app is open, and
queued messages arrive when it next opens. To enable content-free wake-ups:

1. Create a Firebase project and add an Android app with package `dev.whispr.android`.
2. Create `android/firebase.properties` (git-ignored) from the values in the
   downloaded `google-services.json`:
   ```properties
   app_id=1:1234567890:android:abcdef
   api_key=AIza...
   project_id=your-project
   sender_id=1234567890
   ```
3. Create a service-account key with the "Firebase Cloud Messaging API Admin"
   role, save it outside the repo, and start the server with
   `FCM_CREDENTIALS_FILE=/path/to/key.json` (e.g. as a mounted secret in compose).

## Docs

- [Architecture](docs/ARCHITECTURE.md): layers, auth protocol, storage, testing
- [Threat model](docs/THREAT_MODEL.md): assets, adversaries, mitigations, known gaps

# Development setup

How to build, test and run Whispr locally. For running a public server, see
`docs/DEPLOYMENT.md`.

## Requirements

| Tool | Version | For |
|---|---|---|
| Docker with Compose | recent | Local backend (Postgres, MinIO, server) |
| Go | as in `server/go.mod` | Server development and tests |
| JDK | 21 | Android build and JVM tests (libsignal's classes target Java 21) |
| Android SDK | API 37 platform, build-tools | Android app |
| Android NDK | 28.2.13676358 (r28c) | Release builds only (strips native debug info) |

Gradle downloads JDK 21 itself if it is missing. Create
`android/local.properties` with `sdk.dir=...` if `ANDROID_HOME` is not set.

## Backend

```sh
docker compose up --build      # Postgres, MinIO, server on 127.0.0.1:8080
curl http://127.0.0.1:8080/healthz
```

The first build compiles libsignal's C library from source, which takes a
few minutes. Credentials in `docker-compose.yml` are for local development
only and every port binds to 127.0.0.1.

### Server tests

```sh
cd server
go test ./...                                     # unit tests (fake signature verifier)

export WHISPR_TEST_DATABASE_URL='postgres://whispr:whispr-dev-only@127.0.0.1:5432/whispr?sslmode=disable'
go test ./...                                     # + Postgres integration and security tests

WHISPR_LOAD=1 WHISPR_LOAD_USERS=200 WHISPR_LOAD_MESSAGES=50 \
  go test ./internal/messaging -run TestGatewayLoad -v   # gateway load test

docker build --target test .                      # whole suite against real libsignal
golangci-lint run ./...                           # v2.14.0, as in CI
```

Integration tests create and drop their own database per test, so the user
needs `CREATEDB` (the compose user has it) and packages run in parallel.

Media tests against real MinIO:

```sh
WHISPR_TEST_S3_ENDPOINT=127.0.0.1:9000 WHISPR_TEST_S3_ACCESS_KEY=whispr \
  WHISPR_TEST_S3_SECRET_KEY=whispr-dev-only WHISPR_TEST_S3_BUCKET=whispr-media \
  go test ./internal/attachments/
```

## Android

```sh
cd android
./gradlew assembleDebug testDebugUnitTest
./gradlew ktlintCheck :app:lintDebug :app:checkDesignTokens   # what CI checks
./gradlew :core:designsystem:recordRoborazziDebug             # design-system screenshots (slow)
```

Screens take colours, sizes and type from the design system
(`:core:designsystem`); `checkDesignTokens` fails the build on literals in
app sources.

### Run against the local backend

```sh
docker compose up -d
adb reverse tcp:8080 tcp:8080     # the debug build talks to http://127.0.0.1:8080
./gradlew installDebug
```

`adb reverse` is used because Android 17 blocks local-network addresses
(such as `10.0.2.2`) without a runtime permission. Override the URL with
`-Pwhispr.serverUrl=...`; debug builds allow plain HTTP to loopback only.

Screen security is on by default, so screenshots (including
`adb exec-out screencap`) come out black. Turn it off in Settings → Screen
security while testing.

### Two devices

Install the debug app on two emulators or phones, run
`adb -s <device> reverse tcp:8080 tcp:8080` for each, and onboard both. On one
open **My code**; on the other tap **New chat → Scan QR code** (or **Scan from
image**). Accept the request on the first phone and chat. To verify, open the
chat, tap the verify icon and compare numbers or scan each other's code.

### On-device and live tests

```sh
./gradlew :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest
WHISPR_SERVER_URL=http://127.0.0.1:8080/ ./gradlew :data:testDebugUnitTest   # live server tests
```

### Release build

Release builds need a server URL, certificate pins and a signing key, and
refuse to build without them:

```sh
export WHISPR_KEYSTORE_FILE=/path/to/release.jks WHISPR_KEYSTORE_PASSWORD=... \
       WHISPR_KEY_ALIAS=... WHISPR_KEY_PASSWORD=...
./gradlew :app:assembleRelease :app:bundleRelease \
  -Pwhispr.releaseServerUrl=https://api.example.org/ \
  -Pwhispr.certPins=sha256/<pin1>,sha256/<pin2>
```

Instead of environment variables you can put `storeFile`, `storePassword`,
`keyAlias` and `keyPassword` in `android/keystore.properties` (git-ignored).
Create a key with:

```sh
keytool -genkeypair -keystore release.jks -alias whispr -keyalg EC \
  -groupname secp256r1 -validity 10000 -storetype PKCS12
```

Keep the keystore and its passwords outside the repository and back them up:
a lost key means users cannot install updates over the existing app.

Tagging `vX.Y.Z` (matching `versionName`) runs `.github/workflows/release.yml`,
which builds, verifies and publishes the signed APK, AAB and `SHA256SUMS`.

### Push (optional)

Without Firebase everything works while the app is open, and queued messages
arrive when it next opens. To enable content-free wake-ups:

1. Create a Firebase project with an Android app `dev.whispr.android`.
2. Create `android/firebase.properties` (git-ignored) from
   `google-services.json`:
   ```properties
   app_id=1:1234567890:android:abcdef
   api_key=AIza...
   project_id=your-project
   sender_id=1234567890
   ```
3. Create a service-account key with the "Firebase Cloud Messaging API
   Admin" role and start the server with `FCM_CREDENTIALS_FILE=/path/key.json`.

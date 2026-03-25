# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore.properties or env vars)
./gradlew assembleRelease

# Install debug APK on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.uber.autoaccept.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean

# Create a release (triggers CI/CD pipeline)
git tag v1.1.0 && git push origin v1.1.0
```

## Architecture

The app uses a **Vortex State Machine** pattern. All ride offer processing flows through a deterministic state machine with sealed-class states and event-driven transitions.

### State Flow

```
Idle → Online → OfferDetected → OfferAnalyzing
                                       ├→ ReadyToAccept → Accepting → Accepted → Online (3s delay)
                                       └→ Rejected → Online (1s delay)
                                 Error → Online (2s delay)
```

### Layer Responsibilities

- **`model/`** — `AppState` (sealed class for all states), `StateEvent` (all transition events), `Models.kt` (`FilterSettings`, `UberOffer`, `FilterResult`, `AppConfig` data classes).
- **`engine/StateMachine.kt`** — Manages state transitions via `handleEvent()`. Exposes `StateFlow<AppState>`. Keeps transition history (max 100).
- **`engine/FilterEngine.kt`** — Evaluates `UberOffer` against `FilterSettings`. Returns `FilterResult.Accepted` or `FilterResult.Rejected`. Filtering is controlled by 4 numbered conditions stored in `FilterSettings.enabledConditions: Set<Int>`:
  - Condition 1: `pickupKeywords` match AND dropoff is airport or 광역시
  - Condition 2: pickup is airport keyword (any destination)
  - Condition 3: pickup is 광역시 AND dropoff is 특별시
  - Condition 4: dropoff is airport keyword (any origin)
- **`state/StateHandler.kt`** — `IStateHandler` interface and `BaseStateHandler`. Each handler in `StateHandlers.kt` handles one state. Signature: `handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent?`
- **`service/UberAccessibilityService.kt`** — Main entry point. Listens for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` from `com.ubercab.driver`. Orchestrates the state machine.
- **`service/FloatingWidgetService.kt`** — Foreground service rendering two `TYPE_APPLICATION_OVERLAY` windows: a draggable status widget and a ⊕ crosshair target. The crosshair's screen coordinates are passed to `AcceptingHandler.targetClickPoint` before each accept attempt. Call `disableTargetTouch()` before gesturing and `enableTargetTouch()` after.
- **`service/ServiceState.kt`** — Singleton bridge between `UberAccessibilityService` and `FloatingWidgetService` via `StateFlow`.
- **`utils/UberOfferParser.kt`** — Dual parsing: primary ViewId-based (HIGH confidence), text-search fallback (MEDIUM confidence) using Korean address terms.
- **`utils/AccessibilityHelper.kt`** — Utility object for `AccessibilityNodeInfo` traversal.
- **`utils/ShizukuHelper.kt`** — Wraps Shizuku's `newProcess` to run `input tap x y` (5 taps × 30ms gap). Used to bypass `FLAG_IS_GENERATED_BY_ACCESSIBILITY`.
- **`utils/OfferCardDetector.kt`** — OpenCV-based visual detection of offer cards and accept buttons. Fallback when ViewId parsing fails.
- **`utils/ScreenshotManager.kt`** — Captures screenshots via `AccessibilityService.takeScreenshot()` (API 30+ only). Used by OfferCardDetector.
- **`auth/AuthManager.kt`** — Singleton. Calls `check_license` Supabase RPC with phone number (preferred) or ANDROID_ID. Caches result for 24 hours. Prefs key: `twinme_auth`.
- **`auth/LicenseManager.kt`** — Thin wrapper over `AuthManager` for UI-layer license checks.
- **`logging/RemoteLogger.kt`** — Singleton. Batches log entries (max 200) and flushes to Supabase `uber_logs` table every 10s. Sends heartbeat to `uber_users` table every 60s (device state UPSERT keyed on `device_id`).
- **`supabase/`** — `SupabaseClient` (plain HTTP REST/RPC calls) and `SupabaseConfig` (URL + anon key).
- **`update/`** — `UpdateChecker` (Supabase RPC `get_download_url` with 24h cache), `UpdateDialog` (AlertDialog with download links), `UpdateModels` (data classes).
- **`ui/`** — `MainActivity` (service status, auth gate, auto update check), `SettingsActivity` (filter conditions, distance range, version info, manual update check).

### Accept Click Strategy (two-tier)

1. **Shizuku** (primary): `ShizukuHelper.tap(x, y)` — runs `input tap` as shell via Shizuku. Bypasses the accessibility gesture flag restriction. Fires 5 rapid taps.
2. **`dispatchGesture`** (fallback): used when Shizuku is unavailable/unauthorized.

Both strategies use the `⊕` crosshair coordinate set by the user in `FloatingWidgetService`.

### Key ViewIds (from Uber driver APK)

| Element | ViewId |
|---------|--------|
| Pickup | `uda_details_pickup_address_text_view` |
| Dropoff | `uda_details_dropoff_address_text_view` |
| Distance | `uda_details_distance_text_view` |
| Duration | `uda_details_duration_text_view` |
| Accept button | `upfront_offer_configurable_details_accept_button` |
| Map label (customer distance) | `ub__upfront_offer_map_label` |

These are used for parsing only; the actual click target is the user-placed ⊕ coordinate, not these nodes.

### Update System

The app checks for updates via Supabase RPC `get_download_url`. Only devices registered in `uber_users` table receive signed download URLs (1-hour expiry). Flow:

1. **Auto check**: `MainActivity` calls `UpdateChecker.check()` after successful auth (24h cache).
2. **Manual check**: Settings → "업데이트 확인" button (`forceCheck = true`).
3. **Server-side**: `get_download_url(device_id, current_version)` → checks `uber_users` → queries `apk_releases` → returns signed Supabase Storage URLs.
4. **Download**: `ACTION_VIEW` intent opens browser to signed URL.

### CI/CD Pipeline

Two GitHub Actions workflows:

- **`build.yml`**: Triggers on push to master. Builds debug APK and uploads as artifact (30-day retention).
- **`release.yml`**: Triggers on `v*` tag push. Builds signed release APK, downloads latest Shizuku APK from rikkaapps/Shizuku, uploads both to Supabase Storage `apks` bucket, inserts record in `apk_releases` table, creates GitHub Release with changelog.

**Required GitHub Secrets**: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`.

### Supabase Backend

| Table | Purpose |
|-------|---------|
| `uber_users` | Device registration, heartbeat state (UPSERT on `device_id`) |
| `uber_logs` | Unified log table (parse, action, viewid_health, lifecycle, debug) |
| `apk_releases` | Version metadata, APK paths in Storage, changelog |

Key RPC functions: `check_license` (auth), `get_download_url` (update check with signed URLs).

## Tech Stack

- **Language:** Kotlin 1.9.0
- **Android:** minSdk 26, targetSdk 34, compileSdk 34
- **Build:** Gradle with Android Gradle Plugin 8.1.0
- **Async:** Kotlin Coroutines (Main/IO dispatchers) + StateFlow
- **View binding:** Enabled
- **Java compatibility:** 1.8
- **External:** Shizuku 12.2.0 (shell tap), Supabase (auth RPC + remote logging + storage), OpenCV 4.9.0 (visual detection), Gson 2.10.1

## Conventions

- State transitions are event-driven: create a `StateEvent`, pass it to `StateMachine.handleEvent()`. Never set state directly.
- State handlers implement `canHandle(state)` and `handle(state, rootNode)`. `rootNode` may be null; always null-check.
- Filter conditions are indexed integers (1–4) in `FilterSettings.enabledConditions`. Adding a new condition = new `val conditionN` in `FilterEngine.isEligible()` + new index in the set.
- Filter keywords (airport names, Seoul district names) are hardcoded in Korean in `FilterSettings` defaults.
- Humanization delay: base `AppConfig.autoAcceptDelay` (200ms) + optional random 0–200ms when `humanizationEnabled = true`.
- All accessibility node traversal goes through `AccessibilityHelper` object methods.
- SharedPreferences keys: `uber_auto_accept` (filter/service settings), `twinme_auth` (auth cache).
- `RemoteLogger` must be initialized before use (`RemoteLogger.initialize(context, deviceId, enabled)`); call `shutdown()` on service disconnect.
- Supabase HTTP calls follow `SupabaseClient` patterns: `HttpURLConnection` + Gson + `Dispatchers.IO`. New RPC calls should use `rpcPost()`.
- Release signing: `keystore.properties` (local) or env vars `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` (CI).
- Version management: single source of truth in `app/build.gradle` (`versionCode`, `versionName`). Exposed via `BuildConfig.VERSION_NAME`.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
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
- **`utils/UberOfferParser.kt`** — Dual parsing: primary ViewId-based (HIGH confidence), regex fallback (MEDIUM confidence).
- **`utils/AccessibilityHelper.kt`** — Utility object for `AccessibilityNodeInfo` traversal.
- **`utils/ShizukuHelper.kt`** — Wraps Shizuku's `newProcess` to run `input tap x y` (5 taps × 30ms gap). Used to bypass `FLAG_IS_GENERATED_BY_ACCESSIBILITY`.
- **`auth/AuthManager.kt`** — Singleton. Calls `check_license` Supabase RPC with phone number (preferred) or ANDROID_ID. Caches result for 24 hours. Prefs key: `twinme_auth`.
- **`auth/LicenseManager.kt`** — Thin wrapper over `AuthManager` for UI-layer license checks.
- **`logging/RemoteLogger.kt`** — Singleton. Batches log entries (max 200) and flushes to Supabase `uber_logs` table every 10s. Sends heartbeat to `uber_users` table every 60s (device state UPSERT keyed on `device_id`).
- **`supabase/`** — `SupabaseClient` (plain HTTP REST/RPC calls) and `SupabaseConfig` (URL + anon key).
- **`ui/`** — `MainActivity` (service status, auth gate), `SettingsActivity` (filter mode + per-condition ON/OFF toggles + distance range).

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

## Tech Stack

- **Language:** Kotlin 1.9.0
- **Android:** minSdk 26, targetSdk 34, compileSdk 34
- **Build:** Gradle with Android Gradle Plugin 8.1.0
- **Async:** Kotlin Coroutines (Main/IO dispatchers) + StateFlow
- **View binding:** Enabled
- **Java compatibility:** 1.8
- **External:** Shizuku (shell tap), Supabase (auth RPC + remote logging)

## Conventions

- State transitions are event-driven: create a `StateEvent`, pass it to `StateMachine.handleEvent()`. Never set state directly.
- State handlers implement `canHandle(state)` and `handle(state, rootNode)`. `rootNode` may be null; always null-check.
- Filter conditions are indexed integers (1–4) in `FilterSettings.enabledConditions`. Adding a new condition = new `val conditionN` in `FilterEngine.isEligible()` + new index in the set.
- Filter keywords (airport names, Seoul district names) are hardcoded in Korean in `FilterSettings` defaults.
- Humanization delay: base `AppConfig.autoAcceptDelay` (200ms) + optional random 0–200ms when `humanizationEnabled = true`.
- All accessibility node traversal goes through `AccessibilityHelper` object methods.
- SharedPreferences keys: `uber_auto_accept` (filter/service settings), `twinme_auth` (auth cache).
- `RemoteLogger` must be initialized before use (`RemoteLogger.initialize(context, deviceId, enabled)`); call `shutdown()` on service disconnect.

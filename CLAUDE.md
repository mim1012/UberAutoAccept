# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# JAVA_HOME must point to Android Studio's JBR (bundled JDK)
export JAVA_HOME="D:/Android/jbr"

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.uber.autoaccept.ExampleUnitTest"

# Clean build
./gradlew clean
```

## Architecture

### Vortex State Machine

All ride offer processing flows through a deterministic state machine:

```
Idle → Online → OfferDetected → OfferAnalyzing
                                       ├→ ReadyToAccept → Accepting → Accepted → Online (3s)
                                       └→ Rejected → Online (1s)
                                 Error → Online (2s)
```

- Transitions are **event-driven**: create `StateEvent`, pass to `StateMachine.handleEvent()`. Never set state directly.
- Each state has a dedicated `IStateHandler` with `canHandle(state)` and `handle(state, rootNode)`. `rootNode` may be null.
- `StateMachine` exposes `StateFlow<AppState>` and keeps transition history (max 100).

### Layer Responsibilities

- **`model/`** — `AppState` (sealed class), `StateEvent` (sealed class), `Models.kt` (`FilterSettings`, `UberOffer`, `FilterResult`, `AppConfig`).
- **`engine/StateMachine.kt`** — State transitions via `handleEvent()`.
- **`engine/FilterEngine.kt`** — Evaluates offers against `FilterSettings`. 4 numbered conditions in `enabledConditions: Set<Int>`:
  - 1: `pickupKeywords` AND (airport OR pickupKeywords in dropoff)
  - 2: pickup is airport keyword (any destination)
  - 3: pickup is 광역시 AND dropoff is 특별시
  - 4: dropoff is airport keyword (any origin)
- **`state/StateHandlers.kt`** — One handler per state. Signature: `handle(state, rootNode): StateEvent?`
- **`service/UberAccessibilityService.kt`** — Main entry point. Listens for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` from `com.ubercab.driver`. Orchestrates state machine, config loading, and service recovery.
- **`service/FloatingWidgetService.kt`** — Foreground service with two overlay windows: draggable status widget + ⊕ crosshair target. Crosshair coordinates feed `AcceptingHandler.targetClickPoint`.
- **`service/ServiceState.kt`** — SharedPreferences-backed singleton bridging accessibility and floating widget services. Persists `start()/stop()` to survive process kills. `restoreIfNeeded()` on service reconnect.
- **`utils/UberOfferParser.kt`** — Dual parsing: ViewId-based (HIGH confidence), regex fallback (MEDIUM confidence).
- **`utils/AccessibilityHelper.kt`** — `AccessibilityNodeInfo` traversal utilities.
- **`utils/ShizukuHelper.kt`** — AIDL-based Shizuku UserService binding with auto-reconnect. Manages `IShizukuService` lifecycle.
- **`service/ShizukuUserService.kt`** — Runs in Shizuku process. Implements `IShizukuService.aidl` (`tap`, `tapRepeat` via `input tap` shell command).
- **`logging/RemoteLogger.kt`** — Batches log entries (max 200), flushes to Supabase `uber_logs` every 10s. Heartbeat to `uber_users` every 60s. Recovery events flush immediately.
- **`auth/`** — `AuthManager` calls `check_license` Supabase RPC. Caches 24h. Prefs key: `twinme_auth`.
- **`supabase/`** — `SupabaseClient` (plain HTTP REST/RPC) and `SupabaseConfig` (URL + anon key).
- **`ui/`** — `MainActivity` (service status with 3-state indicator, auth gate), `SettingsActivity` (filter mode, per-condition ON/OFF, distance range).

### Accept Click Strategy (two-tier)

1. **Shizuku AIDL** (primary): `ShizukuHelper.tap(x, y)` → `IShizukuService.tapRepeat()` — 5 rapid taps via shell in Shizuku process. Bypasses accessibility gesture flag.
2. **`dispatchGesture`** (fallback): used when Shizuku is unavailable/unauthorized.

Both use the ⊕ crosshair coordinate placed by the user.

### Service Recovery

Services self-heal because user cannot intervene mid-ride:

| Scenario | Recovery |
|----------|----------|
| Shizuku IPC failure / null service | Immediate `scheduleRebind()` with trigger reason |
| Shizuku binder dead / disconnected | 3s delayed rebind |
| `onInterrupt()` called | Config/FilterEngine reload + Shizuku rebind |
| `ServiceState` becomes inactive during Uber event | Auto-start + config reload |
| Process killed by Android | `ServiceState.restoreIfNeeded()` reads last state from SharedPreferences |

All recovery events log via `RemoteLogger.logRecovery(component, trigger, success)` with immediate flush.

### Remote Logging

| Log Type | Purpose |
|----------|---------|
| `lifecycle` | connected, disconnected, **recovery** events |
| `shizuku` | bind, tap, disconnect, **rebind_attempt** |
| `debug` | accessibility_event, opencv_detection, diagnostic |
| `parse` | Offer parse results |
| `action` | Accept button click results |

Heartbeat includes: `service_active`, `shizuku_bound`, `shizuku_available`, `current_state`, `filter_mode`.

### Key ViewIds (from Uber driver APK)

| Element | ViewId |
|---------|--------|
| Pickup | `uda_details_pickup_address_text_view` |
| Dropoff | `uda_details_dropoff_address_text_view` |
| Distance | `uda_details_distance_text_view` |
| Duration | `uda_details_duration_text_view` |
| Accept button | `upfront_offer_configurable_details_accept_button` |
| Map label | `ub__upfront_offer_map_label` |

These are for parsing only; actual click target is the user-placed ⊕ coordinate.

## Tech Stack

- **Language:** Kotlin 1.9.0
- **Android:** minSdk 26, targetSdk 34, compileSdk 34
- **Build:** Gradle 8.7, AGP 8.1.0, AIDL enabled, BuildConfig enabled
- **Async:** Kotlin Coroutines (Main/IO) + StateFlow
- **External:** Shizuku 13.1.5 (AIDL UserService), Supabase (auth + logging), OpenCV (button detection), Gson

## Conventions

- State transitions: `StateEvent` → `StateMachine.handleEvent()`. Never set state directly.
- Filter conditions: indexed integers (1–4) in `FilterSettings.enabledConditions`. New condition = new `val conditionN` in `FilterEngine.isEligible()` + new index.
- Filter keywords (airport names, district names) are hardcoded in Korean in `FilterSettings` defaults.
- Humanization delay: base 200ms + optional random 0–200ms when `humanizationEnabled = true`.
- All `AccessibilityNodeInfo` operations go through `AccessibilityHelper`.
- SharedPreferences keys: `uber_auto_accept` (filter/service), `twinme_auth` (auth cache).
- `RemoteLogger.initialize()` before use; `shutdown()` on service destroy.
- `ServiceState.init(context)` must be called in both `UberAccessibilityService` and `FloatingWidgetService`.
- Shizuku rebind calls must include a trigger string for log traceability.

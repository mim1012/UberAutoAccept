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

- **`model/`** — Sealed class `AppState` defines all states; `Models.kt` holds `FilterSettings`, `UberOffer`, `FilterResult`, `AppConfig` data classes. `StateEvent` sealed class defines all transition events.
- **`engine/StateMachine.kt`** — Manages state transitions via `handleEvent()`. Exposes current state as `StateFlow<AppState>`. Maintains transition history (max 100).
- **`engine/FilterEngine.kt`** — Evaluates `UberOffer` against `FilterSettings`. Returns `FilterResult.Accepted` or `FilterResult.Rejected` with reasons. Supports four modes: `AIRPORT`, `SEOUL_ENTRY`, `BOTH`, `DISABLED`.
- **`state/StateHandler.kt`** — `IStateHandler` interface and `BaseStateHandler` abstract class. Each concrete handler in `StateHandlers.kt` implements logic for one state (e.g., `OfferDetectedHandler` triggers parsing, `AcceptingHandler` clicks the button).
- **`service/UberAccessibilityService.kt`** — Main entry point. Listens for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` events from `com.ubercab.driver`. Orchestrates the state machine, loads config from SharedPreferences.
- **`utils/UberOfferParser.kt`** — Dual parsing strategy: primary ViewId-based parsing (HIGH confidence) with regex fallback (MEDIUM confidence).
- **`utils/AccessibilityHelper.kt`** — Utility object for AccessibilityNodeInfo traversal (find by ViewId, find by text, extract all text, get bounds).
- **`ui/`** — `MainActivity` shows service status; `SettingsActivity` configures filter mode and distance range. Settings persisted to SharedPreferences key `uber_auto_accept`.

### Key ViewIds (from Uber driver APK)

| Element | ViewId |
|---------|--------|
| Pickup | `uda_details_pickup_address_text_view` |
| Dropoff | `uda_details_dropoff_address_text_view` |
| Distance | `uda_details_distance_text_view` |
| Duration | `uda_details_duration_text_view` |
| Accept button | `upfront_offer_configurable_details_accept_button` |
| Map label (customer distance) | `ub__upfront_offer_map_label` |

These may break when the Uber driver app updates.

## Tech Stack

- **Language:** Kotlin 1.9.0
- **Android:** minSdk 26, targetSdk 34, compileSdk 34
- **Build:** Gradle with Android Gradle Plugin 8.1.0
- **Async:** Kotlin Coroutines (Main dispatcher) + StateFlow
- **View binding:** Enabled
- **Java compatibility:** 1.8

## Conventions

- State transitions are event-driven: create a `StateEvent`, pass it to `StateMachine.handleEvent()`. Never set state directly.
- State handlers follow the `IStateHandler` interface with `canHandle(state)` and `handle(state, service)` methods.
- Filter keywords (airport names, Seoul district names) are hardcoded in Korean in `FilterSettings` defaults.
- The humanization delay (random 0-200ms on top of base 200ms) is controlled by `AppConfig.humanizationEnabled`.
- All accessibility node operations go through `AccessibilityHelper` object methods.
- SharedPreferences key is `uber_auto_accept`; filter settings are stored as individual keys (`filter_mode`, `min_customer_distance`, `max_customer_distance`, etc.).

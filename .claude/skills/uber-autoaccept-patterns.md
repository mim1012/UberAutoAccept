---
name: uber-autoaccept-patterns
description: Coding patterns extracted from UberAutoAccept Android project - Vortex State Machine, Accessibility Service, Shizuku IPC, Supabase remote logging
version: 1.0.0
source: local-git-analysis
analyzed_commits: 17
---

# UberAutoAccept Patterns

## Commit Conventions

**Conventional commits** (88% adherence):
- `feat:` — New features (41%, dominant)
- `fix:` — Bug fixes (29%)
- `refactor:` — Code restructuring
- `docs:` — Documentation
- `ci:` — CI/CD changes

Commit messages are bilingual (Korean body, English or Korean prefix). Large feat commits bundle multiple related changes.

## Code Architecture

```
app/src/main/java/com/uber/autoaccept/
├── auth/           # AuthManager, LicenseManager (Supabase auth)
├── engine/         # StateMachine (Vortex), FilterEngine
├── logging/        # RemoteLogger, LogModels (Supabase uber_logs)
├── model/          # AppState (sealed class), Models (data classes)
├── service/        # UberAccessibilityService, FloatingWidgetService, ServiceState
├── state/          # StateHandler interface, concrete StateHandlers
├── supabase/       # SupabaseClient, SupabaseConfig
├── ui/             # MainActivity, SettingsActivity
└── utils/          # UberOfferParser, AccessibilityHelper, ShizukuHelper, ScreenshotManager
```

**23 Kotlin files, ~3700 LOC total.**

## Core Design Pattern: Vortex State Machine

All ride offer processing follows a deterministic state machine:

```
Idle → Online → OfferDetected → OfferAnalyzing
                                       ├→ ReadyToAccept → Accepting → Accepted → Online
                                       └→ Rejected → Online
                                 Error → Online
```

### Rules
- State transitions are **event-driven**: create `StateEvent`, pass to `StateMachine.handleEvent()`. Never set state directly.
- Each state has a dedicated `IStateHandler` implementation with `canHandle()` and `handle()`.
- `StateMachine` exposes `StateFlow<AppState>` for reactive observation.
- State history is maintained (max 100 transitions) for debugging.

## Co-Change Patterns (Files That Change Together)

| Trigger | Always Co-Changes |
|---------|-------------------|
| New filter logic | `FilterEngine.kt` + `Models.kt` + `SettingsActivity.kt` |
| New log type | `LogModels.kt` + `RemoteLogger.kt` |
| Accept strategy change | `StateHandlers.kt` + `UberAccessibilityService.kt` |
| UI feature | `MainActivity.kt` + layout XML + `ServiceState.kt` |
| Shizuku change | `ShizukuHelper.kt` + `UberAccessibilityService.kt` |

**Hottest file**: `UberAccessibilityService.kt` (changed in 10/17 commits = 59%).

## Service Recovery Pattern

Services must self-heal because the user cannot intervene mid-ride:

1. **ServiceState** — SharedPreferences-backed singleton. `start()/stop()` persist. `restoreIfNeeded()` on process restart.
2. **Shizuku IPC** — Auto-rebind on: `onServiceDisconnected`, `OnBinderDead`, tap IPC failure, null service. All via `scheduleRebind(delayMs, trigger)`.
3. **Accessibility onInterrupt** — Reload config/filterEngine, rebind Shizuku. Don't clear ServiceState.
4. **Event auto-recover** — If `ServiceState.isActive() == false` during Uber event, auto-start + reload.

### Recovery Logging
Every recovery event must call `RemoteLogger.logRecovery(component, trigger, success, details)` which:
- Records component/trigger/success + current shizuku_bound/service_active snapshot
- Immediately flushes (device may die again)

## Remote Logging (Supabase)

### Tables
- `uber_logs` — All events (parse, action, lifecycle, debug, shizuku, viewid_health)
- `uber_users` — Device heartbeat (upsert on device_id, every 60s)

### Log Types
| Type | Purpose |
|------|---------|
| `lifecycle` | connected, disconnected, **recovery** events |
| `shizuku` | bind, tap, disconnect, **rebind_attempt** |
| `debug` | accessibility_event, opencv_detection, diagnostic |
| `parse` | Offer parse success/failure |
| `action` | Accept button click results |
| `heartbeat` | Device status (via uber_users upsert) |

### Heartbeat Fields
Includes: `service_active`, `shizuku_bound`, `shizuku_available`, `current_state`, `filter_mode`, `uptime_seconds`.

## Shizuku Integration Pattern

Uses **AIDL UserService** (not shell commands):
1. `ShizukuUserService` runs in Shizuku process
2. `IShizukuService.aidl` defines `tapRepeat(x, y, times, intervalMs)`
3. `ShizukuHelper` manages bind/unbind lifecycle with auto-reconnect
4. All rebind attempts logged with trigger reason for traceability

## Accessibility Service Pattern

### Event Handling
- Only processes `com.ubercab.driver` package events
- `TYPE_WINDOW_STATE_CHANGED` → state transitions (app opened/closed)
- `TYPE_WINDOW_CONTENT_CHANGED` → offer detection + parsing

### Parsing Strategy (Dual)
1. **Primary**: ViewId-based (`uda_details_pickup_address_text_view` etc.) → HIGH confidence
2. **Fallback**: Regex on all text content → MEDIUM confidence

### Accept Strategy
1. **Primary**: Shizuku `tapRepeat()` via AIDL IPC
2. **Fallback**: `dispatchGesture()` (AccessibilityService built-in)

## Settings Persistence

- SharedPreferences key: `uber_auto_accept`
- Individual keys per setting (`filter_mode`, `min_customer_distance`, `enabled_conditions` etc.)
- Config reload via broadcast: `com.uber.autoaccept.RELOAD_CONFIG`

## Build & Deploy

```bash
JAVA_HOME="D:/Android/jbr" ./gradlew assembleDebug    # Build
adb install app/build/outputs/apk/debug/app-debug.apk  # Install
```

- Kotlin 1.9.0, minSdk 26, targetSdk 34
- Key dependencies: Shizuku 13.1.5, OpenCV, Kotlin Coroutines, Gson
- AIDL and BuildConfig enabled in build.gradle

## Anti-Patterns to Avoid

1. **Never mutate state directly** — always go through `StateMachine.handleEvent()`
2. **Never ignore recovery** — every service death must attempt self-heal
3. **Never log without trigger** — recovery/rebind logs must include the cause
4. **Never skip flush on critical events** — recovery logs call `flushNow()` immediately
5. **Never hardcode Uber ViewIds without documenting** — they break on app updates

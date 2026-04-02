# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# JAVA_HOME must point to Android Studio's JBR (bundled JDK)
export JAVA_HOME="D:/Android/jbr"

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore.properties or env vars)
./gradlew assembleRelease

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.uber.autoaccept.ExampleUnitTest"

# Clean build
./gradlew clean

# Create a release (triggers CI/CD pipeline)
git tag v1.1.0 && git push origin v1.1.0
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
- **`engine/FilterEngine.kt`** — Evaluates offers against `FilterSettings`. Single condition selected via `enabledConditions: Set<Int>` (RadioGroup, 1개만):
  - 1: 서울출발 → 공항 또는 서울행 (`pickupKeywords` AND (airport OR pickupKeywords in dropoff))
  - 2: 인천공항출발 → 어디든 (pickup is airport keyword)
  - 3: 광역시출발 → 특별시행 (pickup "광역시" AND dropoff "특별시")
  - 4: 어디서든 → 인천공항행 (dropoff is airport keyword) — **기본값**
  - 5: 특별시출발 → 광역시 중구행 (pickup "특별시" AND dropoff matches "광역시.*중구")
  - Reject 로그에 `[활성=, kw=]` 진단정보 포함 (원격 디버깅용)
- **`state/StateHandlers.kt`** — One handler per state. Signature: `handle(state, rootNode): StateEvent?`
- **`service/UberAccessibilityService.kt`** — Main entry point. Listens for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` from `com.ubercab.driver`. Orchestrates state machine, config loading, and service recovery.
- **`service/FloatingWidgetService.kt`** — Foreground service with two overlay windows: draggable status widget + ⊕ crosshair target. Crosshair coordinates feed `AcceptingHandler.targetClickPoint`.
- **`service/ServiceState.kt`** — SharedPreferences-backed singleton bridging accessibility and floating widget services. Persists `start()/stop()` to survive process kills. `restoreIfNeeded()` on service reconnect. **서비스 연결 시 자동 start 없음** — 사용자가 명시적으로 start하거나 이전 상태 복원 시에만 active.
- **`utils/UberOfferParser.kt`** — 4-tier address parsing: 1순위 `findAccessibilityNodeInfosByText` (MEDIUM), 2순위 ViewId full-screen (HIGH), 3순위 ViewId card (MEDIUM), 4순위 `→` arrow from `extractAllText` (LOW, contentDescription 포함). 오버레이 렌더링 시 1~3순위 실패하면 4순위로 대응.
- **`utils/AccessibilityHelper.kt`** — `AccessibilityNodeInfo` traversal utilities.
- **`utils/ShizukuHelper.kt`** — AIDL-based Shizuku UserService binding with auto-reconnect. Manages `IShizukuService` lifecycle.
- **`service/ShizukuUserService.kt`** — Runs in Shizuku process. Implements `IShizukuService.aidl` (`tap`, `tapRepeat` via `input tap` shell command).
- **`logging/RemoteLogger.kt`** — Batches log entries (max 200), flushes to Supabase `uber_logs` every 10s. Heartbeat to `uber_users` every 60s. Recovery events flush immediately.
- **`auth/`** — `AuthManager` calls `check_license` Supabase RPC. Caches 24h. Prefs key: `twinme_auth`.
- **`supabase/`** — `SupabaseClient` (plain HTTP REST/RPC) and `SupabaseConfig` (reads `BuildConfig.SUPABASE_URL` / `BuildConfig.SUPABASE_ANON_KEY` from `local.properties`).
- **`update/`** — `UpdateChecker` (Supabase RPC `get_download_url` with 24h cache), `UpdateDialog` (AlertDialog with download links), `UpdateModels` (data classes).
- **`ui/`** — `MainActivity` (service status with 3-state indicator, auth gate, auto update check), `SettingsActivity` (RadioGroup 단일조건 선택, distance range, version info, manual update check). 저장 시 `RELOAD_CONFIG` 브로드캐스트로 서비스 즉시 반영.

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

### Update System

The app checks for updates via Supabase RPC `get_download_url`. Only devices registered in `uber_users` table receive download URLs. `apks` bucket is **public**; auth is at the RPC level. Flow:

1. **Auto check**: `MainActivity` calls `UpdateChecker.check()` after successful auth (24h cache).
2. **Manual check**: Settings → "업데이트 확인" button (`forceCheck = true`).
3. **Server-side**: `get_download_url(device_id, current_version)` → checks `uber_users` → queries `apk_releases` → returns public Storage URLs.
4. **Download**: `ACTION_VIEW` intent opens browser.
5. **Web download**: `https://uber-download.pages.dev/download.html` (Cloudflare Pages). 전화번호 + 비밀번호 → `get_download_url_by_phone` RPC (비밀번호는 서버사이드 검증).

### CI/CD Pipeline

- **`build.yml`**: Triggers on push to master. Builds debug APK, uploads as artifact (30-day retention).
- **`release.yml`**: Triggers on `v*` tag push. Builds signed release APK, downloads latest Shizuku APK from rikkaapps/Shizuku, uploads both to Supabase Storage `apks` bucket, inserts record in `apk_releases` table, creates GitHub Release with changelog.

**Required GitHub Secrets**: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`.

### Supabase Backend

| Table | Purpose |
|-------|---------|
| `uber_users` | Device registration, heartbeat state (UPSERT on `device_id`) |
| `uber_logs` | Unified log table (parse, action, viewid_health, lifecycle, debug) |
| `apk_releases` | Version metadata, APK paths in Storage, changelog |

Key RPC functions: `check_license` (auth), `get_download_url` (앱 내 업데이트, device_id 기반), `get_download_url_by_phone` (웹 다운로드, 전화번호+비밀번호 서버검증).

## Tech Stack

- **Language:** Kotlin 1.9.0
- **Android:** minSdk 26, targetSdk 34, compileSdk 34
- **Build:** Gradle 8.7, AGP 8.1.0, AIDL enabled, BuildConfig enabled
- **Async:** Kotlin Coroutines (Main/IO) + StateFlow
- **External:** Shizuku 13.1.5 (AIDL UserService), Supabase (auth + logging + storage), OpenCV (button detection), Gson

## Conventions

- State transitions: `StateEvent` → `StateMachine.handleEvent()`. Never set state directly.
- Filter conditions: single-select via `selected_condition` (Int, SharedPrefs) → `setOf(N)` in `enabledConditions`. UI는 RadioGroup. New condition = new `val conditionN` in `FilterEngine.isEligible()` + new RadioButton + new index.
- Filter keywords (airport names, district names) are hardcoded in Korean in `FilterSettings` defaults. `pickupKeywords`는 `listOf("특별시")` 고정 (UI 제거됨).
- Humanization delay: base 200ms + optional random 0–200ms when `humanizationEnabled = true`.
- All `AccessibilityNodeInfo` operations go through `AccessibilityHelper`.
- SharedPreferences keys: `uber_auto_accept` (filter/service), `twinme_auth` (auth cache).
- `RemoteLogger.initialize()` before use; `shutdown()` on service destroy.
- `ServiceState.init(context)` must be called in both `UberAccessibilityService` and `FloatingWidgetService`.
- Shizuku rebind calls must include a trigger string for log traceability.
- Supabase HTTP calls follow `SupabaseClient` patterns: `HttpURLConnection` + Gson + `Dispatchers.IO`. New RPC calls should use `rpcPost()`.
- Release signing: `keystore.properties` (local) or env vars `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` (CI).
- Version management: single source of truth in `app/build.gradle` (`versionCode`, `versionName`). Exposed via `BuildConfig.VERSION_NAME`.
- Supabase keys: `local.properties`에 `SUPABASE_URL`, `SUPABASE_ANON_KEY` → `build.gradle`의 `buildConfigField`로 `BuildConfig`에 주입. **절대 소스에 하드코딩 금지.** CI는 GitHub Secrets에서 주입.
- APK 배포: Supabase Storage `apks` 버킷 (public). 다운로드 페이지는 Cloudflare Pages (`uber-download.pages.dev`). `npx wrangler pages deploy web --project-name uber-download --branch main`으로 배포.

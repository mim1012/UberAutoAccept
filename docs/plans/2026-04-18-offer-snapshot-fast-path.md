# UberAutoAccept Instant Offer Snapshot Fast-Path Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 콜 오퍼 카드가 짧게 떴다가 사라져도, 감지 순간 스냅샷을 고정해서 주소 파싱 → 조건 검사 → 탭까지 1회 fast-path로 끝내는 흐름을 만든다.

**Architecture:** 기존 구조는 live `AccessibilityNodeInfo`를 잡아 두고 나중에 다시 파싱해서 순간 카드에 취약하다. 새 구조는 감지 순간에 오퍼 스냅샷(텍스트, view id, 버튼 후보, bounds, 메타)을 즉시 캡처하고, 그 immutable snapshot을 기준으로 파싱/조건검사/탭을 이어간다. live node 재조회는 fallback으로만 남긴다.

**Tech Stack:** Kotlin, Android AccessibilityService, existing StateMachine, Supabase remote logging, JUnit

---

## Acceptance Criteria

- 오퍼 감지 순간 `OfferSnapshot` 객체가 생성되어 logcat/Supabase에서 식별 가능해야 함
- live root가 사라져도 snapshot 기반으로 pickup/dropoff 파싱이 가능해야 함
- snapshot 기반 조건 검사 후 fast-path accept가 가능해야 함
- 최근 실패 원인(`offer_window_not_found`, `ADDR_FAIL`, `accept_button_found=false`)를 더 정확히 분류해야 함
- 로그에 `app_version`, `git_tag`, `snapshot_source`, `snapshot_age_ms`, `candidate_count`가 남아야 함
- 기존 fallback(state-machine/live-node 파싱)도 깨지지 않아야 함

---

## Task 1: OfferSnapshot 모델 추가

**Objective:** 감지 순간의 UI 상태를 immutable 구조로 보관할 데이터 모델을 만든다.

**Files:**
- Modify: `app/src/main/java/com/uber/autoaccept/model/Models.kt`
- Test: `app/src/test/java/com/uber/autoaccept/utils/OfferSnapshotBuilderTest.kt`

**Step 1: 모델 추가**

`Models.kt`에 아래 데이터 구조 추가:

```kotlin
data class SnapshotNode(
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: Rect?,
    val clickable: Boolean
)

data class OfferSnapshot(
    val capturedAt: Long,
    val source: String,
    val packageName: String,
    val eventType: Int,
    val rootCount: Int,
    val nodes: List<SnapshotNode>,
    val resourceIdCounts: Map<String, Int>,
    val addressCandidates: List<SnapshotNode>,
    val acceptButtonCandidates: List<SnapshotNode>,
    val strongMarkers: Set<String>,
    val textDigest: String
)
```

**Step 2: 테스트 추가**
- `OfferSnapshotBuilderTest.kt` 생성
- snapshot object가 null 없이 직렬화 가능한지 검증

**Step 3: 테스트 실행**
Run: `./gradlew test --tests "*OfferSnapshotBuilderTest"`

**Step 4: Commit**
```bash
git add app/src/main/java/com/uber/autoaccept/model/Models.kt app/src/test/java/com/uber/autoaccept/utils/OfferSnapshotBuilderTest.kt
git commit -m "feat: add immutable offer snapshot model"
```

---

## Task 2: Accessibility tree를 snapshot으로 변환하는 빌더 추가

**Objective:** live `AccessibilityNodeInfo`를 즉시 immutable snapshot으로 복사하는 유틸을 만든다.

**Files:**
- Create: `app/src/main/java/com/uber/autoaccept/utils/OfferSnapshotBuilder.kt`
- Modify: `app/src/main/java/com/uber/autoaccept/utils/AccessibilityHelper.kt`
- Test: `app/src/test/java/com/uber/autoaccept/utils/OfferSnapshotBuilderTest.kt`

**Step 1: 빌더 생성**
- `OfferSnapshotBuilder.build(source, eventType, roots)` 구현
- 각 node에서 다음 필드 복사:
  - viewId short name
  - className
  - text
  - contentDescription
  - bounds
  - clickable

**Step 2: helper 확장**
- `AccessibilityHelper`에 snapshot-friendly 함수 추가:
  - `collectSnapshotNodes(root, limitDepth = 8, limitNodes = 250)`
  - `collectStrongMarkers(resourceIds)`
  - `findAddressLikeSnapshotNodes(nodes)`
  - `findAcceptButtonSnapshotNodes(nodes)`

**Step 3: 테스트 작성**
- fake snapshot node 리스트 기준으로:
  - address 후보 검출
  - accept button 후보 검출
  - marker 추출 검증

**Step 4: 테스트 실행**
Run: `./gradlew test --tests "*OfferSnapshotBuilderTest"`

**Step 5: Commit**
```bash
git add app/src/main/java/com/uber/autoaccept/utils/OfferSnapshotBuilder.kt app/src/main/java/com/uber/autoaccept/utils/AccessibilityHelper.kt app/src/test/java/com/uber/autoaccept/utils/OfferSnapshotBuilderTest.kt
git commit -m "feat: build immutable offer snapshots from accessibility roots"
```

---

## Task 3: 감지 순간 multi-sample snapshot 캡처 추가

**Objective:** 카드가 짧게 떠도 잡히도록 1회가 아니라 짧은 burst로 snapshot을 수집한다.

**Files:**
- Modify: `app/src/main/java/com/uber/autoaccept/service/UberAccessibilityService.kt`
- Modify: `app/src/main/java/com/uber/autoaccept/utils/UberOfferGate.kt`

**Step 1: sampling helper 추가**
`UberAccessibilityService.kt`에:
- `captureOfferSnapshots(source: String, eventType: Int): List<OfferSnapshot>` 추가
- 샘플링 타이밍: `0ms`, `50ms`, `120ms`, `250ms`
- 각 시점마다 windows/rootInActiveWindow 복사

**Step 2: fast-path 진입점 연결**
기존
- `findOfferWindow(...)`
- `StateEvent.NewOfferAppeared(root)`
중간에
- snapshots 생성
- best snapshot 선택
- snapshot 성공 시 fast-path parse로 우선 진행

**Step 3: best snapshot 선택 규칙 구현**
우선순위:
1. strong marker 존재
2. pickup/dropoff candidate 둘 다 존재
3. accept button candidate 존재
4. node count / textDigest richness

**Step 4: Commit**
```bash
git add app/src/main/java/com/uber/autoaccept/service/UberAccessibilityService.kt app/src/main/java/com/uber/autoaccept/utils/UberOfferGate.kt
git commit -m "feat: capture multi-sample offer snapshots on detection"
```

---

## Task 4: Snapshot 기반 파서 추가

**Objective:** live node 대신 snapshot에서 pickup/dropoff를 파싱한다.

**Files:**
- Modify: `app/src/main/java/com/uber/autoaccept/utils/UberOfferParser.kt`
- Modify: `app/src/main/java/com/uber/autoaccept/model/Models.kt`
- Test: `app/src/test/java/com/uber/autoaccept/utils/UberOfferParserSnapshotTest.kt`

**Step 1: 새 API 추가**
`UberOfferParser`에:
- `fun parseOfferSnapshot(snapshot: OfferSnapshot): UberOffer?`
추가

**Step 2: snapshot 파싱 규칙 구현**
우선순위:
1. trusted pickup/dropoff view id pair
2. known alternative pair
3. address-like nodes + spatial pairing(위/아래 순서)
4. reject if non-offer keywords dominate

**Step 3: strict validation 추가**
성공으로 치기 위한 최소 조건:
- pickup/dropoff 둘 다 address-like
- strong marker 또는 accept button candidate 중 하나 이상 존재

**Step 4: snapshot tests 작성**
케이스:
- 정상 uda_details pair
- alt pair
- offline/promo 텍스트 포함 비오퍼 화면 → parse 실패
- 카드가 축약된 상태지만 address candidate는 있는 경우

**Step 5: 테스트 실행**
Run: `./gradlew test --tests "*UberOfferParserSnapshotTest"`

**Step 6: Commit**
```bash
git add app/src/main/java/com/uber/autoaccept/utils/UberOfferParser.kt app/src/test/java/com/uber/autoaccept/utils/UberOfferParserSnapshotTest.kt app/src/main/java/com/uber/autoaccept/model/Models.kt
git commit -m "feat: parse offers from immutable snapshots"
```

---

## Task 5: Snapshot 기반 조건검사 fast-path 추가

**Objective:** 파싱 성공 직후 state-machine full roundtrip 없이 조건검사까지 이어서 빠르게 결론낸다.

**Files:**
- Modify: `app/src/main/java/com/uber/autoaccept/service/UberAccessibilityService.kt`
- Modify: `app/src/main/java/com/uber/autoaccept/engine/FilterEngine.kt`
- Modify: `app/src/main/java/com/uber/autoaccept/state/StateHandlers.kt`

**Step 1: fast-path orchestration 추가**
- `processOfferSnapshotFastPath(snapshot)` 추가
- 내부 순서:
  1. snapshot parse
  2. filterEngine.isEligible(offer)
  3. accepted이면 accept dispatch
  4. rejected면 reject log 후 Online 유지

**Step 2: fallback 유지**
- snapshot 실패 시 기존 `StateEvent.NewOfferAppeared(root)` 경로 유지
- 단, fast-path가 우선

**Step 3: 중복처리 방지**
- `offer_uuid` 또는 `textDigest + capturedAt bucket` 기준 2~3초 dedupe
- 같은 카드 두 번 tap 방지

**Step 4: Commit**
```bash
git add app/src/main/java/com/uber/autoaccept/service/UberAccessibilityService.kt app/src/main/java/com/uber/autoaccept/engine/FilterEngine.kt app/src/main/java/com/uber/autoaccept/state/StateHandlers.kt
git commit -m "feat: add snapshot-based filter fast path"
```

---

## Task 6: Accept fast-path 개선

**Objective:** 조건 통과 직후 가장 빠른 탭 경로를 선택하고 버튼 미탐지여도 overlay target fallback을 사용한다.

**Files:**
- Modify: `app/src/main/java/com/uber/autoaccept/state/StateHandlers.kt`
- Modify: `app/src/main/java/com/uber/autoaccept/utils/GestureClicker.kt`
- Modify: `app/src/main/java/com/uber/autoaccept/utils/UberOfferParser.kt`

**Step 1: button candidate ranking 추가**
우선순위:
1. snapshot accept button candidate bounds
2. current overlay target point
3. live node stored button
4. viewId lookup fallback

**Step 2: accept timing 로그 추가**
- `snapshot_age_ms`
- `decision_latency_ms`
- `tap_dispatch_latency_ms`
- `tap_method`

**Step 3: 성공/실패 로그 개선**
- accept false인데 button not found인지
- tap dispatch 실패인지
- stale snapshot인지 분리

**Step 4: Commit**
```bash
git add app/src/main/java/com/uber/autoaccept/state/StateHandlers.kt app/src/main/java/com/uber/autoaccept/utils/GestureClicker.kt app/src/main/java/com/uber/autoaccept/utils/UberOfferParser.kt
git commit -m "feat: prioritize fast accept path from snapshots"
```

---

## Task 7: 원격 로그를 실검증 가능하게 강화

**Objective:** 최신 배포가 실제로 어떤 바이너리/로직인지 증명 가능하게 한다.

**Files:**
- Modify: `app/src/main/java/com/uber/autoaccept/logging/RemoteLogger.kt`
- Modify: `app/src/main/java/com/uber/autoaccept/logging/LogModels.kt`
- Modify: `app/build.gradle`
- Modify: `supabase/offer_detection_notes.md`
- Modify: `supabase/offer_detection_queries.sql`

**Step 1: 모든 parse/action/debug offer_detection 로그에 공통 메타 추가**
- `app_version`
- `git_tag`
- `snapshot_source`
- `snapshot_age_ms`
- `candidate_count`
- `fast_path`

**Step 2: 현재 누락되는 parser metadata 문제 수정**
현재 live DB에서 `parser_source`, `pickup_view_id`, `dropoff_view_id`가 안 보이므로:
- 직렬화 경로 점검
- json payload에 실제로 들어가도록 테스트 추가

**Step 3: Supabase 쿼리 업데이트**
- fast-path success rate
- snapshot age histogram
- stale snapshot fail count
- app_version별 parse success

**Step 4: Commit**
```bash
git add app/src/main/java/com/uber/autoaccept/logging/RemoteLogger.kt app/src/main/java/com/uber/autoaccept/logging/LogModels.kt app/build.gradle supabase/offer_detection_notes.md supabase/offer_detection_queries.sql
git commit -m "feat: add versioned snapshot diagnostics to remote logs"
```

---

## Task 8: regression tests + manual verification checklist

**Objective:** 실제 순간 카드 문제를 재발 방지 가능한 형태로 검증한다.

**Files:**
- Create: `docs/testing/offer-snapshot-fast-path.md`
- Modify: `README.md`

**Step 1: 테스트 문서 작성**
문서에 아래 검증 포함:
- 실기기에서 offer_detection → parse → action sequence 확인
- `offer_window_not_found` 감소 추이 확인
- `accept_button_found=false`인데 accept 성공한 케이스 분리 확인
- app_version별 비교

**Step 2: 수동 검증 체크리스트**
- 오퍼가 순간적으로 떴다가 사라지는 상황에서:
  - snapshot capture 로그 존재
  - parse success/fail 분류 명확
  - filter result 기록
  - tap method 기록

**Step 3: 최종 명령**
```bash
./gradlew test
./gradlew assembleDebug
```

**Step 4: Commit**
```bash
git add docs/testing/offer-snapshot-fast-path.md README.md
git commit -m "docs: add verification guide for offer snapshot fast path"
```

---

## Verification Focus

릴리즈 후 Supabase에서 반드시 확인할 것:
- `trigger_parse` 대비 `parse success` 비율
- `offer_window_not_found` 비율 감소 여부
- `ADDR_FAIL` 감소 여부
- `snapshot_age_ms` 분포
- device별 fast-path success
- `app_version/git_tag`별 비교

## Immediate Recommendation

가장 먼저 해야 할 핵심 3개:
1. immutable `OfferSnapshot` 도입
2. multi-sample capture 도입
3. parse/filter/tap fast-path 도입

이 세 개 없이 현재 구조에서 delay만 줄이는 건 근본 해결이 아님.

# Uber Auto Accept

Vortex 스타일의 상태 머신 아키텍처를 기반으로 한 Uber 드라이버 자동 콜 수락 Android 애플리케이션입니다.

## 주요 기능

### 🎯 듀얼 모드 필터링
- **인천공항 모드**: 도착지가 "인천공항"인 콜을 자동 수락
- **서울 진입 모드**: 도착지가 "서울"인 콜을 자동 수락
- **두 모드 모두**: 두 조건 중 하나라도 만족하면 수락
- **고객 거리 필터**: 1~3km 범위 내 고객만 선택 (사용자 설정 가능)

### 🏗️ 상태 머신 아키텍처
Vortex의 검증된 설계 패턴을 따릅니다:
- **StateMachine**: 상태 전환 관리
- **StateHandler**: 각 상태별 처리 로직
- **Main Loop**: 상태 관찰 및 자동 처리

### 📱 AccessibilityService 기반
- ViewId 기반 안정적인 화면 파싱
- 정규식 Fallback 지원
- 비루팅 환경에서 동작

## 프로젝트 구조

```
app/src/main/java/com/uber/autoaccept/
├── model/          # 데이터 모델 및 상태 정의
│   ├── AppState.kt
│   └── Models.kt
├── engine/         # 핵심 엔진
│   ├── StateMachine.kt
│   └── FilterEngine.kt
├── state/          # 상태 핸들러
│   ├── StateHandler.kt
│   └── StateHandlers.kt
├── service/        # AccessibilityService
│   └── UberAccessibilityService.kt
├── utils/          # 유틸리티
│   ├── AccessibilityHelper.kt
│   └── UberOfferParser.kt
└── ui/             # 사용자 인터페이스
    ├── MainActivity.kt
    └── SettingsActivity.kt
```

## 상태 흐름

```
Idle → Online → OfferDetected → OfferAnalyzing → ReadyToAccept → Accepting → Accepted
                     ↓                                                           ↓
                  Error ←────────────────────────────────────────────────────────┘
                     ↓
                 Rejected → Online
```

## 설치 및 사용

### 1. 빌드
```bash
cd /home/ubuntu/UberAutoAccept
./gradlew assembleDebug
```

### 2. 설치
생성된 APK를 Android 기기에 설치합니다:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 설정
1. 앱을 실행합니다
2. "서비스 활성화" 버튼을 눌러 접근성 설정으로 이동합니다
3. "Uber Auto Accept" 서비스를 활성화합니다
4. "설정" 버튼을 눌러 필터링 모드와 거리 범위를 설정합니다

### 4. 사용
- Uber 드라이버 앱을 실행하고 온라인 상태로 전환합니다
- 조건에 맞는 콜이 들어오면 자동으로 수락됩니다

## 핵심 ViewId

APK 리버스 엔지니어링을 통해 추출한 주요 ViewId:

| 요소 | ViewId |
|------|--------|
| 출발지 | `uda_details_pickup_address_text_view` |
| 도착지 | `uda_details_dropoff_address_text_view` |
| 거리 | `uda_details_distance_text_view` |
| 소요 시간 | `uda_details_duration_text_view` |
| 수락 버튼 | `upfront_offer_configurable_details_accept_button` |

## 기술 스택

- **언어**: Kotlin
- **최소 SDK**: 26 (Android 8.0)
- **타겟 SDK**: 34 (Android 14)
- **주요 라이브러리**:
  - AndroidX Core KTX
  - Kotlin Coroutines
  - Lifecycle Components
  - Shizuku API (선택적)

## 보안 및 주의사항

⚠️ **이 앱은 교육 및 연구 목적으로만 제공됩니다.**

- Uber의 서비스 약관을 위반할 수 있습니다
- 실제 사용 시 계정 정지 위험이 있습니다
- 앱 업데이트 시 ViewId가 변경될 수 있습니다

## 라이선스

MIT License

## 기여

이슈 및 풀 리퀘스트를 환영합니다.

## 참고

이 프로젝트는 [Vortex](https://github.com/mim1012/Vortex)의 상태 머신 아키텍처를 참고하여 제작되었습니다.

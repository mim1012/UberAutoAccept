# Uber Auto Accept

Vortex 상태 머신 기반 Uber 드라이버 자동 콜 수락 Android 앱.

## 다운로드 (사용자용)

**다운로드 페이지**: https://uber-download.pages.dev/download.html

1. 위 링크 접속 (카톡으로 공유받은 링크)
2. **등록된 전화번호** + **비밀번호** 입력
3. 인증 성공 → **Shizuku** + **UberAutoAccept** 두 개 다운로드
4. 링크는 5분간 유효 — 만료 시 다시 인증

**두 개 APK가 한 세트입니다.** 반드시 둘 다 설치해야 정상 동작합니다.

| APK | 역할 | 필수 |
|-----|------|------|
| `UberAutoAccept-x.x.x.apk` | 메인 앱 (콜 감지 + 자동 수락) | 필수 |
| `Shizuku.apk` | 시스템 탭 권한 (수락 버튼 클릭) | 필수 |

### 설치 순서

1. **Shizuku 먼저 설치** → 앱 실행 → "Start via ADB" 또는 "Start via Root"로 활성화
2. **UberAutoAccept 설치** → 앱 실행 → 인증 → 접근성 서비스 활성화
3. **Shizuku 권한 허용** — UberAutoAccept가 Shizuku 권한을 요청하면 허용

### Shizuku가 없으면?

`dispatchGesture` 폴백이 동작하지만, Uber 앱이 접근성 제스처를 차단할 수 있어 **수락 실패율이 높아집니다**. Shizuku는 사실상 필수입니다.

---

## 배포 관리 (관리자용)

### 릴리즈 만들기

```bash
# 1. app/build.gradle에서 버전 올리기
versionCode 3
versionName "1.2.0"

# 2. 커밋 + 태그
git add -A && git commit -m "feat: 새 기능"
git tag v1.2.0
git push origin master --tags
```

태그 push 시 GitHub Actions가 자동으로:
- Release APK 빌드 (서명됨)
- 최신 Shizuku APK 다운로드
- 둘 다 Supabase Storage에 업로드
- `apk_releases` 테이블에 레코드 삽입
- GitHub Release 생성 (changelog)

### 릴리즈 확인

```bash
# GitHub Release 확인
gh release view v1.2.0

# Supabase에 업로드된 APK 확인
curl -s "https://czqnybgoaeihwvgdtvgn.supabase.co/rest/v1/apk_releases?select=*&order=created_at.desc&limit=3" \
  -H "apikey: YOUR_SERVICE_KEY" \
  -H "Authorization: Bearer YOUR_SERVICE_KEY"

# Storage bucket 파일 목록
curl -s "https://czqnybgoaeihwvgdtvgn.supabase.co/storage/v1/object/list/apks" \
  -H "apikey: YOUR_SERVICE_KEY" \
  -H "Authorization: Bearer YOUR_SERVICE_KEY" \
  -H "Content-Type: application/json" \
  -d '{"prefix":"","limit":100}'
```

### 사용자 관리

인증된 사용자(`uber_users` 테이블에 등록된 기기)만 APK 다운로드 가능.

```bash
# 등록된 사용자 목록 확인
curl -s "https://czqnybgoaeihwvgdtvgn.supabase.co/rest/v1/uber_users?select=device_id,phone_number,device_name,app_version,last_heartbeat_at&order=last_heartbeat_at.desc" \
  -H "apikey: YOUR_SERVICE_KEY" \
  -H "Authorization: Bearer YOUR_SERVICE_KEY"
```

앱에서 업데이트 확인 시 `get_download_url(device_id, current_version)` RPC가 호출되며, `uber_users`에 없는 기기는 `allowed: false`를 받아 다운로드 불가.

### 다운로드 페이지 관리

**페이지 URL**: `https://uber-download.pages.dev/download.html`

카톡으로 이 URL 하나만 공유하면 됩니다. 사용자는 전화번호 + 비밀번호로 인증 후 다운로드.

- **비밀번호 변경**: `web/download.html`의 `DOWNLOAD_PASSWORD` 값 수정 후 push
- **호스팅**: GitHub Pages (Settings → Pages → Branch: `master` / `/web`)
- **보안**: 전화번호(`uber_users` 테이블 확인) + 공용 비밀번호 이중 체크
- **signed URL**: 인증 성공 시 5분 유효 다운로드 링크 발급

### 운영 로그 대시보드

GitHub Pages를 같은 `/web` 폴더에 물리면 정적 대시보드도 같이 볼 수 있습니다.

- **대시보드 파일**: `web/logs.html`
- **예상 URL**: `https://mim1012.github.io/UberAutoAccept/logs.html` 또는 커스텀 Pages 도메인 하위 경로
- **용도**: 최근 1/3/7일 Uber 로그를 Supabase에서 직접 읽어와서 파싱 성공률, 수락 성공률, 버튼 탐지 실패율, ViewID 실패율, Shizuku 비가용률, 실패 TopN을 시각화
- **설정 방법**:
  1. GitHub repo → Settings → Pages
  2. Build and deployment → Deploy from a branch
  3. Branch: `master`, Folder: `/web`
  4. 저장 후 배포 완료되면 `logs.html` 경로로 접속
- **주의**: 브라우저에 들어가는 키는 anon key만 사용. service_role 키는 절대 넣지 않음.

### GitHub Secrets

| Secret | 용도 |
|--------|------|
| `KEYSTORE_BASE64` | Release APK 서명용 keystore (base64) |
| `KEYSTORE_PASSWORD` | keystore 비밀번호 |
| `KEY_ALIAS` | 키 별칭 |
| `KEY_PASSWORD` | 키 비밀번호 |
| `SUPABASE_URL` | Supabase 프로젝트 URL |
| `SUPABASE_SERVICE_KEY` | Supabase service role key (Storage 업로드 + DB INSERT) |

### Supabase 구성

| 리소스 | 용도 |
|--------|------|
| `apks` bucket (private) | APK 파일 저장 (signed URL로만 접근) |
| `apk_releases` 테이블 | 버전 메타데이터, 파일 경로, changelog |
| `uber_users` 테이블 | 기기 인증, 하트비트, 상태 |
| `get_download_url` RPC | 앱 내 업데이트 체크 (device_id 기반, signed URL 5분) |
| `get_download_url_by_phone` RPC | 웹 다운로드 페이지 (전화번호 기반, signed URL 5분) |

---

## 주요 기능

### 필터링 (4가지 조건)

| 조건 | 출발지 | 도착지 |
|------|--------|--------|
| 1 | 키워드 매치 (특별시 등) | 공항 또는 광역시 |
| 2 | 공항 | 어디든 |
| 3 | 광역시 | 특별시 |
| 4 | 어디든 | 공항 |

Settings에서 개별 ON/OFF 가능. 하나라도 만족하면 수락.

### 수락 전략 (2단계)

1. **Shizuku AIDL** (기본) — `input tap` 셸 명령 5회 연타. 접근성 제스처 플래그 우회.
2. **dispatchGesture** (폴백) — Shizuku 미사용 시.

### 서비스 자동 복구

| 상황 | 복구 |
|------|------|
| Shizuku IPC 실패 | 즉시 재바인딩 |
| Shizuku binder 종료 | 3초 후 재바인딩 |
| 프로세스 킬 | SharedPreferences에서 상태 복원 |

---

## 빌드

```bash
export JAVA_HOME="D:/Android/jbr"

# Debug
./gradlew assembleDebug

# Release (keystore.properties 필요)
./gradlew assembleRelease

# 테스트
./gradlew test
```

## 기술 스택

- Kotlin 1.9.0 / minSdk 26 / targetSdk 34
- Shizuku 13.1.5 (AIDL UserService)
- Supabase (인증 + 로깅 + Storage)
- OpenCV 4.9.0 (시각적 감지 폴백)
- Kotlin Coroutines + StateFlow

## 라이선스

MIT License

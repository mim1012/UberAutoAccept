Summary
- 메인 화면 시작 버튼이 이제 엔진 시작까지 직접 연결되어, 접근성 연결 후 바로 오퍼 감지/파싱/수락 흐름이 진행됩니다.
- 오퍼 감지는 더 엄격해졌고, 파싱 성공/실패 원인을 원격 로그로 구조화해 다음 개선 작업에 바로 활용할 수 있습니다.
- 릴리스 자동화는 태그 버전 기준으로 앱 버전과 릴리스 메타데이터를 일관되게 생성합니다.

Before -> After
- 시작 동작: 메인 화면 시작 버튼이 위젯만 띄움 -> 위젯 시작과 동시에 엔진 START 브로드캐스트 전송
- 엔진 상태: 접근성 이벤트는 오지만 service_active=false 상태가 반복될 수 있음 -> 시작 버튼으로 active 전환 경로 명확화
- 오퍼 게이트: 광역시/특별시 키워드 같은 느슨한 조건 포함 -> 신뢰 마커 + 주소 ViewId 쌍 중심으로 축소
- 주소 파싱: 성공/실패 근거가 약함 -> parser_source, pickup/dropoff_view_id, validated 여부를 구조화 로그로 기록
- 실패 분석: parse 실패 시 메시지 위주 -> error_code, failure_stage, ui_summary_ids/addrs/btns로 분석 가능
- 릴리스: 태그와 앱 버전이 분리될 수 있음 -> 태그 기반 versionName/versionCode와 changelog 자동 반영

Developer Notes
- 새 parse 메타데이터는 `uber_logs.data` jsonb 안에 저장되므로 테이블 컬럼 추가 마이그레이션은 필요 없습니다.
- 성능 최적화가 필요하면 `supabase/add_offer_parse_indexes.sql`의 expression index를 적용하세요.
- 배포 후에는 `offer_window_ui_summary`, `parser_source`, `pickup_view_id`, `dropoff_view_id`를 우선 확인하세요.

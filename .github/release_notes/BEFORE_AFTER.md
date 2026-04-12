Summary
- 주소만 정확히 파싱하고, 비-오퍼 화면 파싱을 차단. 접근성 연결 시 자동 활성화. Shizuku 안정화. ADB 없이 원격 추적 가능.

Before → After
- 주소 파싱: 가상뷰/텍스트/→ 분할 등 다단 fallback → 신뢰 ViewId 2단계만(uda_details_* / pick_/drop_off_address)
- 오퍼 게이트: 도시 키워드 등 느슨한 판별 → 수락버튼/오퍼마커 또는 픽업+드롭오프 ViewId 쌍 존재 시에만
- 서비스 활성: 수동 활성 의존 → 접근성 연결 시 자동 start로 “service not active” 제거
- Shizuku: 비데몬 → daemon(true)로 바인더 끊김 감소
- 디버깅: ADB/무선 필요 → 파싱 실패/미감지 시 UI 요약(ids/addrs/btns) 원격 로그로 확인

Developer Notes
- 파서 유지보수는 ViewId 목록만 관리하면 됨. 필요 시 leg_(pickup|dropoff)_address 추가 가능.
- 로그 필터: log_type=parse AND data->>success=true → 주소 페어만 확인 가능.

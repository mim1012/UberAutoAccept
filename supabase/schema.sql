-- =============================================================
-- UberAutoAccept - Supabase 스키마 (Vortex 패턴)
-- Supabase 대시보드 > SQL Editor 에서 실행하세요
-- =============================================================

-- 1. Licenses (전화번호 또는 기기ID 기반 라이센스)
create table if not exists public.licenses (
    id uuid default gen_random_uuid() primary key,
    phone_number text,              -- 전화번호 (선택)
    device_id text,                 -- ANDROID_ID (선택, 전화번호 없을 때 사용)
    user_type text default 'basic', -- 'basic', 'premium'
    is_active boolean default true,
    expires_at timestamptz,         -- NULL = 무기한
    memo text,                      -- 관리자 메모 (사용자 이름 등)
    created_at timestamptz default now()
);

-- 2. Uber Logs (오퍼 파싱/액션/라이프사이클/viewid_health 로그)
create table if not exists public.uber_logs (
    id uuid default gen_random_uuid() primary key,
    device_id text not null,
    log_type text not null,         -- 'parse', 'action', 'lifecycle', 'viewid_health'
    logged_at timestamptz default now(),
    data jsonb
);

-- 3. Uber Users (기기 상태 heartbeat, device_id 기준 UPSERT)
create table if not exists public.uber_users (
    device_id text primary key,
    phone_number text,
    service_connected boolean default false,
    current_state text,
    last_heartbeat_at timestamptz,
    uptime_seconds bigint,
    device_name text,
    os_version text,
    app_version text,
    filter_mode text,
    updated_at timestamptz default now(),
    created_at timestamptz default now()
);

-- =============================================================
-- check_license RPC 함수 (anon 호출 가능, security definer)
-- =============================================================
create or replace function check_license(p_identifier text)
returns json
language plpgsql
security definer
as $$
declare
    v_license record;
begin
    select * into v_license
    from public.licenses
    where (phone_number = p_identifier or device_id = p_identifier)
      and is_active = true
    order by created_at desc
    limit 1;

    if not found then
        return json_build_object(
            'authorized', false,
            'message', '등록되지 않은 기기입니다. 관리자에게 문의하세요.'
        );
    end if;

    if v_license.expires_at is not null and v_license.expires_at < now() then
        return json_build_object(
            'authorized', false,
            'message', '라이센스가 만료되었습니다.',
            'expires_at', v_license.expires_at::text
        );
    end if;

    return json_build_object(
        'authorized', true,
        'user_type', coalesce(v_license.user_type, 'basic'),
        'expires_at', coalesce(v_license.expires_at::text, ''),
        'message', '인증 성공'
    );
end;
$$;

-- anon 및 authenticated 역할에 실행 권한 부여
grant execute on function check_license(text) to anon;
grant execute on function check_license(text) to authenticated;

-- =============================================================
-- Row Level Security
-- =============================================================

alter table public.licenses enable row level security;
alter table public.uber_logs enable row level security;
alter table public.uber_users enable row level security;

-- Licenses: 직접 조회 불가 (RPC 함수를 통해서만 접근)
-- (관리자는 Supabase Dashboard에서 직접 관리)

-- Uber Logs: anon INSERT 허용 (기기ID로만 식별)
create policy "Anon can insert uber_logs"
    on public.uber_logs for insert
    to anon
    with check (true);

-- Uber Users: anon UPSERT 허용 (INSERT + UPDATE, device_id 기준 merge)
create policy "Anon can insert uber_users"
    on public.uber_users for insert
    to anon
    with check (true);

create policy "Anon can update uber_users"
    on public.uber_users for update
    to anon
    using (true)
    with check (true);

-- =============================================================
-- 라이센스 등록 예시 (관리자가 Supabase Dashboard에서 직접 실행)
-- phone_number: 앱에서 읽어온 전화번호 (01012345678 형식)
-- device_id: 앱 로그에서 확인한 ANDROID_ID
-- =============================================================
-- INSERT INTO public.licenses (phone_number, device_id, user_type, is_active, expires_at, memo)
-- VALUES (
--     '01012345678',                          -- 전화번호 (없으면 NULL)
--     'abc123def456',                         -- ANDROID_ID (없으면 NULL)
--     'basic',
--     true,
--     '2026-12-31T23:59:59Z',                -- NULL 이면 무기한
--     '홍길동'                                -- 관리자 메모
-- );

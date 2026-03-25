-- ============================================
-- UberAutoAccept - Supabase 설정 SQL
-- ============================================

-- 1. apk_releases 테이블 생성
CREATE TABLE IF NOT EXISTS apk_releases (
    id BIGSERIAL PRIMARY KEY,
    version_name TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    app_download_path TEXT NOT NULL,
    shizuku_download_path TEXT,
    shizuku_version TEXT,
    changelog TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- RLS 활성화 (서비스 키로만 INSERT 가능)
ALTER TABLE apk_releases ENABLE ROW LEVEL SECURITY;

-- anon은 읽기만 가능
CREATE POLICY "anon_read_releases" ON apk_releases
    FOR SELECT USING (true);

-- 2. Supabase Storage bucket 생성 (Supabase 대시보드에서 수동 생성 필요)
-- Bucket name: apks
-- Public: false

-- 3. get_download_url RPC 함수
CREATE OR REPLACE FUNCTION get_download_url(
    p_device_id TEXT,
    p_current_version TEXT DEFAULT NULL
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_exists BOOLEAN;
    v_latest RECORD;
    v_app_signed_url TEXT;
    v_shizuku_signed_url TEXT;
BEGIN
    -- uber_users 테이블에서 device_id 확인
    SELECT EXISTS(
        SELECT 1 FROM uber_users WHERE device_id = p_device_id
    ) INTO v_user_exists;

    IF NOT v_user_exists THEN
        RETURN json_build_object(
            'allowed', false,
            'has_update', false,
            'message', 'Device not registered'
        );
    END IF;

    -- 최신 릴리즈 조회
    SELECT version_name, app_download_path, shizuku_download_path, changelog
    INTO v_latest
    FROM apk_releases
    ORDER BY created_at DESC
    LIMIT 1;

    -- 릴리즈가 없으면
    IF v_latest IS NULL THEN
        RETURN json_build_object(
            'allowed', true,
            'has_update', false,
            'message', 'No releases available'
        );
    END IF;

    -- 버전 동일하면 업데이트 불필요
    IF v_latest.version_name = p_current_version THEN
        RETURN json_build_object(
            'allowed', true,
            'has_update', false,
            'message', 'Already up to date'
        );
    END IF;

    -- Signed URL 생성 (1시간 유효)
    SELECT (storage.fns.create_signed_url('apks', v_latest.app_download_path, 3600)).signed_url
    INTO v_app_signed_url;

    IF v_latest.shizuku_download_path IS NOT NULL AND v_latest.shizuku_download_path != '' THEN
        SELECT (storage.fns.create_signed_url('apks', v_latest.shizuku_download_path, 3600)).signed_url
        INTO v_shizuku_signed_url;
    END IF;

    RETURN json_build_object(
        'allowed', true,
        'has_update', true,
        'latest_version', v_latest.version_name,
        'app_url', v_app_signed_url,
        'shizuku_url', v_shizuku_signed_url,
        'message', 'Update available'
    );
END;
$$;

-- 4. RPC 함수 실행 권한 (anon 키로 호출 가능하도록)
GRANT EXECUTE ON FUNCTION get_download_url(TEXT, TEXT) TO anon;
GRANT EXECUTE ON FUNCTION get_download_url(TEXT, TEXT) TO authenticated;

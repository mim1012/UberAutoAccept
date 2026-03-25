-- 전화번호 + 비밀번호 기반 다운로드 URL 발급 (웹 페이지용)
-- 비밀번호는 서버사이드에서 검증 (클라이언트 하드코딩 제거)
CREATE OR REPLACE FUNCTION get_download_url_by_phone(
    p_phone TEXT,
    p_password TEXT DEFAULT NULL
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
    v_download_password TEXT := 'uber2024';
BEGIN
    -- 비밀번호 검증
    IF p_password IS NULL OR p_password != v_download_password THEN
        RETURN json_build_object(
            'allowed', false,
            'has_update', false,
            'message', '비밀번호가 올바르지 않습니다'
        );
    END IF;

    -- 전화번호로 uber_users 확인
    SELECT EXISTS(
        SELECT 1 FROM uber_users WHERE phone_number = p_phone
    ) INTO v_user_exists;

    IF NOT v_user_exists THEN
        RETURN json_build_object(
            'allowed', false,
            'has_update', false,
            'message', '등록되지 않은 전화번호입니다'
        );
    END IF;

    -- 최신 릴리즈 조회
    SELECT version_name, app_download_path, shizuku_download_path
    INTO v_latest
    FROM apk_releases
    ORDER BY created_at DESC
    LIMIT 1;

    IF v_latest IS NULL THEN
        RETURN json_build_object(
            'allowed', true,
            'has_update', false,
            'message', '배포된 버전이 없습니다'
        );
    END IF;

    -- Signed URL 생성 (5분 유효)
    SELECT (storage.fns.create_signed_url('apks', v_latest.app_download_path, 300)).signed_url
    INTO v_app_signed_url;

    IF v_latest.shizuku_download_path IS NOT NULL AND v_latest.shizuku_download_path != '' THEN
        SELECT (storage.fns.create_signed_url('apks', v_latest.shizuku_download_path, 300)).signed_url
        INTO v_shizuku_signed_url;
    END IF;

    RETURN json_build_object(
        'allowed', true,
        'has_update', true,
        'latest_version', v_latest.version_name,
        'app_url', v_app_signed_url,
        'shizuku_url', v_shizuku_signed_url,
        'message', 'Download ready'
    );
END;
$$;

GRANT EXECUTE ON FUNCTION get_download_url_by_phone(TEXT, TEXT) TO anon;
GRANT EXECUTE ON FUNCTION get_download_url_by_phone(TEXT, TEXT) TO authenticated;

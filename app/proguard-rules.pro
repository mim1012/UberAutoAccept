# ShizukuUserService - 기본 생성자 + Context 생성자 유지 (Shizuku 리플렉션 사용)
-keep class com.uber.autoaccept.service.ShizukuUserService {
    public <init>();
    public <init>(android.content.Context);
}

package com.pawtrail.auth.infrastructure.security;

import com.pawtrail.auth.infrastructure.config.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 토큰을 담을 쿠키를 만듭니다.
 *
 * 응답 바디에 토큰을 싣지 않고 쿠키에 담는 이유는 브라우저 스크립트가 값을 읽지 못하게 하기 위함입니다.
 * 저장소에 두고 헤더로 보내는 방식보다 나은 지점은, 스크립트가 오염되어도 토큰을 꺼낼 수 없다는 것입니다.
 * 대신 요청 위조가 문제가 되는데 그것은 SameSite 속성으로 막습니다.
 */
@Component
@RequiredArgsConstructor
public class CookieFactory {

    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";

    private final AuthProperties authProperties;

    /**
     * 액세스 토큰 쿠키를 만듭니다.
     *
     * 경로가 / 라 모든 요청에 실려 나갑니다. 어느 서비스를 부르든 인증이 필요하기 때문입니다.
     */
    public ResponseCookie accessToken(String value, Instant expiresAt) {
        return base(ACCESS_TOKEN, value, "/", secondsUntil(expiresAt)).build();
    }

    /**
     * 리프레시 토큰 쿠키를 만듭니다.
     *
     * 경로를 좁게 둡니다.
     * 이 값은 갱신과 로그아웃에서만 쓰이므로 장소 조회 같은 평범한 요청에는 붙을 이유가 없습니다.
     * 붙지 않으면 그만큼 노출되는 자리가 줄어듭니다.
     */
    public ResponseCookie refreshToken(String value, Instant expiresAt) {
        return base(REFRESH_TOKEN, value,
                authProperties.cookie().refreshPath(), secondsUntil(expiresAt)).build();
    }

    /**
     * 로그아웃할 때 쿠키를 지웁니다.
     *
     * 값을 비우고 수명을 0 으로 두면 브라우저가 즉시 버립니다.
     *
     * 만들 때와 같은 경로를 지정해야 합니다.
     * 경로가 다르면 브라우저가 다른 쿠키로 보아 원래 것이 그대로 남습니다.
     */
    public ResponseCookie expireAccessToken() {
        return base(ACCESS_TOKEN, "", "/", 0).build();
    }

    public ResponseCookie expireRefreshToken() {
        return base(REFRESH_TOKEN, "", authProperties.cookie().refreshPath(), 0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value,
                                                      String path, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                // 스크립트가 읽지 못하게 합니다. 이 방식의 핵심입니다.
                .httpOnly(true)

                // https 에서만 보냅니다.
                //
                // 프로파일마다 값이 다릅니다.
                // 로컬은 http 라 켜면 쿠키가 실리지 않습니다.
                // 브라우저는 localhost 를 예외로 두지만 curl 과 Postman 은 그렇지 않아
                // 개발 중 요청을 직접 보낼 때 막힙니다.
                .secure(authProperties.cookie().secure())

                // 다른 사이트에서 시작된 요청에는 붙지 않습니다.
                //
                // 판정은 스킴과 도메인만 보고 포트는 보지 않습니다.
                // 그래서 로컬에서 프론트가 5173, 게이트웨이가 8080 이어도 같은 사이트로 취급되어
                // 이 값을 그대로 써도 동작합니다.
                // 배포에서는 nginx 가 프론트와 API 를 같은 도메인으로 서빙하므로 문제가 없습니다.
                .sameSite("Strict")

                .path(path)
                .maxAge(Duration.ofSeconds(maxAgeSeconds));

        // 지정하지 않으면 요청한 호스트에만 적용됩니다.
        // 하위 도메인이 여럿일 때만 값이 필요합니다.
        String domain = authProperties.cookie().domain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        return builder;
    }

    // 만료 시각을 남은 초로 바꿉니다.
    // 토큰의 수명과 쿠키의 수명을 따로 계산하면 두 값이 어긋날 수 있어 하나에서 끌어옵니다.
    private long secondsUntil(Instant expiresAt) {
        long seconds = Duration.between(Instant.now(), expiresAt).getSeconds();
        return Math.max(seconds, 0);
    }
}

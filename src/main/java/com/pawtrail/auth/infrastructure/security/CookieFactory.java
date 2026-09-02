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
    public static final String OAUTH_STATE = "oauth_state";

    // 소셜 로그인 상태 쿠키가 실려 나갈 경로임
    //
    // 설정이 아니라 상수로 두는 것은 이 값이 OAuthController 의 요청 경로와 같아야 하고
    // 그쪽이 코드이기 때문임
    // 리프레시 토큰 경로가 설정에 있는 것과 갈리지만, 그 값은 이미 설정에 있던 것이라 그대로 둠
    private static final String OAUTH_STATE_PATH = "/api/v1/auth/oauth";

    // 다른 사이트에서 시작된 요청에 쿠키를 실을지 정하는 값임
    //
    // 토큰은 Strict 임
    //   우리 사이트 안에서만 오가므로 가장 좁게 두어도 아무것도 막히지 않음
    //   이 값을 쓸 수 있다는 것이 액세스 토큰을 쿠키에 둔 근거 중 하나였음
    //
    // 상태 쿠키만 Lax 임
    //   콜백은 제공자가 우리 주소로 브라우저를 보내는 요청이라
    //   브라우저가 "다른 사이트에서 시작된 이동" 으로 보고 Strict 쿠키를 안 실어 보냄
    //   그러면 대조할 값이 도착하지 않아 정상 로그인까지 막힘
    //
    //   Lax 는 다른 사이트에서 온 최상위 GET 이동에만 쿠키를 실음
    //   콜백이 정확히 그 형태이고, 요청 위조로 악용되는 것은
    //   POST 나 화면 안에 끼워 넣는 요청이라 그쪽은 여전히 막힘
    //
    //   막으려던 것도 그대로 막힘
    //   공격자가 자기 콜백 주소를 남에게 열게 해도 그 브라우저에는 그 값이 없음
    private static final String SAME_SITE_TOKEN = "Strict";
    private static final String SAME_SITE_OAUTH_STATE = "Lax";

    // 상태 쿠키의 수명임
    //
    // 저장소에 두는 항목의 수명과 같아야 함
    // 쿠키가 먼저 사라지면 저장소에는 값이 남아 있는데 대조할 것이 없어 실패하고,
    // 저장소가 먼저 사라지면 쿠키만 남아 다음 로그인까지 따라다님
    // 두 값이 갈리면 "제공자 화면에서 오래 머물면 가끔 실패한다" 로만 나타남
    private static final long OAUTH_STATE_MAX_AGE_SECONDS = 300;

    private final AuthProperties authProperties;

    /**
     * 액세스 토큰 쿠키를 만듭니다.
     *
     * 경로가 / 라 모든 요청에 실려 나갑니다. 어느 서비스를 부르든 인증이 필요하기 때문입니다.
     */
    public ResponseCookie accessToken(String value, Instant expiresAt) {
        return base(ACCESS_TOKEN, value, "/", secondsUntil(expiresAt), SAME_SITE_TOKEN).build();
    }

    /**
     * 리프레시 토큰 쿠키를 만듭니다.
     *
     * 경로를 좁게 둡니다.
     * 이 값은 갱신과 로그아웃에서만 쓰이므로 장소 조회 같은 평범한 요청에는 붙을 이유가 없습니다.
     * 붙지 않으면 그만큼 노출되는 자리가 줄어듭니다.
     */
    public ResponseCookie refreshToken(String value, Instant expiresAt) {
        return base(REFRESH_TOKEN, value, authProperties.cookie().refreshPath(),
                secondsUntil(expiresAt), SAME_SITE_TOKEN).build();
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
        return base(ACCESS_TOKEN, "", "/", 0, SAME_SITE_TOKEN).build();
    }

    public ResponseCookie expireRefreshToken() {
        return base(REFRESH_TOKEN, "", authProperties.cookie().refreshPath(), 0, SAME_SITE_TOKEN).build();
    }

    /**
     * 소셜 로그인 상태 쿠키를 만듭니다.
     *
     * 인가를 시작한 브라우저와 되돌아온 브라우저가 같은지 확인하는 값입니다.
     *
     * 저장소에만 두면 "우리가 발급한 값인가" 까지만 확인됩니다.
     * 공격자가 자기 계정으로 인가를 시작해 얻은 콜백 주소를 남에게 열게 하면
     * 그 사람의 브라우저에 공격자 계정의 쿠키가 심깁니다.
     * 시작할 때 브라우저에 남긴 값과 대조하면 그 흐름이 걸러집니다.
     *
     * 경로를 좁게 둡니다.
     * 인가와 콜백에서만 쓰이므로 다른 요청에 붙을 이유가 없습니다.
     */
    public ResponseCookie oauthState(String value) {
        return base(OAUTH_STATE, value, OAUTH_STATE_PATH,
                OAUTH_STATE_MAX_AGE_SECONDS, SAME_SITE_OAUTH_STATE).build();
    }

    /**
     * 상태 쿠키를 지웁니다.
     *
     * 콜백이 끝나면 성공이든 실패든 반드시 부릅니다.
     * 남겨 두면 다음 로그인 때 옛 값이 실려 와 그때의 상태와 어긋납니다.
     */
    public ResponseCookie expireOAuthState() {
        return base(OAUTH_STATE, "", OAUTH_STATE_PATH, 0, SAME_SITE_OAUTH_STATE).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path,
                                                      long maxAgeSeconds, String sameSite) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                // 스크립트가 읽지 못하게 함. 이 방식의 핵심임
                .httpOnly(true)

                // https 에서만 보냅니다.
                //
                // 프로파일마다 값이 다릅니다.
                // 로컬은 http 라 켜면 쿠키가 실리지 않음
                // 브라우저는 localhost 를 예외로 두지만 curl 과 Postman 은 그렇지 않아
                // 개발 중 요청을 직접 보낼 때 막힙니다.
                .secure(authProperties.cookie().secure())

                // 다른 사이트에서 시작된 요청에 이 쿠키를 실을지를 정함
                //
                // 판정은 스킴과 도메인만 보고 포트는 보지 않음
                // 그래서 로컬에서 프론트가 5173, 게이트웨이가 8080 이어도 같은 사이트로 취급되어
                // 토큰 쿠키는 가장 좁은 값으로 두어도 동작함
                // 배포에서는 nginx 가 프론트와 API 를 같은 도메인으로 서빙하므로 문제가 없음
                //
                // 쿠키마다 값이 다른 이유는 위 상수 설명에 있음
                .sameSite(sameSite)

                .path(path)
                .maxAge(Duration.ofSeconds(maxAgeSeconds));

        // 지정하지 않으면 요청한 호스트에만 적용됩니다.
        // 하위 도메인이 여럿일 때만 값이 필요함
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

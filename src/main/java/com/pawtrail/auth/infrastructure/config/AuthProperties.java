package com.pawtrail.auth.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이 서비스의 보안 설정입니다.
 * config 저장소의 auth-service.yml 에서 내려옵니다.
 *
 * permitAll 목록은 게이트웨이의 app.gateway.permit-all 과 짝입니다.
 * 게이트웨이는 그 경로에 인증 헤더를 넣지 않고 통과시키므로,
 * 이 서비스가 같은 경로를 열어 두지 않으면 로그인과 회원가입이 401 로 막힙니다.
 *
 * 두 곳에 같은 목록이 존재하는 것은 감수한 것입니다.
 * 게이트웨이는 공통 모듈을 쓰지 않아 상수를 공유할 수 없기 때문이며,
 * 토픽 이름을 공통에 두지 않기로 한 것과 같은 성격입니다.
 * 다만 실패하는 방향이 안전합니다. 어느 한쪽에 빠지면 401 이 나서 드러나고,
 * 양쪽이 동시에 잘못 열리는 일은 없습니다.
 *
 * @param permitAll 인증 없이 통과시킬 경로 패턴입니다.
 * @param cookie    토큰을 담을 쿠키의 속성입니다.
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(List<String> permitAll, CookieOptions cookie) {

    /**
     * 목록이 비어 있으면 기동을 막습니다.
     *
     * 빈 목록은 "열어 둘 경로가 없다" 가 아니라 설정을 못 받았다는 뜻입니다.
     * 로그인과 회원가입까지 인증을 요구하게 되어 아무도 로그인할 수 없는 상태가 되는데,
     * 그때 나타나는 증상이 "모든 요청이 401" 이라 원인을 설정 누락으로 짚기 어렵습니다.
     *
     * 게이트웨이의 GatewayAuthProperties 가 같은 장치를 두고 있습니다.
     */
    public AuthProperties {
        if (permitAll == null || permitAll.isEmpty()) {
            throw new IllegalStateException(
                    "app.auth.permit-all 이 비어 있습니다. config 저장소의 auth-service.yml 을 확인하십시오");
        }
        if (cookie == null) {
            throw new IllegalStateException(
                    "app.auth.cookie 가 비어 있습니다. config 저장소의 auth-service.yml 을 확인하십시오");
        }
    }

    /**
     * 쿠키 속성입니다.
     *
     * @param secure       HTTPS 에서만 보낼지 여부입니다.
     *                     * 프로파일마다 다릅니다
     *                       로컬은 http 라 켜면 쿠키가 실리지 않습니다
     *                       브라우저는 localhost 를 예외로 두지만 curl 과 Postman 은 그렇지 않아
     *                       개발 중 API 를 직접 부를 때 막힙니다
     * @param domain       쿠키를 적용할 도메인입니다. 비우면 요청한 호스트에만 적용됩니다.
     * @param refreshPath  리프레시 토큰 쿠키의 경로입니다.
     *                     * 액세스 토큰과 달리 좁게 두는 이유
     *                       이 값이 /api/v1/auth 이면 갱신과 로그아웃 요청에만 실려 나갑니다
     *                       장소 조회 같은 평범한 요청에는 아예 붙지 않아 노출 면적이 줄어듭니다
     */
    public record CookieOptions(boolean secure, String domain, String refreshPath) {
    }
}

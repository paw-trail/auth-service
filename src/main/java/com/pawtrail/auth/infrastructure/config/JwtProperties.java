package com.pawtrail.auth.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토큰 발급에 쓰는 설정입니다.
 * config 저장소의 auth-service.yml 에서 내려옵니다.
 *
 * 게이트웨이의 JwtProperties 와 짝을 이룹니다.
 * 그쪽은 공개키로 검증하고 이쪽은 개인키로 서명하며, claim 이름은 양쪽이 같아야 합니다.
 *
 * @param privateKeyB64 서명에 쓰는 개인키입니다. PEM 을 Base64 로 한 번 더 감싼 값입니다.
 * @param issuer        토큰의 iss 에 들어갈 값입니다.
 * @param accessExpiry  액세스 토큰이 살아 있는 시간입니다.
 * @param refreshExpiry 리프레시 토큰이 살아 있는 시간입니다.
 * @param claim         토큰 안에 값을 넣을 이름입니다.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String privateKeyB64,
                            String issuer,
                            Duration accessExpiry,
                            Duration refreshExpiry,
                            ClaimNames claim) {

    /**
     * 개인키를 PEM 그대로가 아니라 Base64 로 감싸 받는 이유
     *
     * PEM 은 여러 줄이라 환경변수에 넣으면 개행 처리가 셸과 도구마다 다릅니다.
     * .env 에서는 \n 이스케이프가 먹지 않고, 컨테이너와 로컬의 동작이 갈립니다.
     * 한 줄짜리 값으로 만들면 그 문제가 통째로 사라집니다.
     *
     * 공개키는 config 저장소에 PEM 원본으로 두는데, 그쪽은 파일이라 여러 줄이 자연스럽습니다.
     * 형식이 다른 것은 저장되는 곳이 다르기 때문입니다.
     */
    public JwtProperties {
        if (privateKeyB64 == null || privateKeyB64.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.private-key-b64 가 비어 있습니다. "
                            + "AUTH_JWT_PRIVATE_KEY_B64 환경변수가 설정되었는지 확인하십시오");
        }
        if (accessExpiry == null || refreshExpiry == null) {
            throw new IllegalStateException(
                    "app.jwt.access-expiry 와 refresh-expiry 가 필요합니다. "
                            + "config 저장소의 auth-service.yml 을 확인하십시오");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.issuer 가 비어 있습니다. config 저장소의 auth-service.yml 을 확인하십시오");
        }
        if (claim == null) {
            throw new IllegalStateException(
                    "app.jwt.claim 이 비어 있습니다. 게이트웨이 설정과 같은 이름을 지정해야 합니다");
        }
    }

    /**
     * 토큰 안에서 값이 들어갈 이름입니다.
     *
     * 게이트웨이가 같은 이름으로 꺼내므로 양쪽이 어긋나면 안 됩니다.
     * 어긋나면 서명은 통과하는데 값이 비어 401 이 나며, 그 응답만으로는 원인이 드러나지 않습니다.
     *
     * @param accountId 사용자 식별자를 넣을 이름입니다. 표준 항목인 sub 를 씁니다.
     * @param role      권한을 넣을 이름입니다. 표준에 없어 우리가 정한 이름입니다.
     * @param type      토큰의 종류를 넣을 이름입니다.
     *                  * 액세스와 리프레시가 담는 내용이 같고 수명만 달라 이 값이 없으면 구분할 수 없습니다
     *                    구분하지 않으면 리프레시 토큰을 액세스 쿠키에 넣어 오래 쓸 수 있고,
     *                    액세스 토큰을 갱신 요청에 보내면 복제로 오인되어 계정이 통째로 잠깁니다
     */
    public record ClaimNames(String accountId, String role, String type) {

        /**
         * 두 이름이 비어 있으면 기동을 막습니다.
         *
         * 여기서 막지 않으면 기동은 정상이고 로그인할 때가 되어서야 실패합니다.
         * 그 시점의 예외는 토큰을 만드는 자리에서 나므로 설정 문제라는 것이 드러나지 않습니다.
         *
         * 빈 문자열이 특히 위험합니다.
         * null 은 토큰 생성기가 거부하지만 빈 문자열은 그대로 통과해
         * 이름 없는 항목이 든 토큰이 만들어지고, 게이트웨이가 값을 못 찾아 401 만 내보냅니다.
         */
        public ClaimNames {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalStateException(
                        "app.jwt.claim.account-id 가 비어 있습니다. "
                                + "게이트웨이의 app.jwt.claim.account-id 와 같은 값이어야 합니다");
            }
            if (role == null || role.isBlank()) {
                throw new IllegalStateException(
                        "app.jwt.claim.role 이 비어 있습니다. "
                                + "게이트웨이의 app.jwt.claim.role 과 같은 값이어야 합니다");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalStateException(
                        "app.jwt.claim.type 이 비어 있습니다. "
                                + "게이트웨이의 app.jwt.claim.type 과 같은 값이어야 합니다");
            }
        }
    }

}

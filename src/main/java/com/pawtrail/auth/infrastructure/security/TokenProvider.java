package com.pawtrail.auth.infrastructure.security;

import com.pawtrail.auth.infrastructure.config.JwtProperties;
import com.pawtrail.common.enums.Role;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * 토큰을 만드는 곳입니다.
 *
 * 액세스 토큰과 리프레시 토큰을 모두 JWT 로 만듭니다.
 * 리프레시를 임의의 문자열로 두지 않는 이유는 둘입니다.
 *   jti 가 필요합니다. refresh_token_log.token_id 와 Redis 키 refresh:{jti} 가 그 값을 씁니다.
 *   만료를 토큰 자체가 들고 있습니다. Redis 가 날아가도 만료된 토큰은 통하지 않습니다.
 *
 * 서명은 RS256 으로 고정됩니다.
 * 게이트웨이의 검증기가 공개키로 만들어져 그 알고리즘만 받아들이므로,
 * 여기서 다른 것을 쓰면 모든 요청이 401 이 됩니다.
 */
@Component
@RequiredArgsConstructor
public class TokenProvider {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    /**
     * 발급한 토큰과 그 부속 정보입니다.
     *
     * jti 와 만료 시각을 함께 돌려주는 이유는 부르는 쪽이 그것들을 저장해야 하기 때문입니다.
     * 토큰 문자열을 다시 파싱해서 꺼내게 하면 방금 만든 값을 되읽는 셈이 됩니다.
     *
     * @param value     쿠키에 담을 토큰 문자열입니다.
     * @param tokenId   JWT 의 jti 입니다. Redis 키와 이력 테이블이 이 값을 씁니다.
     * @param expiresAt 만료 시각입니다. 쿠키의 수명과 Redis TTL 을 여기에 맞춥니다.
     */
    public record IssuedToken(String value, String tokenId, Instant expiresAt) {
    }

    /**
     * 액세스 토큰을 만듭니다.
     *
     * 게이트웨이가 sub 와 role 을 꺼내 헤더로 옮기므로 그 두 값이 반드시 들어가야 합니다.
     * 이름은 설정에서 받습니다. 양쪽이 어긋나면 서명은 통과하는데 값이 비어 401 이 납니다.
     */
    public IssuedToken issueAccessToken(UUID accountId, Role role) {
        return issue(accountId, role, jwtProperties.accessExpiry().getSeconds());
    }

    /**
     * 리프레시 토큰을 만듭니다.
     *
     * 액세스 토큰과 같은 내용을 담되 수명만 깁니다.
     * 담는 내용을 줄이지 않는 이유는 갱신할 때 이 토큰만으로 새 액세스 토큰을 만들 수 있어야 하기 때문입니다.
     */
    public IssuedToken issueRefreshToken(UUID accountId, Role role) {
        return issue(accountId, role, jwtProperties.refreshExpiry().getSeconds());
    }

    private IssuedToken issue(UUID accountId, Role role, long expirySeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expirySeconds);

        // jti 는 우리가 만들어 넣습니다.
        // 라이브러리가 자동으로 채워 주지 않고, 무엇보다 그 값을 밖에서 알아야 합니다.
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(tokenId)
                // 아래 두 이름은 게이트웨이 설정과 같아야 합니다.
                // claim(name, value) 로 넣으므로 sub 도 같은 방식으로 처리됩니다.
                .claim(jwtProperties.claim().accountId(), accountId.toString())
                .claim(jwtProperties.claim().role(), role.name())
                .build();

        // 알고리즘을 헤더에 명시합니다.
        //
        // 지정하지 않으면 라이브러리가 키에서 추론하는데,
        // 우리는 검증 쪽이 RS256 만 받도록 고정해 두었으므로 발급도 명시해 짝을 분명히 합니다.
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, tokenId, expiresAt);
    }
}

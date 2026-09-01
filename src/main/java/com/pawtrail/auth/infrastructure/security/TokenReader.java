package com.pawtrail.auth.infrastructure.security;

import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.infrastructure.config.JwtProperties;
import com.pawtrail.common.enums.Role;
import com.pawtrail.common.exception.CustomException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/**
 * 들어온 토큰을 읽는 곳입니다.
 *
 * TokenProvider 와 짝을 이룹니다. 그쪽은 만들고 이쪽은 읽습니다.
 *
 * 한 클래스로 합치지 않은 이유는 두 일의 성격이 다르기 때문입니다.
 * 발급은 이 서비스만 하지만 검증은 게이트웨이도 하는 일이고,
 * TokenProvider 라는 이름이 "주는 쪽" 을 뜻해 읽기까지 담기에는 맞지 않습니다.
 *
 * 검증기는 JwtEncoderConfig 가 만들어 줍니다.
 * 그 클래스가 개인키를 이미 읽고 있어 공개키를 거기서 뽑아내며,
 * 키를 읽는 코드가 두 군데로 갈리면 어긋났을 때 서명은 되는데 검증만 실패합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenReader {

    private final JwtDecoder jwtDecoder;
    private final JwtProperties jwtProperties;

    /**
     * 리프레시 토큰에서 꺼낸 값입니다.
     *
     * @param accountId 토큰이 가리키는 계정입니다.
     * @param role      권한입니다. 갱신할 때 새 토큰에 다시 담습니다.
     * @param tokenId   JWT 의 jti 입니다. 저장소 키와 이력 테이블이 이 값을 씁니다.
     * @param issuedAt  발급 시각입니다. 계정의 토큰 폐기 기준 시각과 비교합니다.
     * @param expiresAt 만료 시각입니다. 유예 항목의 수명을 정할 때 씁니다.
     */
    public record RefreshTokenInfo(UUID accountId, Role role, String tokenId,
                                   LocalDateTime issuedAt, Instant expiresAt) {
    }

    /**
     * 리프레시 토큰을 확인하고 안의 값을 꺼냅니다.
     *
     * 확인하는 것은 서명, 만료, 토큰의 종류입니다.
     * 그 토큰이 아직 살아 있는 것인지는 여기서 보지 않습니다.
     * 그 판단은 저장소에 물어야 하는 일이고 이 클래스는 저장소를 모릅니다.
     *
     * 실패 사유를 구분해 돌려주지 않습니다.
     * 서명 불일치와 만료와 종류 불일치는 부르는 쪽이 할 일이 모두 같고,
     * 응답에 사유를 적으면 토큰을 맞춰 보는 데 단서가 됩니다.
     * 다만 원인은 로그에 남깁니다.
     *
     * @throws CustomException 읽을 수 없는 토큰입니다.
     */
    public RefreshTokenInfo readRefreshToken(String token) {

        Jwt jwt;
        try {
            // 서명과 만료를 확인합니다.
            // 만료된 토큰은 여기서 걸리므로 아래에서 다시 보지 않습니다.
            jwt = jwtDecoder.decode(token);
        } catch (Exception e) {
            log.debug("리프레시 토큰을 읽지 못했습니다. reason={}", e.getMessage());
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 토큰의 종류를 봅니다.
        //
        // 이 검사가 없으면 액세스 토큰을 여기로 보낼 수 있습니다.
        // 서명은 통과하는데 그 jti 는 저장소에 없으므로 복제로 판정되어
        // 계정의 토큰이 전부 폐기됩니다.
        // 액세스 토큰은 모든 요청에 실려 나가 리프레시보다 노출이 크므로
        // 그것을 주운 사람이 계정을 잠글 수 있게 됩니다.
        //
        // 종류가 아예 없는 토큰도 여기서 걸립니다.
        // typ 을 넣기 전에 발급된 토큰이며 더는 받지 않습니다.
        String type = jwt.getClaimAsString(jwtProperties.claim().type());
        if (!TokenType.REFRESH.matches(type)) {
            log.debug("리프레시 토큰이 아닙니다. type={}", type);
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        try {
            String tokenId = jwt.getId();
            String accountId = jwt.getClaimAsString(jwtProperties.claim().accountId());
            String role = jwt.getClaimAsString(jwtProperties.claim().role());
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();

            // 하나라도 비어 있으면 우리가 만든 토큰이 아니거나 설정이 어긋난 것입니다.
            //
            // 설정이 어긋나는 경우가 특히 조용합니다.
            // claim 이름이 게이트웨이와 달라지면 서명은 통과하는데 값만 비는데,
            // 그대로 두면 아래에서 NullPointerException 이 나 500 이 됩니다.
            if (tokenId == null || accountId == null || role == null
                    || issuedAt == null || expiresAt == null) {
                log.warn("리프레시 토큰에 필요한 값이 없습니다. "
                        + "app.jwt.claim 설정이 발급 때와 같은지 확인하십시오");
                throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
            }

            return new RefreshTokenInfo(
                    UUID.fromString(accountId),
                    Role.valueOf(role),
                    tokenId,
                    // 이 서비스는 시각을 LocalDateTime 으로 다룹니다.
                    // 컨테이너의 표준시가 Asia/Seoul 로 맞춰져 있어 기준이 한 곳입니다.
                    LocalDateTime.ofInstant(issuedAt, ZoneId.systemDefault()),
                    expiresAt);

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            // 식별자 형식이 틀렸거나 권한 이름이 우리 열거형에 없는 경우입니다.
            log.warn("리프레시 토큰의 값을 해석하지 못했습니다. reason={}", e.getMessage());
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
    }
}

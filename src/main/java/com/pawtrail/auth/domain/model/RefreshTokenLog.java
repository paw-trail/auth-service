package com.pawtrail.auth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 리프레시 토큰 발급 이력입니다. 토큰 자체는 여기에 없습니다.
 *
 * 실제 토큰은 Redis 의 refresh:{jti} 에 있습니다.
 * 로그아웃할 때 즉시 무효화해야 하는데 데이터베이스로는 만료까지 기다려야 하기 때문입니다.
 * 이 엔티티는 "언제 어디서 발급됐는가" 만 남겨 장애 조사와 이상 접속 확인에 씁니다.
 *
 * BaseEntity 를 상속하지 않습니다.
 * createdAt 이 issuedAt 과, deletedAt 이 revokedAt 과 같은 값이 되어
 * 여섯 컬럼 중 절반이 중복되고, accountId 가 누구인지 이미 담고 있어
 * createdBy 도 필요하지 않습니다.
 * 사람이 만들고 고치는 데이터가 아니라 시스템이 쌓는 로그이므로
 * 공통 모듈의 outbox, processed_event 와 같은 부류입니다.
 */
@Entity
@Table(name = "refresh_token_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenLog {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // 연관관계를 걸지 않고 식별자만 둠
    //
    // 로그 테이블이 계정 삭제를 막는 구조를 만들지 않기 위함이고,
    // 계정은 지우지 않고 status 로만 표시하므로 참조가 끊길 일도 없음
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    // JWT 의 jti 임
    // Redis 키 refresh:{jti} 와 같은 값이며 이 값으로 이력과 실제 토큰이 이어짐
    @Column(name = "token_id", nullable = false, updatable = false, length = 36)
    private String tokenId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    // 로그아웃이나 강제 만료로 회수된 시각임
    // null 이면 아직 유효함
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    // 요청이 어느 주소에서 왔는지임
    //
    // * 무엇으로 채울지는 아직 정해지지 않았음
    //   앞에 nginx 와 게이트웨이가 있어 서블릿의 getRemoteAddr 은 게이트웨이 주소를 돌려줌
    //   원래 주소는 X-Forwarded-For 헤더에 있으며 그것이 실제로 도착하는지
    //   로그인 컨트롤러에서 확인한 뒤 정하기로 했음
    //
    // IPv6 는 IPv4 매핑 표기까지 포함해 최대 45 자임
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    // 어떤 브라우저에서 왔는지임
    // 게이트웨이가 지우는 헤더는 X-User-Id 와 X-User-Role 둘뿐이라 이 값은 그대로 도착함
    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    private RefreshTokenLog(UUID accountId, String tokenId,
                            LocalDateTime issuedAt, LocalDateTime expiresAt,
                            String ipAddress, String userAgent) {
        this.accountId = accountId;
        this.tokenId = tokenId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    /**
     * 발급 이력을 남깁니다.
     *
     * @param tokenId  JWT 의 jti 이며 Redis 키와 같은 값입니다.
     * @param ipAddress null 을 허용합니다. 아직 무엇을 넣을지 정해지지 않았습니다.
     */
    public static RefreshTokenLog issue(UUID accountId, String tokenId,
                                        LocalDateTime issuedAt, LocalDateTime expiresAt,
                                        String ipAddress, String userAgent) {
        return new RefreshTokenLog(accountId, tokenId, issuedAt, expiresAt, ipAddress, userAgent);
    }

    /**
     * 회수 시각을 남깁니다. 로그아웃이나 강제 만료 때 부릅니다.
     *
     * 이미 회수된 이력은 그대로 둡니다.
     * 같은 토큰으로 로그아웃이 두 번 들어와도 처음 시각이 유지되어야 하기 때문입니다.
     */
    public void revoke(LocalDateTime revokedAt) {
        if (this.revokedAt != null) {
            return;
        }
        this.revokedAt = revokedAt;
    }

    // 아직 회수되지 않았는지 봄
    public boolean isActive() {
        return this.revokedAt == null;
    }
}

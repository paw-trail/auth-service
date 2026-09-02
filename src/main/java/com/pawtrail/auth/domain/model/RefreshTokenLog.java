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
 * 리프레시 토큰 발급·회수 이력입니다. 토큰 자체는 여기에 없습니다.
 *
 * 실제 토큰은 Redis 의 refresh:{jti} 에 있습니다.
 * 로그아웃할 때 즉시 무효화해야 하는데 데이터베이스로는 만료까지 기다려야 하기 때문입니다.
 * 이 엔티티는 "언제 어디서 발급됐고 언제 회수됐는가" 만 남겨
 * 장애 조사와 이상 접속 확인에 씁니다.
 *
 * 갱신할 때마다 행이 하나씩 늘어납니다.
 * 리프레시 토큰을 함께 새로 발급하므로 옛 토큰의 이력을 덮어쓰지 않고 회수 시각만 남깁니다.
 * 한 번의 로그인이 여러 행으로 흩어지는데 그 행들은 loginId 로 묶입니다.
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

    // 로그인 한 번을 묶는 값임
    //
    // 로그인할 때 만들고 갱신할 때 그대로 물려줌
    // 이 값이 없으면 기기가 둘 이상일 때 어느 행이 어느 로그인에서 시작됐는지 알 수 없음
    //
    //   행  loginId  issuedAt  revokedAt
    //   X   A        09:00     09:30      <- 폰으로 로그인
    //   Y   A        09:30      10:00
    //   Z   A        10:00     null       <- 폰의 현재 토큰
    //   P   B        14:00     14:30      <- PC 로 로그인
    //   Q   B        14:30     null       <- PC 의 현재 토큰
    //
    // 마지막 행의 issuedAt 은 로그인 시각이 아니라 마지막 갱신 시각임
    // 로그인 시각은 그 사슬의 첫 행에 있음
    //
    // 인증 판단에는 쓰이지 않음
    // 로그인, 갱신, 재사용 탐지, 토큰 폐기 어디에서도 이 값을 보지 않으며
    // 쓰기만 하고 읽는 것은 사람이 이 표를 볼 때뿐임
    // 성격이 Zipkin 의 traceId 에 가까움
    //
    // 이름을 sessionId 로 두지 않은 것은 의도임
    // 서버 세션을 도입했다는 뜻으로 읽히나 이 서비스는 여전히 토큰 기반이며
    // 이 값은 로그를 묶는 것 외에 하는 일이 없음
    @Column(name = "login_id", nullable = false, updatable = false)
    private UUID loginId;

    // JWT 의 jti 임
    // Redis 키 refresh:{jti} 와 같은 값이며 이 값으로 이력과 실제 토큰이 이어짐
    @Column(name = "token_id", nullable = false, updatable = false, length = 36)
    private String tokenId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    // 회수된 시각임
    // null 이면 아직 유효함
    //
    // 채워지는 경로가 셋임
    //   로그아웃    그 토큰 하나
    //   갱신        교체된 옛 토큰
    //   일괄 폐기    그 계정의 아직 유효한 토큰 전부
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

    private RefreshTokenLog(UUID accountId, UUID loginId, String tokenId,
                            LocalDateTime issuedAt, LocalDateTime expiresAt,
                            String ipAddress, String userAgent) {
        this.accountId = accountId;
        this.loginId = loginId;
        this.tokenId = tokenId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    /**
     * 발급 이력을 남깁니다.
     *
     * 로그인할 때와 갱신할 때 모두 이것으로 만듭니다.
     * 갱신이면 loginId 에 옛 행의 값을 그대로 넘겨 같은 사슬로 이어 줍니다.
     *
     * @param loginId  로그인 한 번을 묶는 값입니다. 로그인이면 새로 만들고, 갱신이면 물려받습니다.
     * @param tokenId  JWT 의 jti 이며 Redis 키와 같은 값입니다.
     * @param ipAddress null 을 허용합니다. 아직 무엇을 넣을지 정해지지 않았습니다.
     */
    public static RefreshTokenLog issue(UUID accountId, UUID loginId, String tokenId,
                                        LocalDateTime issuedAt, LocalDateTime expiresAt,
                                        String ipAddress, String userAgent) {
        return new RefreshTokenLog(accountId, loginId, tokenId,
                issuedAt, expiresAt, ipAddress, userAgent);
    }

    /**
     * 회수 시각을 남깁니다. 로그아웃, 갱신으로 교체될 때, 일괄 폐기 때 부릅니다.
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

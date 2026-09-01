package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.model.RefreshTokenLog;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 스프링 데이터가 구현체를 만들어 주는 인터페이스입니다.
 *
 * 도메인은 RefreshTokenLogRepository 만 알고 이 파일은 보지 않습니다.
 */
public interface JpaRefreshTokenLogRepository extends JpaRepository<RefreshTokenLog, UUID> {

    Optional<RefreshTokenLog> findByTokenId(String tokenId);

    List<RefreshTokenLog> findByAccountIdOrderByIssuedAtDesc(UUID accountId);

    /**
     * 한 계정의 아직 회수되지 않은 이력을 한 번에 회수 표시합니다.
     *
     * flushAutomatically 를 켜는 이유
     *   이 쿼리가 건드리는 표는 refresh_token_log 인데 부르기 직전에 고친 것은 account 입니다.
     *   Hibernate 는 쿼리가 건드리는 표를 보고 flush 여부를 정하므로
     *   account 의 변경이 아직 반영되지 않은 채로 이 쿼리가 나갈 수 있습니다.
     *
     * clearAutomatically 를 켜지 않는 이유
     *   켜면 영속성 컨텍스트가 통째로 비워집니다.
     *   방금 고친 Account 가 준영속이 되어 그 변경이 사라지는데, 오류가 나지 않으므로
     *   "폐기했다고 로그는 찍혔는데 실제로는 안 바뀐" 상태가 됩니다.
     *   이 메서드 뒤에 이 표의 엔티티를 다시 읽는 곳이 없어 끄고 두어도 안전합니다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshTokenLog r set r.revokedAt = :revokedAt "
            + "where r.accountId = :accountId and r.revokedAt is null")
    int revokeAllActive(@Param("accountId") UUID accountId,
                        @Param("revokedAt") LocalDateTime revokedAt);
}

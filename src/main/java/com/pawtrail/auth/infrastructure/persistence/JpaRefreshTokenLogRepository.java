package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.model.RefreshTokenLog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 스프링 데이터가 구현체를 만들어 주는 인터페이스입니다.
 *
 * 도메인은 RefreshTokenLogRepository 만 알고 이 파일은 보지 않습니다.
 */
public interface JpaRefreshTokenLogRepository extends JpaRepository<RefreshTokenLog, UUID> {

    Optional<RefreshTokenLog> findByTokenId(String tokenId);

    List<RefreshTokenLog> findByAccountIdOrderByIssuedAtDesc(UUID accountId);
}

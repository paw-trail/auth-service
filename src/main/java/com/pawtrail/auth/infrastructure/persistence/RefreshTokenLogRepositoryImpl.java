package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.model.RefreshTokenLog;
import com.pawtrail.auth.domain.repository.RefreshTokenLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 스프링 데이터로 구현합니다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenLogRepositoryImpl implements RefreshTokenLogRepository {

    private final JpaRefreshTokenLogRepository jpaRefreshTokenLogRepository;

    @Override
    public RefreshTokenLog save(RefreshTokenLog refreshTokenLog) {
        return jpaRefreshTokenLogRepository.save(refreshTokenLog);
    }

    @Override
    public Optional<RefreshTokenLog> findByTokenId(String tokenId) {
        return jpaRefreshTokenLogRepository.findByTokenId(tokenId);
    }

    @Override
    public int revokeAllActive(UUID accountId, LocalDateTime revokedAt) {
        return jpaRefreshTokenLogRepository.revokeAllActive(accountId, revokedAt);
    }

    @Override
    public List<RefreshTokenLog> findByAccountIdOrderByIssuedAtDesc(UUID accountId) {
        return jpaRefreshTokenLogRepository.findByAccountIdOrderByIssuedAtDesc(accountId);
    }
}

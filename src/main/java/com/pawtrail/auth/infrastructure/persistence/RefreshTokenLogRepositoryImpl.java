package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.model.RefreshTokenLog;
import com.pawtrail.auth.domain.repository.RefreshTokenLogRepository;
import com.pawtrail.auth.infrastructure.persistence.jpa.RefreshTokenLogJpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 스프링 데이터로 구현합니다.
 *
 * 조회 수단을 둘 쓰게 되어도 파일은 이것 하나입니다.
 * RefreshTokenLogJpaRepository 와 JPAQueryFactory 를 함께 주입받아,
 * 단순한 조회는 앞의 것에 위임하고 동적 조건은 뒤의 것으로 짭니다.
 *
 * 이 서비스는 조회가 단순해 아직 QueryDSL 을 쓰지 않습니다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenLogRepositoryImpl implements RefreshTokenLogRepository {

    private final RefreshTokenLogJpaRepository refreshTokenLogJpaRepository;

    @Override
    public RefreshTokenLog save(RefreshTokenLog refreshTokenLog) {
        return refreshTokenLogJpaRepository.save(refreshTokenLog);
    }

    @Override
    public Optional<RefreshTokenLog> findByTokenId(String tokenId) {
        return refreshTokenLogJpaRepository.findByTokenId(tokenId);
    }

    @Override
    public int revokeAllActive(UUID accountId, LocalDateTime revokedAt) {
        return refreshTokenLogJpaRepository.revokeAllActive(accountId, revokedAt);
    }

    @Override
    public List<RefreshTokenLog> findByAccountIdOrderByIssuedAtDesc(UUID accountId) {
        return refreshTokenLogJpaRepository.findByAccountIdOrderByIssuedAtDesc(accountId);
    }
}

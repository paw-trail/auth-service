package com.pawtrail.auth.domain.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 리프레시 토큰을 보관하는 약속입니다.
 *
 * 이 인터페이스에는 Redis 라는 단어가 나오지 않습니다.
 * 무엇을 할 수 있는지만 적고 어떻게 하는지는 infrastructure 가 정합니다.
 * JPA 리포지터리를 나눈 것과 같은 규칙입니다.
 *
 * 왜 데이터베이스가 아니라 별도 저장소인가
 *
 * 로그아웃할 때 토큰을 즉시 못 쓰게 만들어야 하는데,
 * JWT 는 발급된 뒤에는 만료 시각이 될 때까지 유효합니다.
 * 그래서 발급한 토큰을 따로 적어 두고, 갱신 요청이 올 때
 * "그 토큰이 아직 목록에 있는가" 를 확인합니다.
 * 로그아웃은 그 기록을 지우는 것으로 끝납니다.
 *
 * refresh_token_log 테이블은 이것과 별개입니다.
 * 그쪽은 "언제 어디서 발급됐는가" 를 남기는 이력이고, 여기는 유효성 판단용입니다.
 */
public interface RefreshTokenStore {

    /**
     * 발급한 토큰을 기록합니다.
     *
     * @param tokenId JWT 의 jti 입니다.
     * @param accountId 그 토큰이 가리키는 계정입니다.
     * @param ttl 토큰의 남은 수명입니다. 이 시간이 지나면 기록이 저절로 사라집니다.
     */
    void save(String tokenId, UUID accountId, Duration ttl);

    /**
     * 아직 유효한 토큰인지 확인하고 계정을 돌려줍니다.
     *
     * 비어 있으면 만료됐거나 로그아웃된 것입니다.
     * 둘을 구분하지 않는 이유는 어느 쪽이든 다시 로그인해야 하기 때문입니다.
     */
    Optional<UUID> findAccountId(String tokenId);

    /**
     * 토큰을 회수합니다. 로그아웃이 이것을 부릅니다.
     */
    void delete(String tokenId);
}

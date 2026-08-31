package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.RefreshTokenStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 Redis 로 구현합니다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenStoreImpl implements RefreshTokenStore {

    // 키 앞에 붙는 이름입니다.
    //
    // Redis 는 하나의 저장소를 여러 용도가 나눠 쓰므로 접두사로 구분합니다.
    // 이 서비스에는 앞으로 pwreset, emailverify, oauth:state 도 생깁니다.
    private static final String KEY_PREFIX = "refresh:";

    // 값은 문자열만 담으므로 StringRedisTemplate 을 씁니다.
    //
    // RedisTemplate 은 기본 직렬화가 자바 직렬화라 값이 사람이 못 읽는 형태로 들어가고,
    // redis-cli 로 확인할 때 무엇이 들었는지 보이지 않습니다.
    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String tokenId, UUID accountId, Duration ttl) {
        // 수명을 함께 지정합니다.
        //
        // 만료된 기록을 지우는 작업을 따로 두지 않아도 되고,
        // 토큰 자체의 만료와 기록의 수명이 같아져 둘이 어긋나지 않습니다.
        redisTemplate.opsForValue().set(key(tokenId), accountId.toString(), ttl);
    }

    @Override
    public Optional<UUID> findAccountId(String tokenId) {
        String value = redisTemplate.opsForValue().get(key(tokenId));
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(value));
    }

    @Override
    public void delete(String tokenId) {
        redisTemplate.delete(key(tokenId));
    }

    private String key(String tokenId) {
        return KEY_PREFIX + tokenId;
    }
}

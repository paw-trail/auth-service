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

    // 키 앞에 붙는 이름임
    //
    // Redis 는 하나의 저장소를 여러 용도가 나눠 쓰므로 접두사로 구분함
    // 이 서비스에는 pwreset, emailverify, mailcooldown 이 이미 있고
    // 앞으로 oauth:state 도 생김
    private static final String KEY_PREFIX = "refresh:";

    // 방금 교체된 토큰을 담는 자리임
    //
    // 살아 있는 토큰과 접두사를 나누는 이유는 값의 뜻이 다르기 때문임
    // 위쪽은 계정 식별자를 담고 이쪽은 새로 발급한 토큰 문자열을 담음
    // 한 키에 두 뜻을 담으면 값을 꺼낼 때마다 어느 쪽인지 가려야 하고
    // redis-cli 로 들여다볼 때도 무엇이 무엇인지 보이지 않음
    private static final String GRACE_PREFIX = "refreshgrace:";

    // 값은 문자열만 담으므로 StringRedisTemplate 을 씀
    //
    // RedisTemplate 은 기본 직렬화가 자바 직렬화라 값이 사람이 못 읽는 형태로 들어가고
    // redis-cli 로 확인할 때 무엇이 들었는지 보이지 않음
    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String tokenId, UUID accountId, Duration ttl) {
        // 수명을 함께 지정함
        //
        // 만료된 기록을 지우는 작업을 따로 두지 않아도 되고
        // 토큰 자체의 만료와 기록의 수명이 같아져 둘이 어긋나지 않음
        redisTemplate.opsForValue().set(key(tokenId), accountId.toString(), ttl);
    }

    @Override
    public boolean saveGraceIfAbsent(String tokenId, String replacementToken, Duration ttl) {

        // "없을 때만 넣는다" 를 Redis 안에서 한 번에 처리함
        //
        // 같은 순간에 요청이 여럿 들어와도 참을 받는 것은 하나뿐임
        // 이 자리에서 갈리므로 뒤따르는 교체 작업은 한 요청만 수행함
        //
        // 메일 발송 제한에서 쓴 것과 같은 방법임
        // 거기서도 "묻고 나서 기록" 을 "자리를 잡는다" 로 바꿔 경합을 없앴음
        //
        // 담는 값이 리프레시 토큰 문자열임
        // 사용자의 쿠키에 이미 들어 있는 값이라 여기에 두는 것으로 새로 노출되지는 않고
        // 수명도 짧아 오래 남지 않음
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(graceKey(tokenId), replacementToken, ttl);

        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public Optional<String> findGrace(String tokenId) {

        // 여기서는 지우지 않음
        //
        // 같은 경합으로 요청이 셋 이상 들어올 수 있고 그때도 모두 같은 토큰을 받아야 함
        // 한 번 읽고 지우면 두 번째부터는 복제로 판정되어 유예 창을 둔 목적이 사라짐
        // 시간이 지나면 저절로 사라지므로 지우는 일은 하지 않음
        return Optional.ofNullable(redisTemplate.opsForValue().get(graceKey(tokenId)));
    }

    @Override
    public void deleteGrace(String tokenId) {
        redisTemplate.delete(graceKey(tokenId));
    }

    @Override
    public Optional<UUID> claim(String tokenId) {

        // 읽으면서 지움. Redis 안에서 한 번에 처리되는 명령임
        //
        // 확인과 삭제를 나누면 그 사이에 다른 요청이 같은 값을 읽고 지나갈 수 있음
        // 그러면 같은 토큰으로 두 벌의 새 토큰이 발급되고 한 벌이 주인 없이 남음
        String value = redisTemplate.opsForValue().getAndDelete(key(tokenId));

        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(value));
    }

    private String key(String tokenId) {
        return KEY_PREFIX + tokenId;
    }

    private String graceKey(String tokenId) {
        return GRACE_PREFIX + tokenId;
    }
}

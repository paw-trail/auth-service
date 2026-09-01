package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.RefreshTokenStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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

    // 교체를 한 덩어리로 처리하는 스크립트임
    //
    // 설정 클래스를 따로 만들지 않고 여기서 읽음
    // 이 스크립트를 쓰는 곳이 아래 rotate 하나뿐이라 밖으로 낼 이유가 없음
    //
    // 스프링이 처음 실행할 때 스크립트를 서버에 올리고 그 해시를 기억함
    // 그다음부터는 해시만 보내므로 본문이 매번 오가지 않음
    private static final RedisScript<String> ROTATE_SCRIPT = RedisScript.of(
            new ClassPathResource("redis/rotate-refresh-token.lua"), String.class);

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
    public Optional<UUID> rotate(String oldTokenId, String newTokenId, String newTokenValue,
                                 Duration ttl, Duration graceTtl) {

        // 옛 토큰 소비 · 새 토큰 활성화 · 유예 항목 공개를 스크립트 하나로 처리함
        //
        // 나눠서 하면 그 중간 상태가 다른 요청에 보임
        // 특히 "유예 항목은 보이는데 새 토큰은 아직 없는" 구간이 위험한데,
        // 그때 들어온 요청이 쓸 수 없는 토큰을 받아 가고
        // 그것으로 다시 갱신하면 복제로 판정되어 계정 전체가 폐기됨
        //
        // 키 순서와 인자 순서는 스크립트 안의 KEYS · ARGV 와 짝을 이룸
        // 어긋나면 엉뚱한 키를 지우는데 오류가 나지 않으므로 함께 고쳐야 함
        String accountId = redisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(key(oldTokenId), key(newTokenId), graceKey(oldTokenId)),
                String.valueOf(ttl.toSeconds()),
                newTokenValue,
                String.valueOf(graceTtl.toSeconds()));

        if (accountId == null) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(accountId));
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
    public Optional<UUID> claim(String tokenId) {

        // 읽으면서 지움. Redis 안에서 한 번에 처리되는 명령임
        //
        // 확인과 삭제를 나누면 그 사이에 다른 요청이 같은 값을 읽고 지나갈 수 있음
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

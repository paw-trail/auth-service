package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.OAuthStateStore;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 Redis 로 구현합니다.
 */
@Repository
@RequiredArgsConstructor
public class OAuthStateStoreImpl implements OAuthStateStore {

    // 키 앞에 붙는 이름임
    // Redis 는 하나의 저장소를 여러 용도가 나눠 쓰므로 접두사로 구분함
    private static final String KEY_PREFIX = "oauth:state:";

    // 인가 흐름 하나가 끝나기까지 주어지는 시간임
    //
    // 제공자 화면에서 계정을 고르고 동의하는 데 걸리는 시간이면 충분함
    // 길게 두면 잃어버린 값이 그만큼 오래 쓸 수 있는 상태로 남고,
    // 짧게 두면 계정을 고르다 만료되어 정상 사용자가 처음부터 다시 해야 함
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String state, String nonce) {
        redisTemplate.opsForValue().set(key(state), nonce, TTL);
    }

    @Override
    public Optional<String> consume(String state) {
        // 꺼내면서 지움
        //
        // 확인과 삭제를 나누면 두 요청이 같은 state 로 동시에 들어왔을 때 둘 다 통과함
        // 이 명령은 저장소 안에서 한 번에 처리되므로 하나만 값을 받음
        // 리프레시 토큰을 회수할 때 쓴 것과 같은 방법임
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(key(state)));
    }

    private String key(String state) {
        return KEY_PREFIX + state;
    }
}

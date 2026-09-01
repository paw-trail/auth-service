package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.WithdrawStore;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 Redis 로 구현합니다.
 *
 * 재설정 쪽과 하는 일이 같고 다른 것은 접두사뿐입니다.
 * 그 접두사가 두 코드를 갈라 놓는 장치입니다.
 */
@Repository
@RequiredArgsConstructor
public class WithdrawStoreImpl implements WithdrawStore {

    private static final String CODE_PREFIX = "withdraw:";
    private static final String ATTEMPT_PREFIX = "withdraw:attempt:";

    // 코드를 입력할 시간임
    // 가입 인증·재설정과 같은 값으로 둠, 메일이 도착하는 데 걸리는 시간이 다르지 않음
    private static final Duration CODE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void saveCode(String email, String code) {
        redisTemplate.opsForValue().set(codeKey(email), code, CODE_TTL);
        redisTemplate.delete(attemptKey(email));
    }

    @Override
    public Optional<String> findCode(String email) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(codeKey(email)));
    }

    @Override
    public int increaseAttempt(String email) {
        Long count = redisTemplate.opsForValue().increment(attemptKey(email));

        // 키가 처음 만들어질 때 수명을 붙임, 이유는 가입 인증 쪽과 같음
        if (count != null && count == 1L) {
            redisTemplate.expire(attemptKey(email), CODE_TTL);
        }
        return count == null ? 0 : count.intValue();
    }

    @Override
    public void deleteCode(String email) {
        redisTemplate.delete(codeKey(email));
        redisTemplate.delete(attemptKey(email));
    }

    private String codeKey(String email) {
        return CODE_PREFIX + email;
    }

    private String attemptKey(String email) {
        return ATTEMPT_PREFIX + email;
    }
}

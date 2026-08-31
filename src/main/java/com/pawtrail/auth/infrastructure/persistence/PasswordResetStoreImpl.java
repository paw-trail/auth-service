package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.PasswordResetStore;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 Redis 로 구현합니다.
 *
 * 가입 인증 쪽과 하는 일이 거의 같습니다. 다른 것은 접두사와,
 * 통과 표시를 남기지 않는다는 점뿐입니다.
 */
@Repository
@RequiredArgsConstructor
public class PasswordResetStoreImpl implements PasswordResetStore {

    private static final String CODE_PREFIX = "pwreset:";
    private static final String ATTEMPT_PREFIX = "pwreset:attempt:";

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

        // 키가 처음 만들어질 때 수명을 붙입니다. 이유는 가입 인증 쪽과 같습니다.
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

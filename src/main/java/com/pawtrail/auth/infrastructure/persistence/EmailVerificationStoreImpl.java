package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.EmailVerificationStore;
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
public class EmailVerificationStoreImpl implements EmailVerificationStore {

    // 키 앞에 붙는 이름임
    // Redis 는 하나의 저장소를 여러 용도가 나눠 쓰므로 접두사로 구분함
    private static final String CODE_PREFIX = "emailverify:";
    private static final String ATTEMPT_PREFIX = "emailverify:attempt:";
    private static final String VERIFIED_PREFIX = "emailverified:";

    // 코드를 입력할 시간임
    // 메일이 도착하는 데 몇 초에서 몇 분이 걸리므로 너무 짧으면 정상 사용자가 막힙니다.
    private static final Duration CODE_TTL = Duration.ofMinutes(10);

    // 인증을 통과한 뒤 가입까지 주어지는 시간임
    // 코드보다 길게 두는 것은, 인증을 마친 사람이 비밀번호와 닉네임을 정하는 데
    // 시간이 걸리기 때문임
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void saveCode(String email, String code) {
        // 이전 코드를 덮어씁니다.
        // 여러 개를 살려 두면 어느 것이든 맞히면 되어 맞힐 확률이 올라갑니다.
        redisTemplate.opsForValue().set(codeKey(email), code, CODE_TTL);

        // 시도 횟수도 함께 초기화함
        // 다시 요청했다는 것은 앞선 시도를 접었다는 뜻이므로 횟수를 물려받지 않음
        redisTemplate.delete(attemptKey(email));
    }

    @Override
    public Optional<String> findCode(String email) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(codeKey(email)));
    }

    @Override
    public int increaseAttempt(String email) {
        Long count = redisTemplate.opsForValue().increment(attemptKey(email));

        // increment 는 키가 없으면 0에서 시작해 1을 만들지만 수명을 주지 않음
        // 그대로 두면 이 키만 영원히 남으므로 처음 만들어질 때 코드와 같은 수명을 붙임
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

    @Override
    public void markVerified(String email) {
        // 값이 무엇인지는 보지 않음. 키가 있으면 통과한 것임
        redisTemplate.opsForValue().set(verifiedKey(email), "1", VERIFIED_TTL);
    }

    @Override
    public boolean isVerified(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(verifiedKey(email)));
    }

    @Override
    public void clearVerified(String email) {
        redisTemplate.delete(verifiedKey(email));
    }

    private String codeKey(String email) {
        return CODE_PREFIX + email;
    }

    private String attemptKey(String email) {
        return ATTEMPT_PREFIX + email;
    }

    private String verifiedKey(String email) {
        return VERIFIED_PREFIX + email;
    }
}

package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.WithdrawStore;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 Redis 로 구현합니다.
 *
 * 재설정 쪽과 담는 것은 같고 다른 것은 접두사와, 대조를 스크립트로 한다는 점입니다.
 */
@Repository
@RequiredArgsConstructor
public class WithdrawStoreImpl implements WithdrawStore {

    private static final String CODE_PREFIX = "withdraw:";
    private static final String ATTEMPT_PREFIX = "withdraw:attempt:";

    // 코드를 입력할 시간임
    // 가입 인증·재설정과 같은 값으로 둠, 메일이 도착하는 데 걸리는 시간이 다르지 않음
    private static final Duration CODE_TTL = Duration.ofMinutes(10);

    // 대조와 삭제를 한 번에 처리하는 스크립트임
    //
    // 반환값은 1 맞음 · 0 틀림 · -1 없음
    // 스크립트 안에서 한 덩어리로 실행되므로 같은 코드로 동시에 들어온 요청 중
    // 하나만 1을 받음
    //
    // 로딩 방식은 refresh 토큰 교체 스크립트와 같음
    private static final RedisScript<Long> CONSUME_SCRIPT = RedisScript.of(
            new ClassPathResource("redis/consume-withdraw-code.lua"), Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void saveCode(String email, String code) {
        redisTemplate.opsForValue().set(codeKey(email), code, CODE_TTL);
        redisTemplate.delete(attemptKey(email));
    }

    @Override
    public ConsumeResult consume(String email, String inputCode) {
        Long result = redisTemplate.execute(
                CONSUME_SCRIPT, List.of(codeKey(email)), inputCode);

        // 스크립트가 값을 못 돌려주는 경우는 없으나, null 을 그대로 비교하면
        // 예외가 나므로 없는 것과 같게 다룸
        if (result == null) {
            return ConsumeResult.NOT_FOUND;
        }
        if (result == 1L) {
            return ConsumeResult.MATCHED;
        }
        return result == 0L ? ConsumeResult.MISMATCHED : ConsumeResult.NOT_FOUND;
    }

    @Override
    public void deleteCode(String email) {
        redisTemplate.delete(codeKey(email));
        redisTemplate.delete(attemptKey(email));
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

    private String codeKey(String email) {
        return CODE_PREFIX + email;
    }

    private String attemptKey(String email) {
        return ATTEMPT_PREFIX + email;
    }
}

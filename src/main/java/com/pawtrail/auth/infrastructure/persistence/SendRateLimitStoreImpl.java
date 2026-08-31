package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.SendRateLimitStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 Redis 로 구현합니다.
 *
 * 값이 저절로 사라져야 하는 성격이라 Redis 가 맞습니다.
 * 데이터베이스에 두면 지난 기록을 지우는 작업을 따로 만들어야 하고,
 * 남길 가치도 없는 이력이 쌓입니다.
 */
@Repository
@RequiredArgsConstructor
public class SendRateLimitStoreImpl implements SendRateLimitStore {

    private static final String COOLDOWN_PREFIX = "mailcooldown:";
    private static final String HOURLY_PREFIX = "mailhourly:";

    // 같은 주소로 연달아 보내지 못하게 하는 시간입니다.
    //
    // 메일이 도착하는 데 몇 초가 걸리므로, 사용자가 오지 않는다고 다시 누르는 것을
    // 이 시간 동안 막습니다. 더 길게 두면 정상 사용자가 답답해집니다.
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    // 같은 주소로 한 시간에 보낼 수 있는 총량입니다.
    //
    // 오타를 고쳐 다시 받는 정상 사용자가 걸리지 않을 만큼은 되고,
    // 쏟아붓는 것은 막을 만큼은 적은 값으로 잡았습니다.
    private static final int HOURLY_LIMIT = 5;
    private static final Duration HOURLY_WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean canSend(String email) {

        // 쿨다운 키가 살아 있으면 아직 때가 아닙니다.
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey(email)))) {
            return false;
        }

        String count = redisTemplate.opsForValue().get(hourlyKey(email));
        if (count == null) {
            return true;
        }
        return Integer.parseInt(count) < HOURLY_LIMIT;
    }

    @Override
    public void recordSent(String email) {

        // 쿨다운을 시작합니다. 값은 쓰지 않으므로 아무것이나 넣습니다.
        redisTemplate.opsForValue().set(cooldownKey(email), "1", COOLDOWN);

        Long count = redisTemplate.opsForValue().increment(hourlyKey(email));

        // increment 는 키가 없으면 만들면서 수명을 주지 않습니다.
        // 그대로 두면 이 키만 영원히 남고 시간당 제한이 평생 제한이 됩니다.
        //
        // 처음 만들어질 때만 수명을 붙이므로 한 시간이 흐르는 기준은
        // 마지막 발송이 아니라 그 시간대의 첫 발송입니다.
        // 창이 밀려나지 않아 계산이 단순하고, 한 시간이 지나면 카운터가 통째로 사라집니다.
        if (count != null && count == 1L) {
            redisTemplate.expire(hourlyKey(email), HOURLY_WINDOW);
        }
    }

    private String cooldownKey(String email) {
        return COOLDOWN_PREFIX + email;
    }

    private String hourlyKey(String email) {
        return HOURLY_PREFIX + email;
    }
}

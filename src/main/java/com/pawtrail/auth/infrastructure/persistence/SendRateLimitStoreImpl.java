package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.provider.MailSender.MailPurpose;
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
 *
 * 동시 요청을 어떻게 막는가
 *
 * 쿨다운 키를 setIfAbsent 로 잡습니다.
 * 이 명령은 "없을 때만 넣는다" 를 Redis 안에서 한 번에 처리하므로,
 * 같은 순간에 요청이 백 개 들어와도 참을 받는 것은 하나뿐입니다.
 *
 * 시간당 카운터는 따로 원자화하지 않습니다.
 * 쿨다운이 앞에서 한 번에 하나만 통과시키므로, 카운터를 읽는 시점에는
 * 앞선 발송이 이미 반영되어 있어 둘이 겹칠 창 자체가 없습니다.
 */
@Repository
@RequiredArgsConstructor
public class SendRateLimitStoreImpl implements SendRateLimitStore {

    // 키에 용도를 섞음
    //
    // mailcooldown:{용도}:{이메일} 형태가 되어 기능마다 따로 셈
    // 섞지 않으면 가입 인증을 몇 번 받은 사람이 탈퇴를 못 하는 식으로
    // 관계없는 기능이 서로의 한도를 깎음
    private static final String COOLDOWN_PREFIX = "mailcooldown:";
    private static final String HOURLY_PREFIX = "mailhourly:";

    // 같은 주소로 연달아 보내지 못하게 하는 시간임
    //
    // 메일이 도착하는 데 몇 초가 걸리므로, 사용자가 오지 않는다고 다시 누르는 것을
    // 이 시간 동안 막음. 더 길게 두면 정상 사용자가 답답해짐
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    // 같은 주소로 한 시간에 보낼 수 있는 총량임
    //
    // 오타를 고쳐 다시 받는 정상 사용자가 걸리지 않을 만큼은 되고,
    // 쏟아붓는 것은 막을 만큼은 적은 값으로 잡았음
    private static final int HOURLY_LIMIT = 5;
    private static final Duration HOURLY_WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryAcquire(MailPurpose purpose, String email) {

        // 쿨다운 자리를 먼저 잡음
        //
        // 값은 쓰지 않으므로 아무것이나 넣음
        // 중요한 것은 값이 아니라 "이 키를 내가 만들었는가" 이며,
        // 그 판단이 Redis 안에서 한 번에 끝나는 것이 이 방식의 전부임
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(cooldownKey(purpose, email), "1", COOLDOWN);

        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }

        // 여기부터는 이 요청 하나만 지나갑니다.
        // 그래서 아래 카운터 읽기는 다른 요청과 겹치지 않음
        String count = redisTemplate.opsForValue().get(hourlyKey(purpose, email));

        if (count != null && Integer.parseInt(count) >= HOURLY_LIMIT) {

            // 상한에 걸렸으면 잡아 둔 자리를 돌려줍니다.
            //
            // 보내지 않았는데 쿨다운을 물리면 그 값이 거짓말이 됩니다.
            // 돌려주어도 이 주소는 카운터에 막혀 있어 계속 거부되므로 안전함
            release(purpose, email);
            return false;
        }

        return true;
    }

    @Override
    public void recordSent(MailPurpose purpose, String email) {

        Long count = redisTemplate.opsForValue().increment(hourlyKey(purpose, email));

        // increment 는 키가 없으면 만들면서 수명을 주지 않음
        // 그대로 두면 이 키만 영원히 남고 시간당 제한이 평생 제한이 됩니다.
        //
        // 처음 만들어질 때만 수명을 붙이므로 한 시간이 흐르는 기준은
        // 마지막 발송이 아니라 그 시간대의 첫 발송임
        // 창이 밀려나지 않아 계산이 단순하고, 한 시간이 지나면 카운터가 통째로 사라집니다.
        if (count != null && count == 1L) {
            redisTemplate.expire(hourlyKey(purpose, email), HOURLY_WINDOW);
        }
    }

    @Override
    public void release(MailPurpose purpose, String email) {

        // 쿨다운만 지웁니다.
        // 시간당 카운터는 발송에 성공한 뒤에야 올라가므로 되돌릴 것이 없음
        redisTemplate.delete(cooldownKey(purpose, email));
    }

    private String cooldownKey(MailPurpose purpose, String email) {
        return COOLDOWN_PREFIX + purpose.key() + ":" + email;
    }

    private String hourlyKey(MailPurpose purpose, String email) {
        return HOURLY_PREFIX + purpose.key() + ":" + email;
    }
}

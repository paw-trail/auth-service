package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.repository.EmailVerificationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 Redis 로 구현합니다.
 *
 * 데이터베이스 테이블을 만들지 않는 이유는 만료가 이 값의 본질이기 때문입니다.
 * 30분이 지나면 사라져야 하는데, 테이블에 두면 지우는 작업을 따로 만들어야 하고
 * 남길 가치도 없는 이력이 쌓입니다.
 */
@Repository
@RequiredArgsConstructor
public class EmailVerificationStoreImpl implements EmailVerificationStore {

    // 인증을 통과했다는 표시입니다.
    //
    // 이메일 인증 이슈에서 emailverify: 접두사가 하나 더 생깁니다.
    // 그쪽은 보낸 코드와 시도 횟수를 담고, 이쪽은 통과 여부만 담습니다.
    private static final String VERIFIED_KEY_PREFIX = "emailverified:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean isVerified(String email) {
        // 값이 무엇인지는 보지 않습니다. 키가 있으면 통과한 것입니다.
        return Boolean.TRUE.equals(redisTemplate.hasKey(verifiedKey(email)));
    }

    @Override
    public void clearVerified(String email) {
        redisTemplate.delete(verifiedKey(email));
    }

    private String verifiedKey(String email) {
        return VERIFIED_KEY_PREFIX + email;
    }
}

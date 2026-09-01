package com.pawtrail.auth.domain.event.payload;

import com.pawtrail.common.message.DomainEvent;
import java.util.UUID;

/**
 * 계정이 만들어졌음을 알리는 이벤트입니다.
 *
 * user 서비스가 받아 user_profile 을 만듭니다.
 * 탈퇴의 account.withdrawn 과 대칭입니다.
 *   가입   account.created   → user 가 프로필을 만든다
 *   탈퇴   account.withdrawn → user·pet·report·review·notification 이 각자 지운다
 *
 * 동기 호출이 아니라 이벤트인 이유는 트랜잭션이 갈리기 때문입니다.
 * auth 가 POST /internal/users 를 부르면 auth 는 성공했는데 user 가 실패하는 경우가 생기고,
 * 그때 보상이나 재시도를 따로 짜야 합니다.
 * 이벤트는 계정 INSERT 와 같은 트랜잭션에 기록되므로 그 상태가 원천적으로 생기지 않습니다.
 *
 * 이 이벤트만 payload 가 값을 나릅니다.
 * 다른 이벤트들은 "네가 가진 것이 낡았다" 를 알리고 받는 쪽이 /internal 로 다시 읽는 형태인데,
 * nickname 은 auth 에 없어 다시 읽을 곳이 없으므로 이벤트가 값을 전달하는 유일한 경로입니다.
 *
 * @param accountId 계정 식별자입니다. 이 값이 user_profile 의 키가 됩니다.
 * @param email     계정 이메일입니다.
 * @param nickname  사용자가 가입할 때 입력한 이름입니다.
 *                  * 소셜 가입은 null 입니다
 *                    제공자 콜백에서는 닉네임을 입력받지 않기 때문입니다
 *                    임시 이름을 만들지 않는 이유는, null 자체가 "아직 설정하지 않음" 의
 *                    판별이 되어 별도 플래그 컬럼이나 생성기가 필요 없기 때문입니다
 */
public record AccountCreatedEvent(UUID accountId, String email, String nickname)
        implements DomainEvent {

    // 아래 셋은 봉투를 만들 때만 쓰이고 payload 에는 실리지 않음
    // DomainEvent 가 @JsonIgnore 를 선언해 두었으므로 구현체가 그대로 물려받음

    @Override
    public String getTopic() {
        // infra 의 create-topics.sh 에 같은 이름이 있어야 함
        // 토픽 자동 생성을 꺼 두었으므로 없으면 발행이 실패함
        return "account.created";
    }

    @Override
    public String getAggregateType() {
        return "Account";
    }

    @Override
    public String getAggregateId() {
        // 파티션 키가 되어 같은 계정에 대한 이벤트의 순서를 보장함
        return accountId.toString();
    }
}

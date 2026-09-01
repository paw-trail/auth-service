package com.pawtrail.auth.domain.event.payload;

import com.pawtrail.common.message.DomainEvent;
import java.util.UUID;

/**
 * 계정이 탈퇴했음을 알리는 이벤트입니다.
 *
 * user · pet · report · review · notification 이 받아 각자 자기 데이터를 지웁니다.
 * S3 에 올라간 사진도 그 서비스들이 자기 몫을 지웁니다.
 * 가입의 account.created 와 대칭입니다.
 *
 * 되돌리는 것이 아니라 마저 지우는 방식입니다.
 * auth 는 계정 상태를 바꾸고 이벤트를 남기는 것으로 자기 일을 끝내고,
 * 받는 쪽이 실패하면 재시도가 이어서 지웁니다.
 * 보상 트랜잭션이 아니라 전진 복구라 되돌릴 것이 없습니다.
 *
 * payload 에 식별자 하나만 담습니다.
 * 받는 쪽이 하는 일이 "이 계정의 것을 지운다" 하나뿐이라 더 필요한 값이 없습니다.
 * 이메일이나 닉네임을 담으면 지우려는 개인정보가 이벤트로 흘러
 * 소비자들의 로그와 토픽에 남습니다.
 *
 * @param accountId 계정 식별자입니다. 이 값이 전 서비스에서 사용자를 가리키는 키입니다.
 */
public record AccountWithdrawnEvent(UUID accountId) implements DomainEvent {

    // 아래 셋은 봉투를 만들 때만 쓰이고 payload 에는 실리지 않음
    // DomainEvent 가 @JsonIgnore 를 선언해 두었으므로 구현체가 그대로 물려받음

    @Override
    public String getTopic() {
        // infra 의 create-topics.sh 에 같은 이름이 있어야 함
        // 토픽 자동 생성을 꺼 두었으므로 없으면 발행이 실패함
        return "account.withdrawn";
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

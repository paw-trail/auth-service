package com.pawtrail.auth.application.dto.output;

import com.pawtrail.common.message.outbox.OutboxMessage;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 재발행 대상 outbox 항목의 요약입니다.
 *
 * payload 를 담지 않습니다.
 *
 * 이 목록에 뜨는 것은 account.created 와 account.withdrawn 이고,
 * 앞의 것은 payload 에 이메일과 닉네임이 그대로 들어 있습니다.
 * 관리자가 재발행을 누르는 데 필요한 것은 식별자뿐이라 그 값을 화면까지 흘릴 이유가 없습니다.
 * 내용을 확인해야 하면 Kafka UI 나 데이터베이스에서 봅니다.
 *
 * 대신 retryCount 와 lastError 는 담습니다.
 * 관리자가 "지금 눌러도 되는 상황인가" 를 판단하는 재료이기 때문입니다.
 * 카프카가 잠시 죽어 있었던 것이라면 눌러도 되지만,
 * 직렬화 오류처럼 코드를 고쳐야 하는 것이면 눌러도 또 실패합니다.
 *
 * @param id           재발행할 때 넘기는 값입니다. 이 목록이 존재하는 이유이기도 합니다.
 * @param eventId      이벤트 봉투의 식별자입니다. Kafka UI 에서 그 이벤트를 찾을 때 씁니다.
 * @param topic        어느 소비자가 받지 못하고 있는지를 알려 줍니다.
 * @param aggregateId  어느 계정에 대한 이벤트인지입니다. 계정 상태를 함께 확인할 때 씁니다.
 * @param createdAt    언제부터 멈춰 있는지입니다.
 * @param retryCount   Relay 가 몇 번 시도하고 포기했는지입니다.
 * @param lastError    마지막 실패 원인입니다. 없을 수도 있습니다.
 */
public record OutboxMessageOutput(UUID id,
                                    UUID eventId,
                                    String topic,
                                    String aggregateType,
                                    String aggregateId,
                                    LocalDateTime createdAt,
                                    int retryCount,
                                    String lastError) {

    public static OutboxMessageOutput from(OutboxMessage message) {
        return new OutboxMessageOutput(
                message.getId(),
                message.getEventId(),
                message.getTopic(),
                message.getAggregateType(),
                message.getAggregateId(),
                message.getCreatedAt(),
                message.getRetryCount(),
                message.getLastError());
    }
}

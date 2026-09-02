package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.dto.output.OutboxMessageOutput;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.common.message.outbox.OutboxPublisher;
import com.pawtrail.common.message.outbox.OutboxRepository;
import com.pawtrail.common.response.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행이 끝내 실패한 이벤트를 관리자가 다시 내보내도록 돕습니다.
 *
 * 왜 이 API 가 필요한가
 *
 * OutboxRelay 는 안전망이지 완전한 보장이 아닙니다.
 * 재시도 횟수가 상한에 이른 건은 조회에서 아예 빠지는데, 그렇게 하지 않으면
 * 포기한 건이 같은 집합체의 뒤 이벤트를 영영 막기 때문입니다.
 *
 * 빠진 뒤로는 에러도 남지 않습니다. 실패한 것이 아니라 대상이 아니게 된 것이라
 * 아무도 다시 보내지 않고 조회 수단이 없으면 존재 자체를 알 수 없습니다.
 * outbox 테이블에 retry_count 와 last_error 를 처음부터 둔 이유가 이 자리입니다.
 *
 * 이 서비스는 공통 모듈을 그대로 씁니다.
 * 조회도 발행도 거기 있고, 여기서 하는 일은 무엇을 보여줄지 고르고 응답으로 바꾸는 것뿐입니다.
 * 공통 모듈에 관리자 컨트롤러를 두지 않는 것은 서비스마다 관리자가 할 일이 다르기 때문이며,
 * 재발행은 그 데이터를 소유한 쪽이 처리하는 것이 자연스럽습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOutboxService {

    private final OutboxRepository outboxRepository;
    private final OutboxPublisher outboxPublisher;

    /**
     * Relay 가 포기한 항목을 봅니다.
     *
     * 아직 재시도 중인 건은 담기지 않습니다.
     * 그것들은 곧 Relay 가 다시 집을 것이라 사람이 할 일이 없고,
     * 섞어서 보여주면 목록을 볼 때마다 어느 쪽인지 가려야 합니다.
     * 걸러 두면 목록이 비어 있다는 것 자체가 문제가 없다는 뜻이 됩니다.
     *
     * 상한값을 여기서 넘기지 않습니다.
     * 그 값은 Relay 가 포기하는 기준이라 공통 모듈이 소유하며,
     * 서비스가 다른 숫자를 넘기면 포기 기준과 조회 기준이 어긋납니다.
     */
    @Transactional(readOnly = true)
    public PageResponse<OutboxMessageOutput> findGivenUp(Pageable pageable) {
        return PageResponse.from(
                outboxRepository.findGivenUpMessages(pageable), OutboxMessageOutput::from);
    }

    /**
     * 한 건을 다시 발행합니다.
     *
     * 재시도 횟수를 되돌리지 않습니다.
     * 발행 메서드에 그 횟수를 보는 검사가 없어 상한을 넘긴 건도 그대로 나가므로
     * 되돌릴 이유가 없고, 남아 있는 값이 "몇 번 실패한 뒤 사람이 보냈는지" 의 기록이 됩니다.
     *
     * 재발행된 이벤트는 순서가 어긋나 있을 수 있습니다.
     * 멈춘 건이 같은 계정의 뒤 이벤트를 막지 않기 때문에,
     * 누르는 시점에는 더 나중에 만들어진 이벤트가 이미 나가 있을 수 있습니다.
     *
     * @throws CustomException 발행에 실패한 경우입니다.
     */
    @Transactional
    public void republish(UUID outboxId) {

        // 발행 메서드는 성공 여부를 참·거짓으로 돌려줌
        //
        // 거짓인데 성공으로 응답하면 관리자는 보냈다고 알고 넘어가는데 이벤트는 안 나감
        // 그 상태가 바로 이 API 가 막으려던 것이라 반드시 갈라야 함
        //
        // 실패하면 재시도 횟수가 하나 더 오르고 마지막 오류가 갱신되므로
        // 목록을 다시 열면 무엇 때문에 실패했는지가 보임
        boolean published = outboxPublisher.publish(outboxId);
        if (!published) {
            log.error("관리자 재발행에 실패했습니다. outboxId={}", outboxId);
            throw new CustomException(AuthErrorCode.OUTBOX_REPUBLISH_FAILED);
        }

        log.info("관리자가 이벤트를 다시 발행했습니다. outboxId={}", outboxId);
    }
}

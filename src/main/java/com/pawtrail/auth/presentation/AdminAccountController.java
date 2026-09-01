package com.pawtrail.auth.presentation;

import com.pawtrail.auth.application.dto.response.OutboxMessageResponse;
import com.pawtrail.auth.application.service.AdminOutboxService;
import com.pawtrail.common.response.CommonApiResponse;
import com.pawtrail.common.response.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자가 이 서비스의 운영 기능을 다루는 API 입니다.
 *
 * 경로가 /api/v1/admin/accounts 인 이유
 *
 * 관리자 경로는 두 번째 마디가 어느 서비스인지를 정합니다.
 * 게이트웨이가 그 규칙으로 라우팅하므로 라우트를 새로 열 필요가 없습니다.
 * outbox 를 가진 서비스가 다섯이라 공용 경로 하나로 두면 어디로 보낼지 정할 수 없습니다.
 *
 * 누가 부르는가
 *
 * 프론트의 관리자 페이지가 부릅니다.
 * 게이트웨이를 거쳐야 역할 헤더가 실리므로 서비스 포트로 직접 부르면 통과하지 못합니다.
 *
 * 보호
 *
 * 이 경로는 ADMIN 역할만 통과합니다. 게이트웨이가 먼저 막고 이 서비스가 한 번 더 막습니다.
 * 공통 모듈의 보안 체인에도 같은 규칙이 있으나 이 서비스는 자기 체인을 정의해
 * 그쪽이 물러나므로, SecurityConfig 에 그 줄을 직접 두었습니다.
 *
 * 관리자 역할은 데이터베이스에서 직접 지정합니다. 그 방법은 README 에 있습니다.
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminOutboxService adminOutboxService;

    /**
     * 발행이 끝내 실패해 멈춰 있는 이벤트를 봅니다.
     *
     * 아직 재시도 중인 건은 나오지 않습니다.
     * 비어 있다면 손댈 것이 없다는 뜻입니다.
     */
    @GetMapping("/outbox")
    public ResponseEntity<CommonApiResponse<PageResponse<OutboxMessageResponse>>> findGivenUpOutbox(
            @PageableDefault(size = 20) Pageable pageable) {

        PageResponse<OutboxMessageResponse> response = adminOutboxService.findGivenUp(pageable);
        return ResponseEntity.ok(CommonApiResponse.success(response));
    }

    /**
     * 한 건을 다시 발행합니다.
     *
     * 위 목록의 id 를 그대로 넘깁니다.
     * 실패하면 성공으로 응답하지 않습니다. 보냈다고 알고 넘어가는 것이
     * 이 기능이 막으려던 상황 그 자체이기 때문입니다.
     */
    @PostMapping("/outbox/{outboxId}/retry")
    public ResponseEntity<CommonApiResponse<Void>> republishOutbox(
            @PathVariable UUID outboxId) {

        adminOutboxService.republish(outboxId);
        return ResponseEntity.ok(CommonApiResponse.success(null));
    }
}

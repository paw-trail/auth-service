package com.pawtrail.auth.application.service;

import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.domain.repository.RefreshTokenLogRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 계정의 토큰을 전부 폐기합니다.
 *
 * 부르는 곳이 둘입니다.
 *   토큰 복제가 탐지됐을 때
 *   비밀번호가 바뀌었을 때
 *
 * 왜 별도 클래스이고 별도 트랜잭션인가
 *
 * 복제를 탐지한 쪽은 폐기한 뒤에 401 을 던져야 합니다.
 * 그런데 그 예외가 RuntimeException 이라 같은 트랜잭션 안에서 던지면
 * 방금 한 폐기가 통째로 되돌아갑니다.
 * 응답은 401 로 나가고 로그에도 "복제를 탐지했다" 가 찍히는데
 * 실제로는 아무것도 폐기되지 않은 상태가 되며, 오류가 나지 않아 눈에 띄지 않습니다.
 *
 * 그래서 복제 탐지 쪽은 자기 트랜잭션에서 돌고 먼저 커밋됩니다.
 * 부르는 쪽은 돌아온 뒤에 예외를 던지면 됩니다.
 *
 * 같은 클래스 안에서 부르면 프록시를 거치지 않아 이 설정이 먹지 않습니다.
 * 별도 빈으로 둔 것이 그 때문입니다.
 *
 * 진입점이 둘인 이유
 *
 * 비밀번호 재설정은 반대로 같은 트랜잭션에서 돌아야 합니다.
 * 새 트랜잭션으로 하면 영속성 컨텍스트가 따로 열려 계정을 데이터베이스에서 다시 읽는데,
 * 그때 바깥에는 비밀번호를 이미 고쳐 둔 다른 인스턴스가 남아 있습니다.
 * 안쪽이 폐기 기준 시각을 올리고 커밋한 뒤 바깥이 자기 인스턴스로 갱신하면,
 * 전 컬럼이 함께 나가면서 방금 올린 기준선이 옛 값으로 되돌아갑니다.
 * 오류가 나지 않아 "폐기했는데 안 된" 상태를 알아채기 어렵습니다.
 *
 * 두 메서드가 하는 일은 같고 트랜잭션 경계만 다르므로 안쪽은 하나를 나눠 씁니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevokeService {

    private final AccountRepository accountRepository;
    private final RefreshTokenLogRepository refreshTokenLogRepository;

    /**
     * 새 트랜잭션에서 폐기합니다. 복제를 탐지했을 때 부릅니다.
     *
     * 부르는 쪽이 곧바로 예외를 던지므로 그 트랜잭션에 얹으면 함께 되돌아갑니다.
     * 먼저 커밋시켜야 401 을 내보내면서도 폐기가 남습니다.
     *
     * 부르는 쪽이 그 계정을 고치지 않은 상태여야 합니다.
     * 고쳐 둔 것이 있으면 바깥이 나중에 갱신하면서 여기서 올린 값을 덮습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllInNewTransaction(UUID accountId, String reason) {
        doRevoke(accountId, reason);
    }

    /**
     * 부르는 쪽의 트랜잭션에서 폐기합니다. 비밀번호가 바뀌었을 때 부릅니다.
     *
     * 같은 영속성 컨텍스트라 계정 인스턴스가 하나뿐이고,
     * 비밀번호 변경과 폐기가 한 번에 반영되거나 함께 되돌아갑니다.
     */
    @Transactional
    public void revokeAll(UUID accountId, String reason) {
        doRevoke(accountId, reason);
    }

    /**
     * 그 계정의 토큰을 전부 무효로 만듭니다.
     *
     * 두 곳을 함께 손댑니다.
     *   계정   폐기 기준 시각을 지금으로 올려 이전 토큰이 갱신에서 거부되게 합니다
     *   이력   아직 회수 표시가 없는 행에 회수 시각을 남깁니다
     *
     * 앞엣것만 하면 갱신은 막히지만 이력에는 살아 있는 것처럼 남아,
     * 나중에 조사할 때 무엇이 언제 무효가 됐는지 알 수 없습니다.
     *
     * Redis 의 기록은 건드리지 않습니다.
     * 지우려면 계정으로 토큰을 찾아야 하는데 키가 refresh:{jti} 라 그럴 수 없고,
     * 그것을 하려고 목록을 따로 들고 있으면 동기화할 곳이 두 군데가 됩니다.
     * 기준 시각 하나로 거부되므로 남아 있어도 쓸 수 없으며 수명이 다하면 사라집니다.
     *
     * @param accountId 폐기할 계정입니다.
     * @param reason    로그에 남길 사유입니다.
     */
    private void doRevoke(UUID accountId, String reason) {

        Optional<Account> found = accountRepository.findById(accountId);

        if (found.isEmpty()) {
            // 토큰에 든 식별자로 계정을 못 찾는 경우입니다.
            // 우리가 발급한 토큰인데 그 계정이 없는 것이라 정상적인 상황이 아닙니다.
            // 폐기할 대상이 없으므로 그대로 두고 사실만 남깁니다.
            log.warn("폐기할 계정을 찾지 못했습니다. accountId={}, reason={}", accountId, reason);
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        Account account = found.get();
        account.revokeTokensBefore(now);

        int revoked = refreshTokenLogRepository.revokeAllActive(accountId, now);

        log.warn("계정의 토큰을 전부 폐기했습니다. accountId={}, 이력 {}건, reason={}",
                accountId, revoked, reason);
    }
}

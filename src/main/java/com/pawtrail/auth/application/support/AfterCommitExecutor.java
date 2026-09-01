package com.pawtrail.auth.application.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 커밋된 뒤에 실행합니다.
 *
 * 왜 필요한가
 *
 * Redis 는 트랜잭션에 묶이지 않습니다.
 * 데이터베이스 작업 사이에서 Redis 를 바꾸면, 뒤에서 롤백이 났을 때
 * 데이터베이스는 되돌아가는데 Redis 는 그대로 남아 둘이 어긋납니다.
 *   가입     계정은 안 만들어졌는데 이메일 인증 표시만 사라짐
 *   로그인   이력에 없는 리프레시 토큰이 만료까지 살아 있음
 *   재설정   비밀번호는 안 바뀌었는데 코드만 사라짐
 *
 * 공통 모듈의 OutboxCommitListener 도 같은 이유로 커밋 이후에 발행합니다.
 * 이 서비스만 다른 방식을 쓰면 같은 문제를 두 가지로 푸는 셈이 되므로 맞췄습니다.
 *
 * 감수하는 것
 *
 * 커밋이 끝난 뒤라 여기서 실패해도 호출자에게 전달되지 않습니다.
 * 로그만 남고 응답은 성공으로 나갑니다.
 * 다만 어느 쪽이든 복구할 길이 있어 큰 문제가 되지 않습니다.
 *   인증 표시가 안 지워짐   이메일이 중복될 수 없어 두 번째 가입이 어차피 막힘
 *   토큰이 저장 안 됨       다음 갱신 요청이 실패해 다시 로그인하게 됨
 *   코드가 안 지워짐        만료 시각이 지나면 사라짐
 *
 * 왜 별도 클래스인가
 *
 * 처음에는 AuthService 안의 private 메서드였으나, 비밀번호 재설정도
 * 같은 것이 필요해지면서 두 벌이 될 상황이 되어 밖으로 꺼냈습니다.
 * 앞으로 Redis 와 데이터베이스를 함께 건드리는 자리가 더 생기면 여기를 씁니다.
 */
@Slf4j
@Component
public class AfterCommitExecutor {

    /**
     * @param action      커밋 이후에 실행할 일입니다.
     * @param description 실패했을 때 로그에 남길 이름입니다.
     *                    무엇이 실패했는지가 로그만으로 드러나야 하므로 받습니다.
     */
    public void run(Runnable action, String description) {

        // 트랜잭션이 없으면 그냥 바로 실행함
        //
        // 테스트에서 트랜잭션 없이 부르는 경우가 있는데,
        // 그때 조용히 건너뛰면 "실행됐다고 생각했는데 안 된" 상태가 됩니다.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    // 여기서 던지면 이미 끝난 트랜잭션 밖으로 나가 아무도 받지 않음
                    // 남길 수 있는 것이 로그뿐이므로 무엇이 실패했는지를 적어 둡니다.
                    log.error("커밋 이후 작업에 실패했습니다: {}", description, e);
                }
            }
        });
    }
}

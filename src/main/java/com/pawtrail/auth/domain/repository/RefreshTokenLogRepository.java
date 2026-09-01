package com.pawtrail.auth.domain.repository;

import com.pawtrail.auth.domain.model.RefreshTokenLog;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 리프레시 토큰 발급 이력을 다루는 약속입니다.
 *
 * 실제 토큰은 Redis 에 있고 여기에는 이력만 남습니다.
 * 그래서 이 인터페이스로 하는 일은 "남기기" 와 "회수 표시하기" 두 가지뿐입니다.
 *
 * 회수 표시가 붙는 경로는 셋입니다.
 *   로그아웃    그 토큰 하나
 *   갱신        교체되어 없어진 옛 토큰
 *   일괄 폐기    그 계정의 아직 유효한 토큰 전부
 */
public interface RefreshTokenLogRepository {

    // 발급 이력을 남기거나 회수 시각을 반영함
    RefreshTokenLog save(RefreshTokenLog refreshTokenLog);

    // jti 로 이력 하나를 찾음
    // 로그아웃할 때 회수 시각을 남기려면 어느 행인지 알아야 함
    Optional<RefreshTokenLog> findByTokenId(String tokenId);

    // 한 계정의 아직 회수되지 않은 이력을 모두 회수 표시함
    //
    // 비밀번호가 바뀌었거나 토큰 복제가 탐지됐을 때 부름
    // 계정의 폐기 기준 시각만 올리면 갱신은 막히지만 이 표의 행들은 revokedAt 이 비어 있어
    // 실제로는 무효인데 이력상 살아 있는 것처럼 보임
    //
    // 한 행씩 불러다 고치지 않는 이유는 기기가 여럿이면 행이 여러 개이고
    // 그 전부를 읽어 올 이유가 없기 때문임
    //
    // 회수 표시가 붙은 행 수를 돌려줌
    int revokeAllActive(UUID accountId, LocalDateTime revokedAt);

    // 한 계정의 발급 이력을 최신순으로 가져옴
    //
    // "이 사람이 언제 어디서 로그인했나" 를 보는 것이 이 테이블의 주 용도임
    // 지금은 부르는 곳이 없고 이상 접속을 조사할 때 쓰기 위해 둠
    List<RefreshTokenLog> findByAccountIdOrderByIssuedAtDesc(UUID accountId);
}

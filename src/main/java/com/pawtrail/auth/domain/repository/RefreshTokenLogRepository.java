package com.pawtrail.auth.domain.repository;

import com.pawtrail.auth.domain.model.RefreshTokenLog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 리프레시 토큰 발급 이력을 다루는 약속입니다.
 *
 * 실제 토큰은 Redis 에 있고 여기에는 이력만 남습니다.
 * 그래서 이 인터페이스로 하는 일은 "남기기" 와 "찾아 회수 표시하기" 두 가지뿐입니다.
 */
public interface RefreshTokenLogRepository {

    // 발급 이력을 남기거나 회수 시각을 반영함
    RefreshTokenLog save(RefreshTokenLog refreshTokenLog);

    // jti 로 이력 하나를 찾음
    // 로그아웃할 때 회수 시각을 남기려면 어느 행인지 알아야 함
    Optional<RefreshTokenLog> findByTokenId(String tokenId);

    // 한 계정의 발급 이력을 최신순으로 가져옴
    //
    // "이 사람이 언제 어디서 로그인했나" 를 보는 것이 이 테이블의 주 용도임
    // 지금은 부르는 곳이 없고 이상 접속을 조사할 때 쓰기 위해 둠
    List<RefreshTokenLog> findByAccountIdOrderByIssuedAtDesc(UUID accountId);
}

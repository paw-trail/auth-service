-- refresh_token_log.token_id 에 UNIQUE 인덱스를 추가합니다.
--
-- 이 값은 JWT 의 jti 이며 Redis 키 refresh:{jti} 와 같은 값입니다.
-- 애플리케이션이 UUID 로 만들어 넣으므로 실제로 충돌할 일은 없지만,
-- 중복이 들어오는 상황 자체가 버그이므로 데이터베이스가 막습니다.
--
-- 두 가지를 함께 얻습니다.
--
-- 하나. 조회 계약이 지켜집니다.
--   findByTokenId 가 Optional 을 반환하는데 같은 값이 둘이면
--   IncorrectResultSizeDataAccessException 이 납니다.
--
-- 둘. 로그아웃이 빨라집니다.
--   지금은 token_id 에 인덱스가 없어 전체 스캔을 하며
--   발급 이력이 쌓일수록 느려집니다.
--
-- V20 을 고치지 않고 새 번호로 만드는 이유는
-- 그 스크립트가 이미 develop 에 들어갔기 때문입니다.
-- 아직 어느 영속 데이터베이스에도 적용되지 않아 고칠 수는 있었으나,
-- 예외를 한 번 두면 다음에 같은 상황에서 판단이 흔들립니다.

CREATE UNIQUE INDEX uq_refresh_token_log_token_id
    ON refresh_token_log (token_id);

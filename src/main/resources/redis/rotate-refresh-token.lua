-- 리프레시 토큰을 교체함
--
-- 옛 토큰을 써 버리고, 성공했을 때만 새 토큰을 활성화하고 유예 항목을 남김
-- 세 가지가 한 덩어리로 실행되므로 그 중간 상태를 다른 요청이 볼 수 없음
--
-- 왜 하나로 묶어야 하는가
--
-- 나눠서 하면 "유예 항목은 보이는데 새 토큰은 아직 활성화되지 않은" 구간이 생김
-- 그 사이에 들어온 동시 요청은 유예 항목에서 새 토큰을 받아 가는데,
-- 그 토큰으로 다시 갱신하면 저장소에 없으므로 복제로 판정되어 계정 전체가 폐기됨
--
-- 그 구간을 줄이는 방식으로 두 번 시도했다가 병렬 요청 검증에서 두 번 다 깨졌음
-- 키가 셋이라 단일 키 명령으로는 닫을 수 없는 구간이며, 그래서 스크립트로 묶음
--
-- 반환값
--   옛 토큰이 살아 있었으면 그 계정 식별자
--   이미 없었으면 false (호출부에서 비어 있는 값으로 받음)
--     교체된 지 오래된 토큰이거나, 로그아웃된 토큰이거나,
--     다른 요청이 방금 먼저 교체한 경우임. 어느 쪽인지는 호출부가 유예 항목으로 가름
--
-- KEYS[1]  refresh:{옛jti}         써 버릴 토큰
-- KEYS[2]  refresh:{새jti}         활성화할 토큰
-- KEYS[3]  refreshgrace:{옛jti}    동시 요청에게 내줄 자리
--
-- ARGV[1]  새 토큰의 수명(초). 1 미만이면 아무것도 지우지 않고 오류를 냄
-- ARGV[2]  새 리프레시 토큰 문자열
-- ARGV[3]  유예 시간(초). 같은 검사를 받음

-- 수명을 먼저 확인함
--
-- SET 은 EX 값이 0 이하이면 오류를 냄
-- 그런데 Redis 는 스크립트 중간에 난 오류를 되돌리지 않으므로,
-- 아래 GETDEL 뒤에서 터지면 옛 토큰만 사라지고 새 토큰도 유예 항목도 안 남음
-- 그 상태에서 다시 갱신하면 유예가 없어 복제로 판정되고 계정 전체가 폐기됨
--
-- 그래서 아무것도 지우기 전에 여기서 막음
-- 지금 값으로는 새 토큰의 수명이 항상 14일이라 걸릴 일이 없으나,
-- app.jwt.refresh-expiry 가 0 으로 잘못 들어가면 그렇게 됨
local ttl = tonumber(ARGV[1])
local graceTtl = tonumber(ARGV[3])

if not ttl or ttl < 1 or not graceTtl or graceTtl < 1 then
    return redis.error_reply(
        '토큰 수명이 1초 미만입니다. app.jwt.refresh-expiry 와 app.auth.rotation-grace 를 확인하십시오')
end

local accountId = redis.call('GETDEL', KEYS[1])

if not accountId then
    return false
end

redis.call('SET', KEYS[2], accountId, 'EX', ARGV[1])
redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])

return accountId

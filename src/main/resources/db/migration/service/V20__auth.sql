-- 이 서비스의 첫 마이그레이션 스크립트입니다.
-- V1 부터 V19 는 공통 모듈이 사용하는 대역이므로 쓰지 않습니다.
--
-- 이미 적용된 스크립트는 수정하지 않습니다.
-- 내용이 바뀌면 체크섬이 달라져 다음 기동이 실패합니다.
-- 변경이 필요하면 다음 번호로 새 스크립트를 만듭니다.
--
-- auth_db 에는 이 두 테이블과 공통 대역의 outbox 만 있습니다.
-- 리프레시 토큰 본체, 비밀번호 재설정 코드, 회원가입 이메일 인증 코드,
-- 소셜 로그인의 state 는 전부 Redis 에 있습니다.
-- 만료가 본질이고 이력을 남길 가치가 없어 테이블을 만들지 않았습니다.

-- =============================================================================
-- account
-- =============================================================================
-- 계정. id 가 그대로 X-User-Id 로 흘러 전 서비스가 이 값을 참조합니다.
--
-- 닉네임은 이 테이블에 없습니다. user_db 의 user_profile 이 소유하며,
-- auth 는 회원가입 때 받아서 account.created 이벤트로 넘기기만 합니다.

CREATE TABLE account
(
    -- PK 는 모든 테이블이 uuid 입니다.
    -- 애플리케이션이 Hibernate 의 @UuidGenerator(style = VERSION_7) 로 생성해 넣으므로
    -- 여기에 기본값을 지정하지 않습니다.
    id               uuid         PRIMARY KEY,

    -- 로그인 아이디이자 계정 복구의 유일한 수단입니다.
    -- 소셜 로그인을 구글로 정해 이메일이 항상 확보되므로 NOT NULL 입니다.
    -- 구글은 email 스코프가 기본이고 사용자가 거부할 개념이 없습니다.
    --
    -- UNIQUE 라 같은 이메일로 소셜과 로컬 두 계정이 생기는 것이 막힙니다.
    -- 계정 연결 기능은 두지 않습니다.
    email            varchar(255) NOT NULL UNIQUE,

    -- BCrypt 해시는 길이가 60 으로 고정입니다.
    -- 소셜 계정은 비밀번호가 없으므로 NULL 입니다.
    -- 비밀번호 재설정도 이 값이 NULL 이면 대상이 아닙니다.
    password_hash    varchar(60),

    -- LOCAL · GOOGLE
    -- 카카오·네이버는 이메일 동의항목에 승인 절차가 있어 나중으로 미뤘습니다.
    -- 경로가 /api/v1/auth/oauth/{provider} 라 추가해도 게이트웨이는 바뀌지 않습니다.
    auth_provider    varchar(12)  NOT NULL,

    -- 제공자가 주는 계정 고유 식별자(구글은 id_token 의 sub)입니다.
    -- 로그인할 때마다 같은 값이 오므로 이것으로 계정을 찾습니다.
    -- LOCAL 계정은 NULL 입니다.
    --
    -- OAuth 의 code 와 제공자 access_token 은 저장하지 않습니다.
    -- 신원을 확인하는 순간까지만 쓰이고 그 뒤로는 우리 JWT 를 발급해 씁니다.
    provider_user_id varchar(64),

    -- USER · ADMIN
    -- 관리자 가입 API 는 없으며 DB 에서 직접 지정합니다.
    role             varchar(12)  NOT NULL,

    -- ACTIVE · WITHDRAWN
    -- 계정 정지 기능을 두지 않으므로 값이 둘뿐입니다.
    -- 악성 후기는 관리자가 후기를 지우고, 잘못된 제보는 REJECTED 로 처리합니다.
    --
    -- CHECK 제약을 걸지 않는 것은 의도입니다.
    -- 나중에 값이 늘어도 마이그레이션 없이 되며 검증은 애플리케이션이 합니다.
    status           varchar(12)  NOT NULL,

    -- 마지막 로그인 시각. 휴면 계정 판단이나 운영 통계에 씁니다.
    last_login_at    timestamp,

    -- 아래 6개 컬럼은 모든 테이블이 공통으로 가집니다.
    -- 공통 모듈의 BaseEntity 와 짝을 이루므로 빠뜨리면 기동 검증에 실패합니다.
    created_at       timestamp    NOT NULL,
    created_by       varchar(45)  NOT NULL,
    updated_at       timestamp    NOT NULL,
    updated_by       varchar(45)  NOT NULL,

    -- 이 테이블에서는 사용하지 않고 항상 NULL 입니다.
    -- 탈퇴는 status = 'WITHDRAWN' 하나로만 표현합니다.
    -- 둘 다 쓰면 조회 조건이 두 갈래로 갈려 한쪽만 고쳤을 때
    -- 탈퇴한 계정으로 로그인이 되는 사고가 납니다.
    deleted_at       timestamp,
    deleted_by       varchar(45)
);

-- 소셜 계정을 찾는 키입니다.
-- provider 를 함께 묶는 이유는 다른 제공자가 같은 sub 를 줄 수 있기 때문입니다.
--
-- LOCAL 계정은 provider_user_id 가 NULL 인데,
-- PostgreSQL 은 NULL 을 서로 다른 값으로 보므로 여러 행이 들어갑니다.
-- 여기서는 그것이 원하는 동작입니다.
CREATE UNIQUE INDEX uq_account_provider
    ON account (auth_provider, provider_user_id);

COMMENT ON TABLE account IS '계정. id 가 X-User-Id 로 전 서비스에 흐릅니다.';

-- =============================================================================
-- refresh_token_log
-- =============================================================================
-- 리프레시 토큰 발급 이력입니다. 토큰 본체는 여기에 없습니다.
--
-- 실제 토큰은 Redis 의 refresh:{jti} 에 있습니다.
-- 로그아웃할 때 즉시 무효화해야 하는데 DB 로는 만료까지 기다려야 하기 때문입니다.
-- 이 테이블은 "언제 어디서 발급됐는가" 만 남겨 장애 조사와 이상 접속 확인에 씁니다.
--
-- BaseEntity 를 상속하지 않습니다.
-- created_at 이 issued_at 과, deleted_at 이 revoked_at 과 같은 값이 되어
-- 12 개 컬럼 중 절반이 중복됩니다. account_id 가 누구인지 이미 담고 있어
-- created_by 도 필요 없습니다.
-- 사람이 만들고 고치는 데이터가 아니라 시스템이 쌓는 로그이므로
-- outbox·processed_event 와 같은 부류입니다.

CREATE TABLE refresh_token_log
(
    id           uuid        PRIMARY KEY,

    -- 외래키를 걸지 않습니다.
    -- 계정을 지우지 않고 status 로만 표시하므로 참조가 끊길 일이 없고,
    -- 로그 테이블이 계정 삭제를 막는 구조를 만들지 않기 위해서입니다.
    account_id   uuid        NOT NULL,

    -- JWT 의 jti 입니다. Redis 키 refresh:{jti} 와 같은 값이며
    -- 이 값으로 이력과 실제 토큰이 이어집니다.
    -- UUID 문자열이라 36 자입니다.
    token_id     varchar(36) NOT NULL,

    issued_at    timestamp   NOT NULL,
    expires_at   timestamp   NOT NULL,

    -- 로그아웃이나 강제 만료로 회수된 시각입니다. NULL 이면 아직 유효합니다.
    revoked_at   timestamp,

    -- IPv6 는 최대 45 자입니다(IPv4 매핑 표기 포함).
    ip_address   varchar(45),

    -- 브라우저가 보내는 값이라 길이 상한이 없어 text 로 둡니다.
    user_agent   text
);

-- 특정 계정의 발급 이력을 최신순으로 봅니다.
-- "이 사람이 언제 어디서 로그인했나" 를 확인하는 것이 이 테이블의 주 용도입니다.
CREATE INDEX idx_refresh_token_log_account
    ON refresh_token_log (account_id, issued_at DESC);

COMMENT ON TABLE refresh_token_log IS '리프레시 토큰 발급 이력. 토큰 본체는 Redis 에 있습니다.';

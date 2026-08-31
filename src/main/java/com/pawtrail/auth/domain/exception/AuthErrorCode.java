package com.pawtrail.auth.domain.exception;

import com.pawtrail.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 이 서비스의 도메인 에러 코드입니다.
 *
 * 공통 코드는 CommonErrorCode 에 있고 도메인 개념은 여기에 둡니다.
 * 공통에 두면 코드 하나를 더할 때마다 공통 모듈 재배포와 전 서비스 버전업이 필요해집니다.
 *
 * getCode 는 반드시 name 을 그대로 반환합니다.
 * 상수 이름이 곧 응답의 code 값이자 API 계약인데, 규칙을 어겨도 컴파일러가 잡지 못합니다.
 *
 * 메시지는 고정 문자열입니다. 동적인 값이 필요하면 응답 data 에 담습니다.
 */
public enum AuthErrorCode implements ErrorCode {

    // ── 회원가입 ──────────────────────────────────────────────
    // 이미 쓰이고 있는 이메일임
    //
    // 계정이 있다는 사실을 알려 주는 셈이지만 가입에서는 불가피함
    // 알려주지 않으면 사용자가 왜 가입이 안 되는지 알 수 없음
    // 비밀번호 재설정에서 계정 존재를 숨기는 것과는 상황이 다름
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),

    // 이메일 인증을 거치지 않고 가입을 시도함
    // 인증에 성공하면 Redis 에 표시가 남고 가입은 그것을 확인함
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해 주세요."),

    // ── 이메일 인증 ───────────────────────────────────────────
    // 코드가 틀렸거나 만료됨
    //
    // 둘을 구분해 알려주지 않음
    // 어느 쪽인지 알면 유효한 코드가 살아 있는지를 떠볼 수 있음
    INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않거나 만료되었습니다."),

    // 짧은 시간에 너무 많이 시도함
    //
    // 6자리 숫자는 백만 가지뿐이라 무차별 대입이 실제로 가능하므로 횟수를 셈
    // 5회를 넘기면 코드를 지우므로 다시 요청해야 함
    // 잠갔다 푸는 방식을 쓰지 않는 것은 해제 시각을 따로 관리해야 하고,
    // 다시 요청하면 새 코드가 오므로 사용자가 막히지도 않기 때문임
    TOO_MANY_VERIFICATION_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "인증 시도가 너무 많습니다. 코드를 다시 요청해 주세요."),

    // 메일을 보내지 못함
    //
    // * 회원가입 인증에서는 이 코드를 그대로 내보냄
    //   사용자가 오지 않는 코드를 기다리는 것보다 실패를 아는 편이 나음
    //
    // * 비밀번호 재설정에서는 내보내지 않고 로그만 남김
    //   거기는 계정이 없어도 항상 성공으로 응답하는데,
    //   발송 실패만 500 으로 나가면 그 자체가 "이 이메일은 가입돼 있다" 는 신호가 됨
    MAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요."),

    // ── 로그인 ────────────────────────────────────────────────
    // 이메일이 없거나 비밀번호가 틀림
    //
    // 두 경우에 같은 코드를 씀
    // 나누면 "이 이메일은 가입되어 있다" 가 드러나 회원 목록을 캐낼 수 있음
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),

    // 탈퇴한 계정임
    //
    // 이것은 알려 주어야 함
    // 로그인이 안 되는 이유를 모르면 사용자가 비밀번호를 계속 다시 입력하게 됨
    ACCOUNT_WITHDRAWN(HttpStatus.FORBIDDEN, "탈퇴한 계정입니다."),

    // 소셜 계정으로 가입해 비밀번호가 없음
    //
    // 비밀번호 변경과 재설정에서 씁니다.
    //
    // * 로그인에서는 쓰지 않습니다
    //   로그인은 아무나 부를 수 있는 경로라, 여기서 이 코드를 내보내면
    //   아무 비밀번호나 넣고 응답만 비교해도 그 이메일이 가입되어 있음을 알 수 있습니다
    //   위 LOGIN_FAILED 로 계정 존재를 숨긴 것이 그 자리에서 무너집니다
    //
    // * 변경은 이미 로그인한 사람이 부르므로 숨길 것이 없습니다
    //   자기 계정이 소셜이라는 것은 본인이 아는 사실입니다
    //
    // * 재설정은 계정 존재를 숨겨야 하므로 이 코드를 내보내지 않습니다
    //   대상이 아니어도 항상 성공으로 응답합니다
    PASSWORD_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "소셜 로그인으로 가입한 계정입니다."),

    // ── 토큰 ─────────────────────────────────────────────────
    // 쿠키에 리프레시 토큰이 없거나, 서명·만료가 잘못되었거나,
    // Redis 에 해당 jti 가 없음
    //
    // 마지막 경우가 로그아웃 이후에 옛 토큰을 다시 쓴 상황임
    // 셋을 구분하지 않는 이유는 어느 쪽이든 프론트가 할 일이 로그인 화면으로 보내는 것 하나이기 때문임
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),

    // ── 비밀번호 ──────────────────────────────────────────────
    // 변경할 때 확인용으로 받은 현재 비밀번호가 틀림
    CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),

    // ── 소셜 로그인 ───────────────────────────────────────────
    // 지원하지 않는 제공자로 요청함
    // 경로가 /oauth/{provider} 라 아무 값이나 들어올 수 있으므로 이 서비스가 판단함
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 로그인 방식입니다."),

    // 제공자와 주고받는 과정에서 실패함
    // 코드 교환 실패, 응답에 필요한 값이 없음 등이 여기로 옴
    OAUTH_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다."),

    // 콜백으로 돌아온 state 가 저장해 둔 값과 다름
    // CSRF 방지 장치이며 값이 어긋나면 남이 시작한 흐름일 수 있음
    INVALID_OAUTH_STATE(HttpStatus.BAD_REQUEST, "잘못된 접근입니다. 처음부터 다시 시도해 주세요."),

    // ── 계정 ─────────────────────────────────────────────────
    // 헤더의 식별자로 계정을 찾지 못함
    //
    // 게이트웨이가 검증한 토큰에서 나온 값이므로 정상 흐름에서는 나오지 않음
    // 탈퇴 직후 남아 있던 토큰으로 요청이 오는 경우 등에 나타남
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    AuthErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return this.httpStatus;
    }

    @Override
    public String getCode() {
        return this.name();
    }

    @Override
    public String getMessage() {
        return this.message;
    }
}

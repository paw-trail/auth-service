package com.pawtrail.auth.domain.enums;

/**
 * 계정이 어떤 방식으로 만들어졌는지 나타냅니다.
 *
 * LOCAL 은 이메일과 비밀번호로 직접 가입한 계정이고,
 * GOOGLE 은 구글 로그인으로 만들어진 계정입니다.
 *
 * 카카오와 네이버는 이메일 동의항목에 승인 절차가 있어 뒤로 미뤘습니다.
 * 나중에 추가하더라도 경로가 /api/v1/auth/oauth/{provider} 이므로
 * 게이트웨이 설정은 바뀌지 않고 이 열거형에 값을 하나 더하면 됩니다.
 */
public enum AuthProvider {

    // 이메일과 비밀번호로 가입한 계정임
    // password_hash 가 있고 provider_user_id 가 없음
    LOCAL,

    // 구글 로그인으로 만들어진 계정임
    // password_hash 가 없고 provider_user_id 에 구글의 sub 가 들어감
    GOOGLE;

    // 이 계정이 비밀번호를 가지는 방식인지 판단함
    // 비밀번호 변경과 재설정의 대상인지 가리는 데 씀
    public boolean hasPassword() {
        return this == LOCAL;
    }
}

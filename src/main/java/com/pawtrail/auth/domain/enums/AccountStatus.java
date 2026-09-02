package com.pawtrail.auth.domain.enums;

/**
 * 계정의 현재 상태입니다.
 *
 * 값이 둘뿐인 것은 계정 정지 기능을 두지 않기로 했기 때문입니다.
 * 악성 후기는 관리자가 후기를 지우고 잘못된 제보는 반려로 처리하므로
 * 계정을 막아야만 풀리는 상황이 없습니다.
 *
 * 컬럼에 CHECK 제약을 걸지 않았으므로 나중에 값이 늘어도
 * 마이그레이션 없이 이 열거형에 추가하면 됩니다.
 */
public enum AccountStatus {

    // 정상 계정임
    ACTIVE,

    // 탈퇴한 계정임
    //
    // 행을 지우지 않고 이 값으로 표시함
    // 같은 이메일로 다시 가입하는 것을 막아야 하고
    // account.withdrawn 이벤트를 각 서비스가 소비할 때까지 근거가 남아야 하기 때문임
    WITHDRAWN;

    // 로그인할 수 있는 상태인지 판단함
    public boolean canLogin() {
        return this == ACTIVE;
    }
}

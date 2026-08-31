package com.pawtrail.auth.application.dto.response;

import com.pawtrail.auth.domain.enums.AuthProvider;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.common.enums.Role;
import java.util.UUID;

/**
 * 계정 요약입니다. 로그인 응답과 GET /auth/me 가 같은 것을 씁니다.
 *
 * 로그인 응답에 토큰이 없는 것은 쿠키로 나가기 때문입니다.
 * 대신 이 요약을 담아 프론트가 로그인 직후 /auth/me 를 또 부르지 않아도 되게 합니다.
 * auth 가 이미 들고 있는 값이라 추가 조회가 없습니다.
 *
 * 닉네임은 담지 않습니다.
 * user_db 의 user_profile 이 소유자라 auth 가 그것을 담으려면 user 를 호출해야 하고,
 * 그것이 바로 hasPet 과 defaultPetId 를 뺀 이유였습니다.
 * 프론트는 닉네임이 필요하면 GET /users/me 를 부릅니다.
 */
public record AccountResponse(UUID accountId,
                              String email,
                              Role role,
                              AuthProvider authProvider) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getEmail(),
                account.getRole(),
                account.getAuthProvider());
    }
}

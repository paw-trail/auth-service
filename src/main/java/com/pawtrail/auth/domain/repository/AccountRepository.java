package com.pawtrail.auth.domain.repository;

import com.pawtrail.auth.domain.enums.AuthProvider;
import com.pawtrail.auth.domain.model.Account;
import java.util.Optional;
import java.util.UUID;

/**
 * 계정을 저장하고 찾아오는 약속입니다.
 *
 * 이 인터페이스에는 JPA 라는 단어가 나오지 않습니다.
 * 무엇을 할 수 있는지만 적고 어떻게 하는지는 infrastructure 가 정합니다.
 *
 * 탈퇴한 계정을 걸러내지 않습니다.
 * 부르는 쪽마다 처리가 다르기 때문입니다.
 *   로그인          탈퇴한 계정이면 그 사실을 알려야 함
 *   가입 중복 검사   탈퇴한 계정이어도 막아야 함, 같은 이메일 재가입 방지
 *   비밀번호 재설정  탈퇴한 계정이어도 조용히 성공 응답, 계정 존재를 숨겨야 함
 * 여기서 걸러 버리면 "없음" 과 "탈퇴함" 이 구분되지 않으므로
 * 상태 판단은 서비스가 Account.canLogin() 등으로 합니다.
 */
public interface AccountRepository {

    // 새로 만든 계정을 저장하거나 변경된 계정을 반영함
    Account save(Account account);

    // 식별자로 찾음
    // 게이트웨이가 넣어 준 X-User-Id 로 조회하는 경로가 여기를 씀
    Optional<Account> findById(UUID id);

    // 이메일로 찾음
    // 로그인과 비밀번호 재설정이 씀
    Optional<Account> findByEmail(String email);

    // 이미 쓰이고 있는 이메일인지 봄
    //
    // 탈퇴한 계정도 참으로 나옴
    // 행이 남아 있는 한 같은 이메일로 다시 가입할 수 없다는 뜻이며 그것이 의도임
    boolean existsByEmail(String email);

    // 소셜 계정을 찾음
    //
    // 제공자와 식별자를 함께 보는 이유는 서로 다른 제공자가 같은 값을 줄 수 있기 때문임
    // 구글이라면 providerUserId 자리에 id_token 의 sub 가 들어감
    Optional<Account> findByAuthProviderAndProviderUserId(AuthProvider authProvider,
                                                          String providerUserId);
}

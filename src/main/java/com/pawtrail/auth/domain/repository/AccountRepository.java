package com.pawtrail.auth.domain.repository;

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
 *   비밀번호 재설정  탈퇴한 계정이어도 조용히 성공 응답, 계정 존재를 숨겨야 함
 * 여기서 걸러 버리면 "없음" 과 "탈퇴함" 이 구분되지 않으므로
 * 상태 판단은 서비스가 Account.canLogin() 등으로 합니다.
 *
 * 가입 중복 검사는 이 목록에서 빠졌습니다.
 * 탈퇴할 때 이메일을 치환하므로 탈퇴한 계정은 애초에 그 조회에 걸리지 않습니다.
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
    // 탈퇴한 계정은 여기에 안 걸림
    // 탈퇴할 때 email 을 withdrawn+{id}@pawtrail.invalid 로 치환하기 때문임
    // 즉 한 번 탈퇴한 주소로 다시 가입할 수 있으며 그것이 의도임
    //
    // 재가입은 새 식별자를 받는 별개 계정임
    // 옛 계정의 데이터는 탈퇴 이벤트를 받은 각 서비스가 이미 지웠거나 지우는 중이고,
    // 그 소비자들이 전부 계정 식별자로 지우므로 새 계정을 건드릴 수가 없음
    boolean existsByEmail(String email);

    // 제공자 식별자로 찾음
    //
    // 소셜 로그인이 계정을 찾는 첫 경로임
    // 구글이라면 이 자리에 id_token 의 sub 가 들어가고, 로그인할 때마다 같은 값이 옴
    //
    // 제공자를 조건에 넣지 않는 이유
    //
    // 이미 있는 계정에 구글을 이으면 auth_provider 는 LOCAL 로 남음
    // 비밀번호를 그대로 쓸 수 있어야 하기 때문임
    // 그래서 (GOOGLE, sub) 조합으로 찾으면 이어 붙인 계정을 못 찾고,
    // 그 사람은 로그인할 때마다 계정이 새로 만들어지려다 이메일 중복에 걸림
    //
    // 지금은 이을 수 있는 제공자가 구글뿐이라 식별자만으로 충분함
    // 제공자를 늘리면 서로 다른 제공자가 같은 값을 줄 수 있으므로
    // 그때는 계정과 제공자를 잇는 표를 따로 두게 됨
    Optional<Account> findByProviderUserId(String providerUserId);
}

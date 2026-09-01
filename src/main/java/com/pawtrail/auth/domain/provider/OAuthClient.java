package com.pawtrail.auth.domain.provider;

import com.pawtrail.auth.domain.enums.AuthProvider;

/**
 * 소셜 제공자에게 사용자 신원을 확인받는다는 약속입니다.
 *
 * 이 인터페이스에는 구글도 HTTP 도 나오지 않습니다.
 * 무엇을 할 수 있는지만 적고 어떻게 하는지는 infrastructure 가 정합니다.
 * MailSender 와 SmtpMailSender 를 나눈 것과 같은 규칙입니다.
 *
 * 제공자마다 구현이 하나씩 생깁니다.
 * 구글은 신원 토큰 하나로 끝나지만 카카오는 사용자 정보 API 를 따로 불러야 하는 등
 * 주고받는 방식이 제각각이라, 설정만 늘려서 제공자가 붙는 구조가 아닙니다.
 *
 * 인가 주소를 만드는 일도 여기 둡니다.
 * 응답 형식과 스코프 표기가 제공자마다 다른 규격이라 그 지식이 한곳에 모여 있어야 합니다.
 * 서비스가 주소를 조립하면 제공자를 늘릴 때 서비스도 함께 고쳐야 합니다.
 */
public interface OAuthClient {

    /**
     * 이 구현이 담당하는 제공자입니다.
     *
     * 새로 만드는 계정의 auth_provider 값이 이것에서 나옵니다.
     * 서비스가 GOOGLE 을 직접 적지 않게 하려는 것입니다.
     */
    AuthProvider provider();

    /**
     * 사용자를 보낼 제공자 인가 주소를 만듭니다.
     *
     * @param state  되돌아온 요청이 우리가 시작한 것인지 확인할 값입니다.
     * @param nonce  받은 신원 토큰이 이 요청에 대한 응답인지 확인할 값입니다.
     */
    String buildAuthorizationUri(String state, String nonce);

    /**
     * 인가 코드를 사용자 신원으로 바꿉니다.
     *
     * 토큰을 받아 오되 저장하지 않습니다.
     * 제공자 API 를 대신 호출할 일이 없어 신원을 확인하는 순간까지만 필요합니다.
     *
     * @param nonce  인가를 시작할 때 만들어 두었던 값입니다.
     *               받은 토큰 안의 값과 다르면 이 요청에 대한 응답이 아닙니다.
     * @throws com.pawtrail.common.exception.CustomException
     *         교환에 실패했거나 토큰이 우리가 기대한 것과 다른 경우입니다.
     */
    OAuthUser exchange(String code, String nonce);

    /**
     * 제공자가 알려 준 사용자 신원입니다.
     *
     * 두 값만 담는 것은 우리가 쓰는 것이 그뿐이기 때문입니다.
     * 이름과 사진은 받아도 저장할 자리가 없습니다.
     * 프로필은 user 서비스가 소유하고, 소셜 가입은 닉네임 없이 시작해
     * 사용자가 프로필 설정 화면에서 직접 넣습니다.
     *
     * 별도 파일로 두지 않고 안에 둔 것은 MailSender 가 MailPurpose 를 안에 둔 것과 같습니다.
     * 이 약속 바깥에서는 쓰이지 않는 값이라 함께 있는 편이 읽기 좋습니다.
     *
     * @param providerUserId  제공자가 매기는 계정 고유 식별자입니다.
     *                        로그인할 때마다 같은 값이 오므로 이것으로 계정을 찾습니다.
     *                        account.provider_user_id 에 들어갑니다.
     * @param email           제공자가 확인해 준 이메일입니다.
     *                        기존 계정을 찾아 연결할 때 쓰는 열쇠입니다.
     */
    record OAuthUser(String providerUserId, String email) {
    }
}

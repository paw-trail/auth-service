package com.pawtrail.auth.application.dto.input;

/**
 * 소셜 로그인 콜백 입력입니다.
 *
 * 문자열이 넷 이어져 있어 이 서비스에서 순서 사고가 가장 위험한 자리였습니다.
 * 특히 state 와 cookieState 를 뒤집으면 두 값을 서로 비교하게 되어
 * 대조가 언제나 성공합니다. 브라우저 결속이 통째로 무의미해지는데 오류는 나지 않습니다.
 *
 * @param provider    경로에서 온 제공자 이름입니다. 지원 여부는 서비스가 판단합니다.
 * @param code        제공자가 붙여 보낸 인가 코드입니다.
 * @param state       제공자가 그대로 돌려준 값입니다.
 * @param cookieState 인가를 시작할 때 브라우저에 남긴 값입니다. 없으면 null 입니다.
 * @param client      발급 이력에 남길 접속 정보입니다.
 */
public record OAuthCallbackInput(String provider, String code, String state,
                                 String cookieState, ClientInfo client) {
}

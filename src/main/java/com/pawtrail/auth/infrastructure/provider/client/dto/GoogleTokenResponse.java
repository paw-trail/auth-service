package com.pawtrail.auth.infrastructure.provider.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 구글 토큰 교환 응답 중 우리가 쓰는 부분입니다.
 *
 * 응답에는 access_token, expires_in, scope, token_type 도 함께 옵니다.
 * 담지 않는 것은 쓰지 않기 때문입니다.
 * 액세스 토큰은 구글 API 를 대신 호출할 때 필요한데 우리는 로그인 여부만 확인합니다.
 *
 * ignoreUnknown 을 켜 두는 이유는 제공자가 필드를 늘려도 우리가 깨지지 않게 하려는 것입니다.
 * 이벤트를 소비할 때 같은 장치를 두는 것과 같은 이유입니다.
 *
 * @param idToken  사용자 신원이 담긴 서명된 토큰입니다.
 *                 이 안의 sub 와 email 을 꺼내 쓰며, 서명은 구글 공개키로 확인합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleTokenResponse(@JsonProperty("id_token") String idToken) {
}

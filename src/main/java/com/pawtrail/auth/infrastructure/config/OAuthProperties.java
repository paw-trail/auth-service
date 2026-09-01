package com.pawtrail.auth.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜 로그인 설정입니다.
 * config 저장소의 auth-service.yml 과 application-{env}.yml 에서 내려옵니다.
 *
 * 여기에는 우리 환경이 정하는 값만 있습니다.
 * 인가 주소, 토큰 주소, 공개키 주소, 발급자, 스코프는 제공자가 정하는 값이고
 * 환경이 바뀌어도 같으므로 제공자 구현체의 상수로 둡니다.
 * 한 곳에 섞어 두면 "이 중에 내가 고쳐도 되는 것이 무엇인지" 를 매번 판단하게 됩니다.
 *
 * 제공자를 Map 이 아니라 이름 붙은 필드로 받는 것은 의도입니다.
 * Map 으로 두면 설정만 추가하면 제공자가 늘어난다는 뜻으로 읽히는데 그렇지 않습니다.
 * 카카오는 id_token 이 아니라 사용자 정보 API 를 따로 불러야 해서 구현체를 새로 만들어야 하고,
 * 컨트롤러의 분기도 코드입니다. 구조가 사실을 말하게 두는 편이 낫습니다.
 *
 * @param frontendBaseUrl  로그인이 끝난 뒤 사용자를 돌려보낼 프론트 주소입니다.
 *                         * 경로는 여기 두지 않고 코드 상수로 둡니다
 *                           환경에 따라 달라지는 것은 호스트와 포트이지 경로가 아닙니다
 *                         * 브라우저가 찾아가는 주소이므로 컨테이너 이름을 쓸 수 없습니다
 *                           dev 프로파일에서도 이 값만은 localhost 입니다
 * @param google           구글 설정입니다. 지금 지원하는 제공자는 이것 하나뿐입니다.
 */
@ConfigurationProperties(prefix = "app.oauth")
public record OAuthProperties(String frontendBaseUrl, Provider google) {

    /**
     * 값이 비어 있으면 기동을 막습니다.
     *
     * frontendBaseUrl 이 없으면 콜백이 사용자를 어디로도 보내지 못합니다.
     * 그때 나타나는 증상은 흰 화면이거나 null 이 섞인 주소라
     * 원인이 설정 누락이라는 것이 드러나지 않습니다.
     *
     * 끝의 슬래시를 막는 이유는 주소를 이어 붙이기 때문입니다.
     * 값이 http://localhost:5173/ 이면 결과가 //login/success 가 되는데
     * 브라우저가 그것도 열기는 해서 오류 없이 이상하게 동작합니다.
     */
    public OAuthProperties {
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.oauth.frontend-base-url 이 비어 있습니다. "
                            + "config 저장소의 application-{env}.yml 을 확인하십시오");
        }
        if (frontendBaseUrl.endsWith("/")) {
            throw new IllegalStateException(
                    "app.oauth.frontend-base-url 은 / 로 끝나면 안 됩니다. 값=" + frontendBaseUrl);
        }
        if (google == null) {
            throw new IllegalStateException(
                    "app.oauth.google 이 비어 있습니다. "
                            + "config 저장소의 auth-service.yml 을 확인하십시오");
        }
    }

    /**
     * 경로 변수로 들어온 이름을 지원하는지 봅니다.
     *
     * 경로가 /oauth/{provider} 라 아무 값이나 들어올 수 있고,
     * 게이트웨이는 그 경로를 통째로 열어 두었을 뿐이라 판단하지 않습니다.
     * 지원 여부를 정하는 곳이 여기 하나여야 컨트롤러와 서비스가 같은 기준을 씁니다.
     *
     * 설정을 돌려주지 않고 참·거짓만 돌려주는 것은 부르는 쪽이 그것만 쓰기 때문입니다.
     * 제공자 설정은 그 제공자의 구현체가 만들어질 때 이미 받아 갑니다.
     */
    public boolean supports(String provider) {
        return GOOGLE.equals(provider);
    }

    /**
     * 경로 변수와 설정 이름으로 함께 쓰는 값입니다.
     *
     * 소문자인 것은 경로에 그대로 나타나기 때문입니다.
     *
     * AuthProvider.GOOGLE 에서 이름을 뽑아 쓰지 않는 것은 의도입니다.
     * 그렇게 하면 열거형 상수 이름을 바꾸는 순간 주소가 함께 바뀌는데,
     * 그 연결이 코드에 드러나지 않아 알아채기 어렵습니다.
     * TokenType 이 name 대신 값을 따로 둔 것과 같은 이유입니다.
     */
    public static final String GOOGLE = "google";

    /**
     * 제공자 하나의 설정입니다.
     *
     * @param clientId      제공자 콘솔에서 발급받은 클라이언트 식별자입니다.
     *                      * 비밀이 아니므로 config 저장소에 값 그대로 둡니다
     *                        인가 요청에 실려 브라우저 주소창에 그대로 나타나고,
     *                        남이 알아도 콘솔에 등록된 리디렉션 주소로만 되돌아갑니다
     *                        공개키를 저장소에 두고 개인키만 환경변수로 뺀 것과 같은 판단입니다
     * @param clientSecret  클라이언트 비밀값입니다.
     *                      토큰 교환 요청에 실려 서버끼리만 오가며 환경변수로 들어옵니다.
     * @param redirectUri   제공자가 사용자를 되돌려보낼 우리 주소입니다.
     *                      * 콘솔에 등록한 값과 한 글자도 다르면 redirect_uri_mismatch 가 납니다
     *                      * 토큰을 교환할 때도 같은 값을 다시 보냅니다
     *                        규격이 인가 때와 교환 때의 값이 같은지 확인하도록 정하고 있습니다
     */
    public record Provider(String clientId, String clientSecret, String redirectUri) {

        /**
         * 셋 중 하나라도 비면 기동을 막습니다.
         *
         * 값이 없으면 제공자에게 보낼 요청을 만들 수 없는데,
         * 그것이 실행 시점에 드러나면 사용자가 구글 화면까지 갔다가 오류를 봅니다.
         * 설정 누락은 기동할 때 알아채는 편이 낫습니다.
         */
        public Provider {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalStateException(
                        "app.oauth.google.client-id 가 비어 있습니다. "
                                + "config 저장소의 auth-service.yml 을 확인하십시오");
            }
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalStateException(
                        "app.oauth.google.client-secret 이 비어 있습니다. "
                                + "환경변수 AUTH_OAUTH_GOOGLE_CLIENT_SECRET 이 설정되었는지 확인하십시오");
            }
            if (redirectUri == null || redirectUri.isBlank()) {
                throw new IllegalStateException(
                        "app.oauth.google.redirect-uri 가 비어 있습니다. "
                                + "config 저장소의 application-{env}.yml 을 확인하십시오");
            }
        }
    }
}

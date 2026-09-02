package com.pawtrail.auth.infrastructure.provider.external;

import com.pawtrail.auth.domain.enums.AuthProvider;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.provider.OAuthClient;
import com.pawtrail.auth.infrastructure.config.OAuthProperties;
import com.pawtrail.auth.infrastructure.provider.external.dto.GoogleTokenResponse;
import com.pawtrail.common.exception.CustomException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 구글로 사용자 신원을 확인합니다.
 *
 * 주소와 스코프를 상수로 두는 것은 이 값들의 주인이 구글이기 때문입니다.
 * 환경이 바뀌어도 같고 우리가 고를 수 있는 값이 아니므로 설정으로 빼지 않습니다.
 * 설정에 섞어 두면 "이 중에 내가 고쳐도 되는 것이 무엇인지" 를 매번 판단하게 됩니다.
 *
 * 스프링의 소셜 로그인 지원을 쓰지 않는 이유
 *
 * 그 지원은 필터가 인가 흐름 전체를 맡아 세션까지 만들어 주는 것이 이점인데,
 * 우리는 자체 토큰을 쿠키에 심고 프론트 주소로 되돌려보내야 해서 그 마지막을 바꿔 써야 합니다.
 * 그러면 남는 이점이 적고, 보안 필터 체인에 손이 닿습니다.
 * 이 서비스는 공통 모듈의 기본 체인을 물러나게 하고 직접 채워 넣은 상태라 그 자리가 특히 민감합니다.
 *
 * 신원 토큰 검증을 직접 짜지 않는 이유
 *
 * 서명 확인에는 구글 공개키가 필요한데 그 키는 주기적으로 바뀝니다.
 * 스프링이 주는 검증기를 쓰면 키를 받아 두었다가 바뀌면 다시 받아 오는 일을 대신 해 줍니다.
 * 우리 토큰을 게이트웨이가 확인하는 것과 같은 계열이며 키를 얻는 방법만 다릅니다.
 */
@Slf4j
@Component
public class GoogleOAuthClient implements OAuthClient {

    // 사용자를 보낼 구글 인가 화면임
    private static final String AUTHORIZATION_URI = "https://accounts.google.com/o/oauth2/v2/auth";

    // 인가 코드를 토큰으로 바꾸는 곳임
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    // 신원 토큰의 서명을 확인할 공개키가 있는 곳임
    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    // 신원 토큰의 iss 에 들어오는 값임
    //
    // 두 가지를 허용하는 것은 구글이 흐름에 따라 스킴을 붙이거나 뗀 값을 보내기 때문임
    // 한쪽만 받으면 평소에는 되다가 특정 경우에만 실패하는 형태가 됨
    private static final Set<String> ALLOWED_ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");

    // 요청할 권한 범위임
    //
    // openid 는 신원 토큰을 달라는 뜻이고 email 은 이메일을 담아 달라는 뜻임
    // 이 둘은 민감 항목이 아니라 별도 승인 절차가 없고 사용자가 거부할 개념도 없어
    // 이메일이 항상 확보됨. 카카오·네이버를 뒤로 미룬 이유가 정확히 이 차이임
    private static final String SCOPE = "openid email";

    // 인가 요청에 실어 보낼 고정 값들임
    //
    // response_type 이 code 인 것은 인가 코드를 먼저 받고 토큰은 서버가 바꾸는 방식이기 때문임
    // 브라우저에 토큰이 직접 실리지 않으므로 주소창과 브라우저 기록에 남지 않음
    private static final String RESPONSE_TYPE = "code";
    private static final String GRANT_TYPE = "authorization_code";

    // 응답이 오지 않을 때 요청을 언제까지 붙잡아 둘지임
    //
    // 값이 없으면 무한정 기다리며, 그동안 요청을 처리하던 스레드가 묶임
    // 사용자 입장에서는 구글 화면을 지나온 뒤 아무 일도 안 일어나는 상태가 됨
    // 메일 발송에 같은 이유로 5초를 준 것과 같은 판단임
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final OAuthProperties.Provider config;
    private final RestClient restClient;
    private final JwtDecoder idTokenDecoder;

    public GoogleOAuthClient(OAuthProperties properties) {
        this.config = properties.google();

        // 이 클래스만 쓰는 호출기를 직접 만듦
        //
        // * 공용 빈으로 두지 않는 것은 상대가 바깥 서비스이기 때문임
        //   우리 서비스끼리 부를 때 붙이는 인증 헤더가 구글로 나가면 안 됨
        //   그 헤더를 어느 호출기에 붙일지는 아직 정해지지 않았고,
        //   여기서 공용 빈을 쓰면 나중에 그 결정이 이 호출에도 딸려 오게 됨
        //
        // * 시간 제한을 주려고 요청 공장을 지정함
        //   기본값은 제한이 없어 상대가 응답하지 않으면 그대로 묶임
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();

        // 구글 신원 토큰을 확인할 검증기임
        //
        // * 빈으로 등록하지 않는 것이 중요함
        //   이 서비스에는 우리 토큰을 확인하는 JwtDecoder 빈이 이미 있어서
        //   같은 타입을 하나 더 등록하면 주입할 때 어느 쪽인지 정해지지 않음
        //   TokenReader 가 그 빈을 받고 있으므로 갱신과 로그아웃이 함께 깨짐
        //
        // * 만드는 시점에 키를 받아 오지 않음
        //   처음 토큰을 확인할 때 받아 오므로 기동에 네트워크가 필요하지 않고
        //   테스트에서도 이 객체가 만들어지는 것만으로는 바깥에 나가지 않음
        this.idTokenDecoder = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.GOOGLE;
    }

    @Override
    public String buildAuthorizationUri(String state, String nonce) {
        // access_type 을 주지 않음
        //
        // 값을 offline 으로 주면 구글이 갱신용 토큰을 함께 발급함
        // 그것은 사용자를 대신해 구글 API 를 계속 부를 때 필요한 것인데
        // 우리는 로그인 순간에 신원만 확인하므로 받을 이유가 없음
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_URI)
                .queryParam("client_id", config.clientId())
                .queryParam("redirect_uri", config.redirectUri())
                .queryParam("response_type", RESPONSE_TYPE)
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .build()
                .encode()
                .toUriString();
    }

    @Override
    public OAuthUser exchange(String code, String nonce) {
        String idToken = requestIdToken(code);
        Jwt jwt = decode(idToken);

        verifyIssuer(jwt);
        verifyAudience(jwt);
        verifyNonce(jwt, nonce);
        verifyEmailIsOwned(jwt);

        String providerUserId = require(jwt, "sub");
        String email = require(jwt, "email");
        return new OAuthUser(providerUserId, email);
    }

    /**
     * 인가 코드를 토큰으로 바꿔 신원 토큰만 꺼냅니다.
     *
     * 리디렉션 주소를 다시 보내는 것은 규격이 그렇게 정하고 있기 때문입니다.
     * 인가를 시작할 때 쓴 값과 같은지 제공자가 확인합니다.
     */
    private String requestIdToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("redirect_uri", config.redirectUri());
        form.add("grant_type", GRANT_TYPE);

        GoogleTokenResponse response;
        try {
            response = restClient.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientException e) {
            // 상대가 400 을 주는 경우가 대부분임
            //
            // 이미 쓴 코드를 다시 보냈거나, 코드가 만료됐거나,
            // 리디렉션 주소가 인가 때와 다르거나, 시크릿이 틀린 경우가 여기로 옴
            // 사용자가 할 수 있는 일은 다시 시도하는 것 하나라 코드를 나누지 않고
            // 무엇이었는지는 로그에 남김
            log.warn("구글 토큰 교환에 실패했습니다. message={}", e.getMessage());
            throw new CustomException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }

        if (response == null || response.idToken() == null || response.idToken().isBlank()) {
            // 교환은 성공했는데 신원 토큰이 없는 경우임
            // openid 스코프를 빠뜨리면 이 모양이 되므로 상수를 먼저 확인할 것
            log.warn("구글 응답에 신원 토큰이 없습니다. 요청한 스코프를 확인하십시오");
            throw new CustomException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
        return response.idToken();
    }

    /**
     * 서명과 만료를 확인하고 내용을 꺼냅니다.
     *
     * 서명이 맞다는 것은 구글이 만든 토큰이라는 뜻일 뿐입니다.
     * 누구에게 준 것인지, 어느 요청에 대한 응답인지는 아래에서 따로 봅니다.
     */
    private Jwt decode(String idToken) {
        try {
            return idTokenDecoder.decode(idToken);
        } catch (JwtException e) {
            log.warn("구글 신원 토큰을 읽지 못했습니다. message={}", e.getMessage());
            throw new CustomException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }

    /**
     * 발급자가 구글이 맞는지 봅니다.
     */
    private void verifyIssuer(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        if (!ALLOWED_ISSUERS.contains(issuer)) {
            log.warn("구글 신원 토큰의 발급자가 다릅니다. iss={}", issuer);
            throw new CustomException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }

    /**
     * 우리에게 준 토큰이 맞는지 봅니다.
     *
     * 이 확인이 없으면 같은 제공자를 쓰는 다른 앱이 받은 토큰을 그대로 들이밀 수 있습니다.
     * 서명은 통과하므로 이 값을 보지 않으면 구분할 방법이 없습니다.
     */
    private void verifyAudience(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(config.clientId())) {
            log.warn("구글 신원 토큰의 대상이 우리가 아닙니다. aud={}", audience);
            throw new CustomException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }

    /**
     * 이 요청에 대한 응답이 맞는지 봅니다.
     *
     * 앞서 오간 토큰을 손에 넣어 다시 밀어 넣는 것을 서명만으로는 구분할 수 없습니다.
     * 인가를 시작할 때 우리가 만든 값이 그 안에 들어 있어야 이번 것입니다.
     */
    private void verifyNonce(Jwt jwt, String expected) {
        String actual = jwt.getClaimAsString("nonce");
        if (!expected.equals(actual)) {
            log.warn("구글 신원 토큰의 nonce 가 다릅니다");
            throw new CustomException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }

    /**
     * 이메일이 제공자가 확인해 준 것인지 봅니다.
     *
     * 이 값에 기대는 것이 계정 연결입니다.
     * 같은 이메일의 기존 계정을 찾으면 그 계정에 구글 식별자를 채워 연결하는데,
     * 확인되지 않은 이메일로 그렇게 하면 남의 계정에 들어가는 길이 열립니다.
     * 구글은 사실상 항상 참을 주지만, 연결의 안전이 이 값에 걸려 있으므로 직접 확인합니다.
     */
    private void verifyEmailIsOwned(Jwt jwt) {
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            log.warn("구글이 확인하지 않은 이메일입니다");
            throw new CustomException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
    }

    /**
     * 반드시 있어야 하는 값을 꺼냅니다.
     */
    private String require(Jwt jwt, String claim) {
        String value = jwt.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            log.warn("구글 신원 토큰에 필요한 값이 없습니다. claim={}", claim);
            throw new CustomException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }
        return value;
    }
}

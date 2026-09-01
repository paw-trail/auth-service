package com.pawtrail.auth.presentation;

import com.pawtrail.auth.application.service.OAuthLoginService;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.infrastructure.config.OAuthProperties;
import com.pawtrail.auth.infrastructure.security.CookieFactory;
import com.pawtrail.common.exception.CustomException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 소셜 로그인 API 입니다.
 *
 * 경로가 /api/v1/auth 아래인데도 AuthController 와 파일을 나눈 이유가 둘 있습니다.
 *
 * 하나. 반환하는 것이 다릅니다.
 *   AuthController 의 열 개 경로는 모두 CommonApiResponse 를 담은 JSON 을 돌려줍니다.
 *   여기 두 경로는 브라우저 주소창이 도착하는 곳이라 JSON 을 낼 수 없습니다.
 *   실패해도 마찬가지입니다. 오류 응답을 JSON 으로 내면 흰 화면에 그것이 그대로 보이고,
 *   이미 우리 페이지를 떠나 제공자에 갔다 오는 길이라 프론트가 잡을 자리가 없습니다.
 *   한 파일에 두 규약이 있으면 "이 컨트롤러는 무엇을 반환하는가" 에 답이 둘이 됩니다.
 *
 * 둘. 예외를 다루는 방식이 다릅니다.
 *   여기는 서비스가 던진 예외를 잡아 이동으로 바꿔야 합니다.
 *   같은 클래스에 예외 처리기를 두면 로그인과 회원가입의 예외까지 함께 잡혀
 *   JSON 으로 나가야 할 응답이 이동으로 바뀝니다.
 *   파일을 나누면 그 위험이 애초에 생기지 않습니다.
 *
 * 두 경로 모두 인증 없이 열려 있습니다.
 * 토큰을 받기 전에 부르는 요청이며 config 저장소의 permit-all 목록에
 * /api/v1/auth/oauth/** 한 줄로 덮여 있습니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    // 로그인을 마친 사람이 도착할 곳임
    // 이번에 계정이 만들어졌는지를 함께 실어 프론트가 프로필 설정으로 유도할 수 있게 함
    private static final String SUCCESS_PATH = "/login/success";

    // 실패했을 때 도착할 곳임
    private static final String ERROR_PATH = "/login/error";

    // 사용자가 스스로 취소했을 때 도착할 곳임
    //
    // 오류 화면으로 보내지 않는 것이 의도임
    // 본인이 취소를 누른 것이라 오류가 났다고 알리는 것은 틀린 안내이고,
    // 그다음에 할 일이 로그인이므로 로그인 화면이 도착지로 맞음
    private static final String CANCELED_PATH = "/login";

    // 실패 사유임
    //
    // 사용자가 할 수 있는 일이 갈리는 것만 구분함
    // state 가 어긋났든 서명이 틀렸든 교환이 실패했든 다시 시도하는 수밖에 없어
    // 그것들을 나누면 화면만 늘고 사용자가 얻는 것이 없음
    // 어느 단계에서 깨졌는지는 서버 로그에 남기며 그것을 볼 사람은 우리임
    private static final String REASON_WITHDRAWN = "WITHDRAWN";
    private static final String REASON_FAILED = "FAILED";

    private final OAuthLoginService oAuthLoginService;
    private final OAuthProperties oAuthProperties;
    private final CookieFactory cookieFactory;

    /**
     * 사용자를 제공자 로그인 화면으로 보냅니다.
     *
     * 지원하지 않는 제공자면 이동시키지 않고 오류로 응답합니다.
     * 이 경로는 프론트의 버튼에서만 열리므로 그런 요청은 주소를 직접 고쳐 넣은 경우이며,
     * 사용자가 아니라 만든 사람이 볼 오류입니다.
     */
    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String provider) {
        OAuthLoginService.AuthorizationRequest request =
                oAuthLoginService.buildAuthorizationUri(provider);

        // 이 흐름을 시작했다는 표시를 브라우저에 남김
        //
        // 저장소에만 두면 "우리가 발급한 값인가" 까지만 확인됨
        // 콜백에서 이 쿠키와 되돌아온 값을 대조해야 "이 브라우저가 시작한 값인가" 가 갈림
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cookieFactory.oauthState(request.state()).toString())
                .location(URI.create(request.authorizationUri()))
                .build();
    }

    /**
     * 제공자가 되돌려보낸 요청을 받아 로그인을 마칩니다.
     *
     * 성공하면 토큰 두 개를 쿠키로 심고 프론트로 보냅니다.
     * 실패해도 프론트로 보내되 사유를 주소에 실어 화면이 무엇을 말할지 정하게 합니다.
     *
     * 모든 값을 없어도 되는 것으로 받습니다.
     * 반드시 있어야 하는 것으로 두면 값이 빠졌을 때 스프링이 먼저 오류 응답을 만들고,
     * 그것이 JSON 이라 아래 매핑을 거치지 않은 채 사용자에게 그대로 보입니다.
     *
     * @param code  인가 코드입니다. 제공자가 사용자를 되돌려보내며 붙입니다.
     * @param state 우리가 시작한 흐름인지 확인하는 값입니다.
     * @param error 사용자가 동의를 취소했거나 제공자 쪽에서 막힌 경우에만 붙습니다.
     */
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = CookieFactory.OAUTH_STATE, required = false) String cookieState,
            HttpServletRequest servletRequest) {

        // 제공자가 오류를 실어 보낸 경우임
        //
        // 값이 access_denied 이면 사용자가 동의 화면에서 취소를 누른 것이고
        // 그 밖의 값은 요청 자체가 제공자에게 거부된 것임
        // 어느 쪽이든 인가 코드가 없으므로 아래 흐름을 탈 수 없음
        if (error != null) {
            return handleProviderError(error);
        }

        if (code == null || state == null) {
            // 정상 흐름에서는 나올 수 없음
            // 주소를 직접 열었거나 제공자가 규격과 다르게 응답한 경우임
            log.warn("소셜 로그인 콜백에 필요한 값이 없습니다. code={}, state={}",
                    code != null, state != null);
            return redirectToFrontend(ERROR_PATH, "reason", REASON_FAILED);
        }

        OAuthLoginService.OAuthLoginResult result;
        try {
            result = oAuthLoginService.callback(
                    provider,
                    code,
                    state,
                    cookieState,
                    // 지금은 넣지 않음
                    // 앞에 게이트웨이가 있어 getRemoteAddr 이 게이트웨이 주소를 돌려주며,
                    // 원래 주소를 어떻게 얻을지는 nginx 를 붙일 때 함께 정함
                    null,
                    servletRequest.getHeader(HttpHeaders.USER_AGENT));
        } catch (CustomException e) {
            return handleFailure(e);
        } catch (Exception e) {
            // 우리가 예상한 실패가 아닌 것도 여기서 이동으로 바꿈
            //
            // 이 클래스는 JSON 을 내지 않기로 한 자리라 그대로 흘려보내면
            // 전역 처리기가 만든 JSON 이 브라우저에 그대로 보임
            //
            // 실제로 닿는 길이 있음
            // Account.linkGoogle 은 이미 다른 식별자와 연결된 계정에서 예외를 던지는데,
            // 제공자가 같은 이메일에 다른 식별자를 주면 식별자 조회가 빗나가고
            // 이메일 조회가 기존 계정을 찾아 그 자리에 닿음
            // 콜백이 동시에 둘 들어와 유일성 제약이 부딪힐 때도 같음
            //
            // 나올 수 없는 상황인지와 났을 때 어떻게 보이는지는 다른 문제임
            // 원인은 스택트레이스까지 남겨 우리가 보고, 사용자에게는 화면을 보여줌
            log.error("소셜 로그인 처리 중 예상하지 못한 오류가 났습니다", e);
            return redirectToFrontend(ERROR_PATH, "reason", REASON_FAILED);
        }

        ResponseCookie accessCookie = cookieFactory.accessToken(
                result.accessToken().value(), result.accessToken().expiresAt());
        ResponseCookie refreshCookie = cookieFactory.refreshToken(
                result.refreshToken().value(), result.refreshToken().expiresAt());

        String location = frontendUri(SUCCESS_PATH, "isNew", String.valueOf(result.isNew()));

        // 쿠키를 각각 헤더로 붙임
        //
        // Set-Cookie 는 하나의 헤더에 여러 값을 담을 수 없어 줄이 여러 개가 됨
        // header 를 세 번 부르는 것이 그 때문이며 이동 응답에도 쿠키는 그대로 실림
        //
        // 상태 쿠키는 여기서 지움
        // 이 흐름이 끝났으므로 남겨 두면 다음 로그인 때 옛 값이 실려 와 어긋남
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expireOAuthState().toString())
                .location(URI.create(location))
                .build();
    }

    /**
     * 제공자가 오류를 실어 보낸 경우를 처리합니다.
     */
    private ResponseEntity<Void> handleProviderError(String error) {
        if ("access_denied".equals(error)) {
            // 취소는 오류가 아니므로 조용히 로그인 화면으로 돌려보냄
            // 사유를 실어 보내지 않는 것은 화면에 말할 것이 없기 때문임
            log.debug("사용자가 소셜 로그인을 취소했습니다");
            return redirectToFrontend(CANCELED_PATH, null, null);
        }
        log.warn("제공자가 오류를 반환했습니다. error={}", error);
        return redirectToFrontend(ERROR_PATH, "reason", REASON_FAILED);
    }

    /**
     * 서비스가 던진 예외를 이동으로 바꿉니다.
     *
     * 탈퇴한 계정만 갈라내는 이유는 그때만 사용자가 할 일이 다르기 때문입니다.
     * 나머지는 전부 다시 시도하는 수밖에 없어 한 가지로 모읍니다.
     */
    private ResponseEntity<Void> handleFailure(CustomException e) {
        if (e.getErrorCode() == AuthErrorCode.ACCOUNT_WITHDRAWN) {
            return redirectToFrontend(ERROR_PATH, "reason", REASON_WITHDRAWN);
        }
        log.warn("소셜 로그인에 실패했습니다. code={}", e.getErrorCode().getCode());
        return redirectToFrontend(ERROR_PATH, "reason", REASON_FAILED);
    }

    // 프론트 주소를 만들어 이동 응답으로 감쌈
    //
    // 상태 쿠키를 함께 지움
    // 콜백에서 나가는 길이 성공 하나와 이 메서드뿐이라 두 곳만 챙기면 빠뜨릴 자리가 없음
    private ResponseEntity<Void> redirectToFrontend(String path, String name, String value) {
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expireOAuthState().toString())
                .location(URI.create(frontendUri(path, name, value)))
                .build();
    }

    // 프론트 주소를 만듦
    //
    // 경로를 설정이 아니라 코드에 두는 것은 환경에 따라 달라지지 않기 때문임
    // 설정에는 호스트와 포트만 있고 배포에서 도메인으로 바뀌어도 경로는 그대로임
    private String frontendUri(String path, String name, String value) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUriString(oAuthProperties.frontendBaseUrl()).path(path);
        if (name != null) {
            builder.queryParam(name, value);
        }
        return builder.build().encode().toUriString();
    }

}

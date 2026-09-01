package com.pawtrail.auth.presentation;

import com.pawtrail.auth.application.dto.response.AccountResponse;
import com.pawtrail.auth.application.service.AuthService;
import com.pawtrail.auth.application.service.EmailVerificationService;
import com.pawtrail.auth.application.service.LogoutService;
import com.pawtrail.auth.application.service.PasswordResetService;
import com.pawtrail.auth.application.service.RefreshService;
import com.pawtrail.auth.infrastructure.security.CookieFactory;
import com.pawtrail.auth.presentation.request.EmailVerificationRequest;
import com.pawtrail.auth.presentation.request.LoginRequest;
import com.pawtrail.auth.presentation.request.SignupRequest;
import com.pawtrail.common.response.CommonApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API 입니다.
 *
 * 이 경로들은 게이트웨이가 인증 헤더를 넣지 않고 통과시킵니다.
 * 토큰을 받기 전에 불러야 하는 요청이기 때문이며,
 * 같은 목록이 config 저장소의 app.gateway.permit-all 과 app.auth.permit-all 양쪽에 있습니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshService refreshService;
    private final LogoutService logoutService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final CookieFactory cookieFactory;

    /**
     * 회원가입입니다.
     *
     * 가입한 뒤 자동으로 로그인시키지 않습니다.
     * 쿠키를 심으려면 토큰을 발급해야 하는데, 방금 비밀번호를 정한 사람이
     * 그것으로 한 번 들어와 보는 편이 낫다고 보았습니다.
     */
    @PostMapping("/signup")
    public ResponseEntity<CommonApiResponse<AccountResponse>> signup(
            @Valid @RequestBody SignupRequest request) {

        AccountResponse response = authService.signup(
                request.email(), request.password(), request.nickname());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(CommonApiResponse.success(response));
    }

    /**
     * 로그인입니다.
     *
     * 토큰은 응답 바디가 아니라 쿠키로 나갑니다.
     * 브라우저 스크립트가 값을 읽지 못하게 하기 위함이며,
     * 그래서 프론트는 로그인 여부를 GET /auth/me 로만 알 수 있습니다.
     */
    @PostMapping("/login")
    public ResponseEntity<CommonApiResponse<AccountResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {

        // 어떤 주소에서 왔는지를 확인하기 위한 로그입니다.
        //
        // 앞에 게이트웨이가 있어 getRemoteAddr 은 게이트웨이 주소를 돌려줍니다.
        // 원래 주소는 X-Forwarded-For 헤더에 실려 오는데, 그것이 실제로 도착하는지를
        // 아직 확인하지 못했습니다. 받는 쪽이 있어야 보이는 값이기 때문입니다.
        //
        // 확인이 끝나면 이 로그를 지우고 refresh_token_log 의 ip_address 를 채우는 방식을 정합니다.
        log.info("로그인 요청 X-Forwarded-For={}, User-Agent={}, remoteAddr={}",
                servletRequest.getHeader("X-Forwarded-For"),
                servletRequest.getHeader(HttpHeaders.USER_AGENT),
                servletRequest.getRemoteAddr());

        AuthService.LoginResult result = authService.login(
                request.email(),
                request.password(),
                // 지금은 넣지 않습니다. 위 로그로 무엇을 넣을지 정한 뒤에 채웁니다.
                // 컬럼이 비어도 되게 해 두었으므로 그때까지 null 로 둡니다.
                null,
                servletRequest.getHeader(HttpHeaders.USER_AGENT));

        ResponseCookie accessCookie = cookieFactory.accessToken(
                result.accessToken().value(), result.accessToken().expiresAt());
        ResponseCookie refreshCookie = cookieFactory.refreshToken(
                result.refreshToken().value(), result.refreshToken().expiresAt());

        // 쿠키 두 개를 각각 헤더로 붙입니다.
        //
        // Set-Cookie 는 하나의 헤더에 여러 값을 담을 수 없어 줄이 두 개가 됩니다.
        // add 를 두 번 부르는 것이 그 때문이며 set 을 쓰면 뒤엣것이 앞을 덮습니다.
        return ResponseEntity
                .ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(CommonApiResponse.success(result.account()));
    }

    /**
     * 액세스 토큰을 다시 발급합니다.
     *
     * 이 경로는 인증 없이 열려 있습니다.
     * 액세스 토큰이 만료된 상태로 부르는 요청이라 인증을 걸면 아무도 갱신할 수 없습니다.
     *
     * 리프레시 토큰도 함께 새로 발급되어 쿠키 두 개가 모두 갱신됩니다.
     * 응답 바디는 비어 있습니다.
     * 프론트가 이것을 부르는 자리는 401 인터셉터 안이고 거기서 필요한 것은 새 쿠키뿐이며,
     * 로그인 여부는 GET /auth/me 로만 판단한다는 규칙을 흐리지 않기 위해서입니다.
     */
    @PostMapping("/refresh")
    public ResponseEntity<CommonApiResponse<Void>> refresh(
            @CookieValue(name = CookieFactory.REFRESH_TOKEN, required = false)
            String refreshTokenValue,
            HttpServletRequest servletRequest) {

        RefreshService.RefreshResult result = refreshService.refresh(
                refreshTokenValue,
                // 로그인과 같은 이유로 아직 비워 둡니다.
                // X-Forwarded-For 를 무엇으로 읽을지 정해지면 그때 함께 채웁니다.
                null,
                servletRequest.getHeader(HttpHeaders.USER_AGENT));

        ResponseCookie accessCookie = cookieFactory.accessToken(
                result.accessToken(), result.accessExpiresAt());
        ResponseCookie refreshCookie = cookieFactory.refreshToken(
                result.refreshToken(), result.refreshExpiresAt());

        return ResponseEntity
                .ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(CommonApiResponse.success(null));
    }

    /**
     * 로그아웃입니다.
     *
     * 어떤 경우에도 성공으로 응답합니다.
     * 쿠키가 없어도, 토큰이 만료됐어도, 읽을 수 없는 값이어도 마찬가지입니다.
     * 여기서 401 을 내보내면 클라이언트가 지우지 못하는 쿠키를 들고 갇힙니다.
     *
     * 지우는 쿠키의 속성은 만들 때와 같아야 합니다.
     * 경로가 다르면 브라우저가 다른 쿠키로 보아 원래 것이 그대로 남는데,
     * 오류가 나지 않으므로 "로그아웃했는데 토큰이 살아 있는" 상태를 알아채기 어렵습니다.
     * CookieFactory 가 만들 때와 같은 값으로 만료 쿠키를 찍어 주는 것이 그 때문입니다.
     *
     * 액세스 토큰은 되돌릴 수 없어 만료까지 그대로 쓸 수 있습니다.
     * 쿠키를 지우면 브라우저에서는 사라지지만 이미 복사된 값까지 막지는 못하며,
     * 수명을 30분으로 짧게 둔 것이 유일한 대응입니다.
     */
    @PostMapping("/logout")
    public ResponseEntity<CommonApiResponse<Void>> logout(
            @CookieValue(name = CookieFactory.REFRESH_TOKEN, required = false)
            String refreshTokenValue) {

        logoutService.logout(refreshTokenValue);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expireAccessToken().toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expireRefreshToken().toString())
                .body(CommonApiResponse.success(null));
    }

    /**
     * 회원가입 인증 코드를 보냅니다.
     *
     * 이미 쓰는 이메일이면 그 사실을 알려 줍니다.
     * 아래 비밀번호 재설정과 정반대인데, 가입은 알려 주지 않으면
     * 사용자가 왜 진행이 안 되는지 알 수 없기 때문입니다.
     */
    @PostMapping("/email/verify-request")
    public ResponseEntity<CommonApiResponse<Void>> sendSignupCode(
            @Valid @RequestBody EmailVerificationRequest.SendCode request) {

        emailVerificationService.sendCode(request.email());
        return ResponseEntity.ok(CommonApiResponse.success(null));
    }

    /**
     * 회원가입 인증 코드를 확인합니다.
     *
     * 통과하면 표시가 남고 회원가입이 그것을 확인합니다.
     * 표시는 30분간 유효하므로 그 안에 가입을 마쳐야 합니다.
     */
    @PostMapping("/email/verify")
    public ResponseEntity<CommonApiResponse<Void>> verifySignupCode(
            @Valid @RequestBody EmailVerificationRequest.VerifyCode request) {

        emailVerificationService.verify(request.email(), request.code());
        return ResponseEntity.ok(CommonApiResponse.success(null));
    }

    /**
     * 비밀번호 재설정 코드를 보냅니다.
     *
     * 어떤 경우에도 성공으로 응답합니다.
     * 가입되지 않은 이메일이어도, 소셜 계정이어도, 발송이 실패해도 마찬가지입니다.
     * 응답이 갈리면 그 차이만으로 어떤 이메일이 가입되어 있는지 알아낼 수 있습니다.
     */
    @PostMapping("/password/reset-request")
    public ResponseEntity<CommonApiResponse<Void>> sendResetCode(
            @Valid @RequestBody EmailVerificationRequest.SendCode request) {

        passwordResetService.sendCode(request.email());
        return ResponseEntity.ok(CommonApiResponse.success(null));
    }

    /**
     * 코드를 확인하고 비밀번호를 바꿉니다.
     *
     * 로그인 상태에서 바꾸는 것과 다른 기능입니다.
     * 그쪽은 현재 비밀번호로 본인을 확인하지만 여기는 그것을 모르는 상태라
     * 메일이 본인 확인 수단입니다.
     */
    @PostMapping("/password/reset")
    public ResponseEntity<CommonApiResponse<Void>> resetPassword(
            @Valid @RequestBody EmailVerificationRequest.ResetPassword request) {

        passwordResetService.reset(request.email(), request.code(), request.newPassword());
        return ResponseEntity.ok(CommonApiResponse.success(null));
    }
}

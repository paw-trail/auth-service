package com.pawtrail.auth.presentation;

import com.pawtrail.auth.application.dto.response.AccountResponse;
import com.pawtrail.auth.application.service.AuthService;
import com.pawtrail.auth.application.service.EmailVerificationService;
import com.pawtrail.auth.application.service.PasswordResetService;
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

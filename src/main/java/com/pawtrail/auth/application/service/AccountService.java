package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.dto.response.AccountResponse;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.common.exception.CustomException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 사람이 자기 계정을 다루는 일을 처리합니다.
 *
 * AuthService 와 무엇이 다른가
 *
 * 그쪽은 "내가 누구인지 증명하는" 일을 합니다. 회원가입과 로그인이 거기 있습니다.
 * 이쪽은 "이미 증명한 사람이 자기 것을 보고 고치는" 일을 합니다.
 * 들어오는 시점에 이미 게이트웨이가 신원을 확인해 헤더로 넘겨 준 상태입니다.
 *
 * 그래서 이 클래스의 메서드는 전부 계정 식별자를 인자로 받습니다.
 * 이메일이나 비밀번호로 사람을 찾는 일이 없습니다. 그것은 증명하는 쪽의 일입니다.
 *
 * 탈퇴도 여기로 옵니다.
 * 경로가 /auth/me 로 같고, 본인이 자기 계정에 하는 일이라는 점도 같습니다.
 *
 * 왜 AuthService 에 합치지 않는가
 *
 * 서비스를 나누는 기준이 "무엇을 하는가" 이기도 하지만 트랜잭션 경계이기도 합니다.
 * 특히 TokenRevokeService 는 전파 방식이 다른 진입점을 두 개 가지는데,
 * 같은 클래스 안에서 부르면 프록시를 거치지 않아 그 설정이 통째로 무시됩니다.
 * 그때 나는 일이 "폐기했다고 로그는 찍혔는데 실제로는 안 된" 상태라 드러나지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevokeService tokenRevokeService;

    /**
     * 내 계정 정보를 봅니다.
     *
     * 쿠키가 HttpOnly 라 프론트는 토큰 안을 읽을 수 없습니다.
     * 그래서 로그인했는지, 누구로 로그인했는지를 확인하는 유일한 창구가 여기입니다.
     *
     * 닉네임은 담기지 않습니다. user_db 의 user_profile 이 소유자이며,
     * 그것까지 담으려면 auth 가 user 를 호출해야 해서 이 서비스가 남을 부르지 않는다는
     * 성질이 깨집니다. 프론트는 닉네임이 필요하면 GET /users/me 를 따로 부릅니다.
     */
    @Transactional(readOnly = true)
    public AccountResponse getMyAccount(UUID accountId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    // 게이트웨이가 넣어 준 식별자로 찾았는데 없는 경우임
                    //
                    // 토큰은 우리가 발급한 것이고 서명도 통과했는데 계정만 없는 상태라
                    // 정상적인 상황이 아님. 데이터가 지워졌거나 다른 데이터베이스를 보고 있음
                    log.warn("헤더의 계정을 찾지 못했습니다. accountId={}", accountId);
                    return new CustomException(AuthErrorCode.ACCOUNT_NOT_FOUND);
                });

        // 탈퇴한 계정도 그대로 돌려줌
        //
        // 여기까지 왔다는 것은 탈퇴 전에 발급된 토큰이 아직 살아 있다는 뜻임
        // 상태를 숨기면 프론트가 "왜 아무것도 안 되는지" 를 알 수 없으므로
        // status 를 그대로 보여 주고 화면이 판단하게 함
        return AccountResponse.from(account);
    }

    /**
     * 비밀번호를 바꿉니다.
     *
     * 비밀번호 재설정과 다른 기능입니다.
     * 그쪽은 비밀번호를 잊은 사람이 메일로 본인을 증명하지만,
     * 여기는 로그인한 사람이 현재 비밀번호로 증명합니다.
     *
     * 둘을 합치면 안 됩니다.
     * 현재 비밀번호 확인이 없는 변경 경로가 생기면 토큰만 있으면 비밀번호를 바꿀 수 있고,
     * 액세스 토큰은 모든 요청에 실려 나가 노출 면적이 넓습니다.
     *
     * 바꾼 뒤에는 이전에 발급된 토큰을 전부 무효로 만듭니다.
     * 본인의 것도 함께 끊기므로 다시 로그인해야 합니다.
     * 쿠키를 지우는 일은 컨트롤러가 합니다. 그것이 HTTP 의 사정이기 때문입니다.
     */
    @Transactional
    public void changePassword(UUID accountId, String currentRawPassword, String newRawPassword) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    log.warn("헤더의 계정을 찾지 못했습니다. accountId={}", accountId);
                    return new CustomException(AuthErrorCode.ACCOUNT_NOT_FOUND);
                });

        // 소셜 계정을 먼저 걸러냄
        //
        // 이것이 없으면 아래 changePassword 가 IllegalStateException 을 던지는데,
        // 그것은 우리 코드가 계약을 어겼을 때 쓰는 예외라 500 으로 나감
        // 소셜 계정이 이 API 를 부르는 것은 버그가 아니라 정상적으로 일어날 수 있는 일이므로
        // 무엇이 문제인지 알려 주는 응답을 내보내는 것이 맞음
        //
        // 판단을 hasPassword 로 하는 것은 비밀번호를 쓰는 제공자가 늘어나도 그대로 맞기 때문임
        if (!account.getAuthProvider().hasPassword()) {
            throw new CustomException(AuthErrorCode.PASSWORD_NOT_SUPPORTED);
        }

        // 탈퇴한 계정은 막음
        //
        // 탈퇴 전에 발급된 토큰이 아직 살아 있으면 여기까지 올 수 있음
        // 되살리는 기능이 없으므로 비밀번호를 바꿀 이유도 없음
        if (!account.canLogin()) {
            throw new CustomException(AuthErrorCode.ACCOUNT_WITHDRAWN);
        }

        // 현재 비밀번호로 본인을 확인함
        //
        // 로그인과 달리 계정을 숨길 이유가 없어 사유를 그대로 알려 줌
        // 이미 그 계정으로 들어와 있는 사람이라 새로 드러나는 사실이 없음
        if (!passwordEncoder.matches(currentRawPassword, account.getPasswordHash())) {
            throw new CustomException(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
        }

        account.changePassword(passwordEncoder.encode(newRawPassword));

        // 이전에 발급된 토큰을 전부 무효로 만듦
        //
        // 비밀번호를 바꾸는 이유가 대개 "누가 내 계정에 들어온 것 같다" 인데
        // 바꿔도 기존 세션이 살아 있으면 그 행위의 목적이 절반만 달성됨
        //
        // 같은 트랜잭션에서 돌아야 함
        // 새 트랜잭션으로 하면 계정을 데이터베이스에서 다시 읽어 고치는데,
        // 여기 있는 인스턴스가 나중에 갱신되면서 그 값을 옛것으로 덮음
        //
        // 이미 나가 있는 액세스 토큰은 이것으로 막히지 않음
        // 게이트웨이가 서명만 보고 통과시키므로 만료될 때까지(30분) 그대로 쓰임
        tokenRevokeService.revokeAll(account.getId(), "비밀번호 변경");

        log.info("비밀번호를 변경했습니다. accountId={}", account.getId());
    }
}

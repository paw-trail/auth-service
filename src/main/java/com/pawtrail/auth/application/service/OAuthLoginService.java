package com.pawtrail.auth.application.service;

import com.pawtrail.auth.application.dto.input.OAuthCallbackInput;
import com.pawtrail.auth.domain.event.payload.AccountCreatedEvent;
import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.provider.OAuthClient;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.domain.repository.OAuthStateStore;
import com.pawtrail.auth.infrastructure.config.OAuthProperties;
import com.pawtrail.auth.infrastructure.security.TokenProvider;
import com.pawtrail.common.exception.CustomException;
import com.pawtrail.common.message.outbox.OutboxEventRecorder;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 로그인을 처리합니다.
 *
 * 흐름이 두 번의 요청으로 나뉩니다.
 * 먼저 사용자를 제공자로 보내고, 제공자가 인가 코드를 붙여 되돌려보내면 그것을 받습니다.
 * 그 사이에 서버가 기억해 두는 것이 state 와 nonce 이며 저장소에 항목 하나로 남습니다.
 *
 * 계정을 찾는 순서가 이 서비스의 핵심입니다.
 *
 *   제공자 식별자로 찾음   이미 이 방식으로 들어온 적이 있는 계정입니다
 *   없으면 이메일로 찾음   이메일로 가입해 쓰던 사람이 처음 소셜로 들어온 경우입니다
 *   그래도 없으면 만듦     소셜로 처음 가입하는 경우입니다
 *
 * 가운데 갈래에서 계정을 잇는 것이 안전한 이유는 양쪽 다 이메일 소유가 확인됐기 때문입니다.
 * 이메일 가입은 인증 코드를 통과해야 하고, 제공자는 확인한 이메일만 넘겨줍니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthLoginService {

    // state 와 nonce 의 길이임
    //
    // 값을 맞힐 수 있으면 두 장치가 모두 무의미해지므로 넉넉히 둠
    // 32바이트를 인코딩하면 43글자가 되어 주소창에 실려도 부담이 없음
    private static final int RANDOM_BYTES = 32;

    // 주소에 그대로 실리므로 + / = 가 없는 방식으로 인코딩함
    // 그렇지 않으면 값이 인코딩되었다 풀리는 과정에서 달라질 여지가 생김
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthProperties oAuthProperties;
    private final OAuthClient oAuthClient;
    private final OAuthStateStore oAuthStateStore;
    private final AccountRepository accountRepository;
    private final OutboxEventRecorder outboxEventRecorder;
    private final TokenIssueService tokenIssueService;

    /**
     * 인가를 시작하는 데 필요한 값입니다.
     *
     * @param authorizationUri 사용자를 보낼 제공자 주소입니다.
     * @param state            이 흐름을 시작한 브라우저에 남길 값입니다.
     *                         부르는 쪽이 쿠키로 심고, 콜백에서 되돌아온 값과 대조합니다.
     */
    public record AuthorizationRequest(String authorizationUri, String state) {
    }

    /**
     * 사용자를 보낼 제공자 인가 주소를 만듭니다.
     *
     * 트랜잭션이 없습니다. 데이터베이스에 쓰는 것이 없고 저장소에만 남기기 때문입니다.
     *
     * state 를 함께 돌려주는 것은 부르는 쪽이 그것을 쿠키로 심어야 하기 때문입니다.
     * 쿠키를 여기서 만들지 않는 것은 그것이 HTTP 의 사정이라서입니다.
     *
     * @throws CustomException 지원하지 않는 제공자인 경우입니다.
     */
    public AuthorizationRequest buildAuthorizationUri(String provider) {
        requireSupported(provider);

        String state = randomValue();
        String nonce = randomValue();

        // 항목 하나에 둘을 함께 담음
        //
        // 따로 저장하면 어느 nonce 가 어느 state 의 짝인지 이어 줄 것이 없어지고
        // 키가 둘이 되어 만료가 어긋날 여지도 생김
        oAuthStateStore.save(state, nonce);

        return new AuthorizationRequest(oAuthClient.buildAuthorizationUri(state, nonce), state);
    }

    /**
     * 콜백 결과입니다.
     *
     * @param isNew 이번에 계정이 만들어졌는지입니다.
     *              프론트가 신규 사용자를 프로필 설정으로 보내는 데 씁니다.
     *              소셜 가입은 닉네임 없이 시작하므로 그 화면을 거치지 않으면
     *              후기 작성자 표시가 빈 상태로 남습니다.
     */
    public record OAuthLoginResult(TokenProvider.IssuedToken accessToken,
                                   TokenProvider.IssuedToken refreshToken,
                                   boolean isNew) {
    }

    /**
     * 제공자가 되돌려보낸 요청을 받아 로그인을 마칩니다.
     *
     * 계정을 만드는 것과 이벤트를 기록하는 것이 한 트랜잭션 안에 있어야 합니다.
     * 나뉘면 "계정은 생겼는데 프로필이 안 생기는" 상태가 만들어집니다.
     *
     * @param input 제공자 이름·인가 코드·state·쿠키에 남긴 state·접속 정보입니다.
     * @throws CustomException 확인에 실패했거나 탈퇴한 계정인 경우입니다.
     */
    @Transactional
    public OAuthLoginResult callback(OAuthCallbackInput input) {
        String provider = input.provider();
        String state = input.state();
        String cookieState = input.cookieState();

        requireSupported(provider);

        // 이 흐름을 시작한 브라우저가 맞는지 먼저 봄
        //
        // 저장소만 보면 "우리가 발급한 값인가" 까지만 확인됨
        // 공격자가 자기 계정으로 인가를 시작해 얻은 콜백 주소를 남에게 열게 하면
        // 그 사람의 브라우저에 공격자 계정의 쿠키가 심기는데, 저장소에는 그 값이 멀쩡히 있음
        //
        // 저장소를 꺼내기 전에 보는 것이 중요함
        // 순서가 반대면 남이 시작해 둔 흐름을 대신 소진시켜 버림
        if (cookieState == null || !cookieState.equals(state)) {
            log.warn("소셜 로그인 상태 쿠키가 없거나 값이 다릅니다");
            throw new CustomException(AuthErrorCode.INVALID_OAUTH_STATE);
        }

        // state 를 꺼내면서 지움
        //
        // 없다는 것은 이미 쓴 값이거나 만료된 것임
        // 둘을 구분하지 않는 이유는 어느 쪽이든 처음부터 다시 하는 수밖에 없기 때문임
        //
        // 같은 콜백 주소를 두 번 열면 두 번째가 여기서 걸림
        String nonce = oAuthStateStore.consume(state)
                .orElseThrow(() -> {
                    log.warn("소셜 로그인 state 를 확인하지 못했습니다");
                    return new CustomException(AuthErrorCode.INVALID_OAUTH_STATE);
                });

        // 인가 코드를 사용자 신원으로 바꿈
        // 서명, 발급자, 대상, nonce, 이메일 확인 여부를 여기서 전부 봄
        OAuthClient.OAuthUser user = oAuthClient.exchange(input.code(), nonce);

        Account account = accountRepository.findByProviderUserId(user.providerUserId())
                .orElse(null);
        boolean isNew = false;

        if (account == null) {
            account = accountRepository.findByEmail(user.email()).orElse(null);
        }

        if (account == null) {
            account = createAccount(user);
            isNew = true;
        } else {
            // 탈퇴한 계정이면 잇지 않고 여기서 끝냄
            //
            // 순서가 중요함
            // 이으면 탈퇴한 행에 제공자 식별자가 박혀, 그 뒤로는 첫 조회에서
            // 계속 그 행이 잡혀 빠져나갈 방법이 없어짐
            if (!account.canLogin()) {
                throw new CustomException(AuthErrorCode.ACCOUNT_WITHDRAWN);
            }

            // 이미 이어진 계정이면 아무 일도 하지 않음
            // 식별자로 찾아온 경우가 그러함
            account.linkGoogle(user.providerUserId());
        }

        TokenIssueService.IssuedSession session =
                tokenIssueService.issue(account, input.client());

        log.info("소셜 로그인 성공 accountId={}, provider={}, isNew={}",
                account.getId(), provider, isNew);
        return new OAuthLoginResult(session.accessToken(), session.refreshToken(), isNew);
    }

    /**
     * 소셜로 처음 들어온 사람의 계정을 만듭니다.
     *
     * 이메일 인증을 요구하지 않습니다.
     * 그 절차는 "이 주소의 주인이 맞는가" 를 우리가 확인하는 장치인데,
     * 제공자가 이미 확인해 준 이메일만 받아 오므로 같은 것을 두 번 하는 셈입니다.
     * 사용자에게는 코드를 받아 입력하는 단계가 하나 더 생길 뿐입니다.
     *
     * 닉네임을 null 로 넘깁니다.
     * 콜백에는 사용자가 입력한 닉네임이 없고, 임시 이름을 지어 주면
     * 그것을 그대로 쓰는 사람이 생겨 후기 목록에서 서로 구분되지 않습니다.
     * 비어 있다는 것 자체가 "아직 정하지 않았다" 의 표시가 됩니다.
     */
    private Account createAccount(OAuthClient.OAuthUser user) {
        Account account = accountRepository.save(Account.createSocial(
                user.email(), oAuthClient.provider(), user.providerUserId()));

        // 이메일 가입과 같은 이벤트를 냄
        //
        // 빠뜨리면 소셜로 가입한 사람만 프로필이 없는 상태가 되는데,
        // 증상이 "마이페이지가 비어 있다" 라 원인이 이 자리라는 것이 드러나지 않음
        outboxEventRecorder.record(
                new AccountCreatedEvent(account.getId(), account.getEmail(), null));

        return account;
    }

    // 경로에 아무 값이나 들어올 수 있으므로 이 서비스가 판단함
    // 게이트웨이는 /api/v1/auth/oauth/** 를 통째로 열어 두었을 뿐임
    private void requireSupported(String provider) {
        if (!oAuthProperties.supports(provider)) {
            throw new CustomException(AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }
    }

    // 맞히기 어려운 값을 만듦
    private String randomValue() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}

package com.pawtrail.auth.application.service;

import com.pawtrail.auth.domain.model.RefreshTokenLog;
import com.pawtrail.auth.domain.repository.RefreshTokenLogRepository;
import com.pawtrail.auth.domain.repository.RefreshTokenStore;
import com.pawtrail.auth.infrastructure.security.TokenReader;
import com.pawtrail.common.exception.CustomException;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 상태를 끝냅니다.
 *
 * 하는 일은 리프레시 토큰을 못 쓰게 만드는 것뿐입니다.
 * 액세스 토큰은 발급된 뒤에는 만료까지 되돌릴 수 없어 그대로 두며,
 * 그 시간을 짧게(30분) 잡아 둔 것이 유일한 대응입니다.
 * 쿠키를 지우면 브라우저에서는 사라지지만 이미 복사된 값까지 막지는 못합니다.
 *
 * 어떤 경우에도 실패하지 않습니다
 *
 * 쿠키가 없어도, 토큰이 만료됐어도, 읽을 수 없는 값이어도 성공으로 응답합니다.
 * 여기서 401 을 내보내면 클라이언트가 지우지 못하는 쿠키를 들고 갇힙니다.
 * 만료된 토큰으로 로그아웃하려는 것은 지극히 정상적인 상황이고,
 * 로그아웃은 여러 번 불러도 결과가 같아야 하는 종류의 요청입니다.
 *
 * 쿠키를 지우는 일은 컨트롤러가 합니다. 그것이 HTTP 의 사정이기 때문입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenLogRepository refreshTokenLogRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenReader tokenReader;

    /**
     * 리프레시 토큰을 회수합니다.
     *
     * @param refreshTokenValue 쿠키에서 꺼낸 값입니다. 쿠키가 없으면 null 입니다.
     */
    @Transactional
    public void logout(String refreshTokenValue) {

        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            log.debug("토큰 없이 로그아웃 요청이 들어왔습니다. 쿠키만 지웁니다");
            return;
        }

        TokenReader.RefreshTokenInfo info;
        try {
            info = tokenReader.readRefreshToken(refreshTokenValue);
        } catch (CustomException e) {
            // 만료됐거나 읽을 수 없는 토큰임
            // 회수할 대상을 특정할 수 없을 뿐이고 사용자가 하려는 일은 이미 이루어집니다.
            log.debug("읽을 수 없는 토큰으로 로그아웃 요청이 들어왔습니다. 쿠키만 지웁니다");
            return;
        }

        // 저장소에서 지움. 이 시점부터 그 토큰으로는 갱신할 수 없음
        //
        // 갱신과 같은 메서드를 쓰는 이유는 저장소에 시킬 일이 "읽으면서 지운다" 로 같기 때문임
        // 돌려받은 계정 식별자는 여기서 쓰지 않음
        refreshTokenStore.claim(info.tokenId());

        // 이력에 회수 시각을 남깁니다.
        //
        // 행을 찾지 못해도 그냥 둡니다.
        // 이력이 없다는 것은 조사할 기록이 한 줄 비는 것이고,
        // 그 때문에 로그아웃을 실패시키면 사용자는 로그인 상태에 갇힙니다.
        Optional<RefreshTokenLog> found = refreshTokenLogRepository.findByTokenId(info.tokenId());

        if (found.isPresent()) {
            found.get().revoke(LocalDateTime.now());
        } else {
            log.warn("회수 표시를 남길 이력 행을 찾지 못했습니다. accountId={}", info.accountId());
        }

        // 직전에 교체된 토큰의 유예 항목은 지우지 않음
        //
        // 그 항목의 키가 옛 토큰의 jti 인데 지금 들어온 것은 새 토큰이라 알 수 없음
        // 남아 있어도 그 안에 든 것은 방금 지운 토큰이라 받아 가도 갱신에 쓸 수 없고,
        // 수명이 짧아 곧 사라집니다.
        log.info("로그아웃했습니다. accountId={}", info.accountId());
    }
}

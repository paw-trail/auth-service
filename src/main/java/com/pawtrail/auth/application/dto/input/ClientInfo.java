package com.pawtrail.auth.application.dto.input;

/**
 * 요청을 보낸 쪽에 대해 우리가 아는 것입니다.
 *
 * 두 값이 늘 한 짝으로 다닙니다.
 * 로그인·갱신·소셜 콜백이 받아서 토큰 발급으로 넘기고, 발급 이력에 함께 저장됩니다.
 * 그 경로에서 둘을 따로 넘기면 인자 자리가 계속 늘어나는데,
 * 둘 다 문자열이라 순서를 바꿔 넣어도 오류가 나지 않습니다.
 *
 * 이 record 에는 HTTP 가 나오지 않습니다.
 * 값을 요청에서 뽑는 일은 ClientInfoFactory 가 하며 그것은 presentation 에 있습니다.
 * 여기까지 HttpServletRequest 를 들이면 application 이 HTTP 를 알게 됩니다.
 *
 * @param ipAddress 요청한 곳의 주소입니다.
 *                  * 지금은 언제나 null 입니다
 *                    앞에 게이트웨이가 있어 원래 주소가 X-Forwarded-For 로 와야 하는데
 *                    그 헤더가 실제로 도착하지 않는 것을 확인했습니다
 *                    nginx 를 붙이면서 무엇을 넣을지 정하며, 그때 고칠 자리는 팩터리 하나입니다
 * @param userAgent 브라우저가 보낸 값입니다. 게이트웨이가 지우지 않아 그대로 도착합니다.
 */
public record ClientInfo(String ipAddress, String userAgent) {

    /**
     * 값을 모르는 경우입니다.
     *
     * 요청 밖에서 부르는 자리에서 씁니다. 이력에는 두 칸이 비어 남습니다.
     */
    public static ClientInfo unknown() {
        return new ClientInfo(null, null);
    }
}

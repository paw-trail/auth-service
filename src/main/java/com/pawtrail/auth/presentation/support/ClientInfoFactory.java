package com.pawtrail.auth.presentation.support;

import com.pawtrail.auth.application.dto.input.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * 요청에서 접속 정보를 뽑습니다.
 *
 * 이 클래스만 HttpServletRequest 를 압니다.
 * ClientInfo 자체는 application 에 있고 문자열 두 개만 담으므로,
 * 그것을 어디서 어떻게 얻는지는 이 층이 정합니다.
 *
 * 뽑는 자리를 하나로 모으는 이유
 *
 * 지금 ip 는 언제나 null 입니다. 앞에 게이트웨이가 있어 원래 주소가 헤더로 와야 하는데
 * 그 헤더가 도착하지 않는 것을 확인했고, 무엇을 넣을지는 nginx 를 붙이며 정하기로 했습니다.
 * 그때 고칠 곳이 여기 한 군데가 되도록 컨트롤러마다 만들지 않습니다.
 */
public final class ClientInfoFactory {

    private ClientInfoFactory() {
    }

    public static ClientInfo from(HttpServletRequest request) {
        // ip 는 아직 채우지 않음
        //
        // getRemoteAddr 은 게이트웨이 주소를 돌려주므로 담아도 쓸모가 없음
        // X-Forwarded-For 는 nginx 를 붙인 뒤에야 진짜 주소가 실리고,
        // 그때 "몇 번째 값을 믿을지" 를 함께 정해야 함
        return new ClientInfo(null, request.getHeader(HttpHeaders.USER_AGENT));
    }
}

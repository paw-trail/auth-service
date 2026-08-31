package com.pawtrail.auth.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 메일 발송에 쓰는 설정입니다.
 * config 저장소의 auth-service.yml 에서 내려옵니다.
 *
 * 접속 주소와 계정은 스프링이 spring.mail 로 읽어 가므로 여기 없습니다.
 * 여기에는 우리가 직접 쓰는 값만 둡니다.
 *
 * @param from 보내는 사람 주소입니다.
 *             spring.mail.username 과 같은 값이지만 따로 받습니다.
 *             그쪽은 접속에 쓰는 계정이고 이것은 메일에 표시되는 주소라,
 *             나중에 발송 계정과 표시 주소가 달라질 수 있기 때문입니다.
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(String from) {

    public MailProperties {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "app.mail.from 이 비어 있습니다. config 저장소의 auth-service.yml 을 확인하십시오");
        }
    }
}

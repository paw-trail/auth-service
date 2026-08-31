package com.pawtrail.auth.infrastructure.provider;

import com.pawtrail.auth.domain.exception.AuthErrorCode;
import com.pawtrail.auth.domain.provider.MailSender;
import com.pawtrail.auth.infrastructure.config.MailProperties;
import com.pawtrail.common.exception.CustomException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP 로 메일을 보냅니다.
 *
 * 본문을 HTML 로 만들되 템플릿 엔진을 쓰지 않습니다.
 * 넣을 것이 코드 여섯 자리 하나뿐이라 자바 문자열로 충분하고,
 * 엔진을 들이면 프론트를 분리하며 걷어낸 의존성이 다시 들어옵니다.
 *
 * 메일 HTML 은 웹 HTML 과 규칙이 다릅니다.
 *   style 블록을 무시하는 클라이언트가 있어 각 태그에 style 을 직접 씁니다
 *   flex 와 grid 를 못 읽는 클라이언트가 있어 쓰지 않습니다
 *   이미지를 기본으로 막는 클라이언트가 많아 코드를 이미지로 넣지 않습니다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpMailSender implements MailSender {

    // 로고 화면에서 뽑은 색입니다.
    //
    // 설정이 아니라 상수로 두는 것은, 이 값이 환경에 따라 달라지지 않기 때문입니다.
    // 프론트가 정한 값과 어긋나면 여기를 고칩니다.
    private static final String CREAM = "#EDE6DB";
    private static final String GREEN = "#6E9370";
    private static final String GREEN_DARK = "#4F7052";
    private static final String MUTED = "#9c9483";
    private static final String LINE = "#dcd3c4";
    private static final String INK = "#2b2b28";

    private static final String SERVICE_NAME = "함께하개";
    private static final String TAGLINE = "반려동물과 함께하는 장소 탐험";

    private final JavaMailSender javaMailSender;
    private final MailProperties mailProperties;

    @Override
    public void sendVerificationCode(String to, String code, MailPurpose purpose) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();

            // true 는 첨부를 붙일 수 있는 형태로 만든다는 뜻입니다.
            // 지금은 첨부가 없지만 한글이 깨지지 않게 인코딩을 지정하려면 이 생성자를 써야 합니다.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            // 보내는 사람 이름을 함께 지정합니다.
            // 이 값이 받는 쪽 메일함 목록에 그대로 보이므로, 계정 주소만 뜨는 것보다 낫습니다.
            helper.setFrom(mailProperties.from(), SERVICE_NAME);
            helper.setTo(to);
            helper.setSubject("[" + SERVICE_NAME + "] " + purpose.subject());

            // 두 번째 인자가 true 라야 HTML 로 해석됩니다.
            // 빠뜨리면 태그가 그대로 글자로 보입니다.
            helper.setText(buildHtml(code, purpose), true);

            javaMailSender.send(message);
            log.info("인증 메일을 보냈습니다. purpose={}", purpose);

        } catch (Exception e) {
            // 받는 주소는 남기지 않습니다. 로그에 이메일이 쌓이는 것을 피합니다.
            log.error("인증 메일 발송에 실패했습니다. purpose={}", purpose, e);
            throw new CustomException(AuthErrorCode.MAIL_SEND_FAILED, e);
        }
    }

    /**
     * 본문을 만듭니다.
     *
     * 위쪽 크림색 띠가 서비스를 말하고 아래쪽이 코드를 전합니다.
     * 코드 상자를 색으로 채우지 않고 테두리만 두른 것은,
     * 흰 배경과 대비가 서야 숫자가 눈에 들어오기 때문입니다.
     *
     * 색을 넓게 칠할수록 광고 메일로 분류될 확률이 올라가는 것도 이유입니다.
     */
    private String buildHtml(String code, MailPurpose purpose) {
        return """
                <div style="background:#ffffff;border-radius:8px;overflow:hidden;max-width:480px;\
                margin:0 auto;font-family:-apple-system,'Segoe UI',sans-serif">
                  <div style="background:%s;padding:20px 28px;text-align:center">
                    <p style="margin:0 0 2px;font-size:18px;font-weight:500;color:%s">%s</p>
                    <p style="margin:0;font-size:12px;color:%s">%s</p>
                  </div>
                  <div style="padding:28px">
                    <p style="margin:0 0 16px;font-size:15px;color:%s">%s</p>
                    <div style="border:1px solid %s;border-radius:8px;padding:22px;text-align:center;\
                margin:0 0 16px">
                      <p style="margin:0;font-size:32px;font-weight:500;letter-spacing:8px;\
                color:%s">%s</p>
                    </div>
                    <p style="margin:0;font-size:13px;color:%s">10분간 유효합니다. \
                본인이 요청하지 않았다면 무시하셔도 됩니다.</p>
                  </div>
                </div>
                """.formatted(
                CREAM, GREEN, SERVICE_NAME, MUTED, TAGLINE,
                INK, purpose.message(),
                LINE, GREEN_DARK, code,
                MUTED);
    }
}

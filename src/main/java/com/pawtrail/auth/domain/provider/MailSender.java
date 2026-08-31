package com.pawtrail.auth.domain.provider;

/**
 * 메일을 보낸다는 약속입니다.
 *
 * 이 인터페이스에는 SMTP 라는 단어가 나오지 않습니다.
 * 무엇을 보낼 수 있는지만 적고 어떻게 보내는지는 infrastructure 가 정합니다.
 * 나중에 발송 방식이 바뀌어도 서비스는 그대로입니다.
 */
public interface MailSender {

    /**
     * 인증 코드 메일을 보냅니다.
     *
     * 두 기능이 같은 메서드를 씁니다. 제목과 안내 문구만 다릅니다.
     *
     * @param purpose 무엇을 위한 코드인지입니다. 제목과 본문 첫 줄에 쓰입니다.
     * @throws com.pawtrail.common.exception.CustomException 발송에 실패한 경우입니다.
     *         부르는 쪽이 그것을 사용자에게 알릴지 삼킬지 정합니다.
     */
    void sendVerificationCode(String to, String code, MailPurpose purpose);

    /**
     * 코드의 용도입니다.
     *
     * 문자열 대신 열거형으로 두는 것은, 새 용도가 생겼을 때
     * 문구를 어디에 적어야 하는지가 한곳에 모여 있게 하기 위함입니다.
     */
    enum MailPurpose {

        SIGNUP("이메일 인증", "이메일 인증을 완료해 주세요."),
        PASSWORD_RESET("비밀번호 재설정", "비밀번호를 재설정하려면 아래 코드를 입력해 주세요.");

        private final String subject;
        private final String message;

        MailPurpose(String subject, String message) {
            this.subject = subject;
            this.message = message;
        }

        public String subject() {
            return this.subject;
        }

        public String message() {
            return this.message;
        }
    }
}

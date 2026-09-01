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
     * 여러 기능이 같은 메서드를 씁니다. 제목과 안내 문구만 다릅니다.
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
     *
     * 발송 제한도 이 값으로 갈립니다. 용도가 다르면 한도를 따로 세야 하기 때문입니다.
     */
    enum MailPurpose {

        SIGNUP("이메일 인증", "이메일 인증을 완료해 주세요.", "signup"),
        PASSWORD_RESET("비밀번호 재설정", "비밀번호를 재설정하려면 아래 코드를 입력해 주세요.", "pwreset"),
        WITHDRAW("회원 탈퇴", "탈퇴를 진행하려면 아래 코드를 입력해 주세요.", "withdraw");

        private final String subject;
        private final String message;
        private final String key;

        MailPurpose(String subject, String message, String key) {
            this.subject = subject;
            this.message = message;
            this.key = key;
        }

        public String subject() {
            return this.subject;
        }

        public String message() {
            return this.message;
        }

        /**
         * 저장소 키에 섞어 쓰는 값입니다.
         *
         * name 을 그대로 쓰지 않는 것은, 상수 이름을 바꾸는 순간 저장소의 키가 함께 바뀌는데
         * 그 연결이 코드에 드러나지 않기 때문입니다. TokenType 이 값을 따로 둔 것과 같은 이유입니다.
         * 여기서는 키의 수명이 짧아 결과가 가볍지만, 판단을 자리마다 다르게 하지 않습니다.
         */
        public String key() {
            return this.key;
        }
    }
}

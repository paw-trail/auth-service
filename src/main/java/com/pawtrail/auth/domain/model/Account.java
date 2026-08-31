package com.pawtrail.auth.domain.model;

import com.pawtrail.auth.domain.enums.AccountStatus;
import com.pawtrail.auth.domain.enums.AuthProvider;
import com.pawtrail.common.entity.BaseEntity;
import com.pawtrail.common.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 계정입니다.
 *
 * 이 엔티티의 id 가 그대로 X-User-Id 로 흘러 전 서비스가 참조하는 값이 됩니다.
 * 게이트웨이가 토큰에서 꺼내 헤더로 주입하고, 각 도메인 서비스는 그 헤더를 믿습니다.
 *
 * 닉네임은 여기에 없습니다.
 * user_db 의 user_profile 이 소유하며, 이 서비스는 회원가입 때 받아서
 * account.created 이벤트로 넘기기만 합니다.
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseEntity {

    // 애플리케이션이 UUID v7 로 만들어 넣음
    // 상위 48비트가 시각이라 B-tree 뒤쪽에 붙어 페이지 분열이 적음
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // 로그인 아이디이자 계정 복구의 유일한 수단임
    // 소셜을 구글로 정해 이메일이 항상 확보되므로 not null 임
    //
    // 변경 메서드를 두지 않음
    // 이메일 변경은 API 명세에 없고, 인증을 다시 받아야 하는 기능이라
    // 필요해질 때 따로 설계할 자리임
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    // BCrypt 해시는 길이가 60 으로 고정임
    // 소셜 계정은 비밀번호가 없으므로 null 임
    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    // LOCAL 또는 GOOGLE
    // 문자열로 저장함, ORDINAL 로 두면 열거형 순서를 바꿀 때 기존 행의 뜻이 통째로 바뀜
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 12)
    private AuthProvider authProvider;

    // 제공자가 주는 계정 고유 식별자임 (구글은 id_token 의 sub)
    // 로그인할 때마다 같은 값이 오므로 이것으로 계정을 찾음
    // LOCAL 계정은 null 임
    @Column(name = "provider_user_id", length = 255)
    private String providerUserId;

    // 공통 모듈의 Role 을 그대로 씀
    //
    // 자기 열거형을 만들면 게이트웨이가 주입한 헤더로 만들어진
    // CustomUserPrincipal.role() 과 타입이 달라져 비교가 되지 않음
    // 토큰을 발급하는 쪽도 이 서비스이므로 저장하는 값과 검증되는 값이 같은 타입이어야 함
    //
    // 변경 메서드를 두지 않음 - 관리자 지정은 DB 에서 직접 UPDATE 함
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 12)
    private Role role;

    // ACTIVE 또는 WITHDRAWN
    //
    // BaseEntity 의 deletedAt 을 쓰지 않고 이 값 하나로만 탈퇴를 표현함
    // 둘 다 쓰면 조회 조건이 두 갈래로 갈려
    // 한쪽만 고쳤을 때 탈퇴한 계정으로 로그인이 되는 사고가 남
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private AccountStatus status;

    // 마지막 로그인 시각임
    // 처음 만들 때는 null 이고 로그인에 성공할 때마다 갱신됨
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // 생성자를 감추고 정적 팩터리 두 개만 여는 이유
    //
    // 로컬 가입과 소셜 가입은 채우는 필드가 서로 다름
    //   로컬  email + passwordHash,  providerUserId 없음
    //   소셜  email + providerUserId, passwordHash 없음
    //
    // 팩터리로 나누면 "소셜인데 비밀번호가 들어간" 조합을 애초에 만들 수 없고
    // 이름 자체가 어떤 계정인지를 말해 줌
    private Account(String email, String passwordHash,
                    AuthProvider authProvider, String providerUserId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.authProvider = authProvider;
        this.providerUserId = providerUserId;
        this.role = Role.USER;
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * 이메일과 비밀번호로 가입한 계정을 만듭니다.
     *
     * @param passwordHash 이미 BCrypt 로 해싱된 값입니다. 평문을 넘기지 않습니다.
     */
    public static Account createLocal(String email, String passwordHash) {
        return new Account(email, passwordHash, AuthProvider.LOCAL, null);
    }

    /**
     * 소셜 로그인으로 만들어진 계정을 만듭니다.
     *
     * @param providerUserId 제공자가 주는 고유 식별자입니다. 구글은 id_token 의 sub 입니다.
     */
    public static Account createSocial(String email, AuthProvider authProvider,
                                       String providerUserId) {
        return new Account(email, authProvider, providerUserId);
    }

    // 위 팩터리가 쓰는 보조 생성자임
    private Account(String email, AuthProvider authProvider, String providerUserId) {
        this(email, null, authProvider, providerUserId);
    }

    // 탈퇴 처리임
    //
    // 행을 지우지 않고 상태만 바꿈
    // 실제 데이터 삭제는 account.withdrawn 이벤트를 받은 각 서비스가 자기 몫을 함
    // 되돌리는 것이 아니라 마저 지우는 방식이므로 보상 트랜잭션이 아님
    public void withdraw() {
        this.status = AccountStatus.WITHDRAWN;
    }

    // 로그인에 성공했을 때 시각을 남김
    public void updateLastLoginAt(LocalDateTime loginAt) {
        this.lastLoginAt = loginAt;
    }

    /**
     * 비밀번호를 바꿉니다.
     *
     * @param newPasswordHash 이미 BCrypt 로 해싱된 값입니다.
     * @throws IllegalStateException 소셜 계정이면 바꿀 비밀번호가 없습니다.
     */
    public void changePassword(String newPasswordHash) {
        if (!this.authProvider.hasPassword()) {
            throw new IllegalStateException(
                    "소셜 로그인 계정은 비밀번호를 가지지 않습니다. authProvider=" + this.authProvider);
        }
        this.passwordHash = newPasswordHash;
    }

    // 로그인할 수 있는 계정인지 봄
    public boolean canLogin() {
        return this.status.canLogin();
    }
}

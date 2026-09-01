package com.pawtrail.auth.infrastructure.persistence.jpa;

import com.pawtrail.auth.domain.model.Account;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 스프링 데이터가 구현체를 만들어 주는 인터페이스입니다.
 *
 * 이 파일은 도메인이 보지 않습니다.
 *
 * persistence 바로 아래가 아니라 jpa 하위에 두는 것은 층이 다르기 때문입니다.
 * AccountRepositoryImpl 은 도메인이 선언한 약속의 구현이지만,
 * 이 인터페이스는 그 구현이 쓰는 부품입니다.
 * 나란히 두면 둘이 같은 급으로 보입니다.
 * 도메인은 AccountRepository 만 알고, 그것을 AccountRepositoryImpl 이 구현하며
 * 그 안에서 이 인터페이스를 씁니다.
 *
 * 메서드 이름만으로 질의가 만들어지므로 본문이 없습니다.
 */
public interface AccountJpaRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<Account> findByProviderUserId(String providerUserId);
}

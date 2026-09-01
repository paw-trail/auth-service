package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.model.Account;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 스프링 데이터가 구현체를 만들어 주는 인터페이스입니다.
 *
 * 이 파일은 도메인이 보지 않습니다.
 * 도메인은 AccountRepository 만 알고, 그것을 AccountRepositoryImpl 이 구현하며
 * 그 안에서 이 인터페이스를 씁니다.
 *
 * 메서드 이름만으로 질의가 만들어지므로 본문이 없습니다.
 */
public interface JpaAccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<Account> findByProviderUserId(String providerUserId);
}

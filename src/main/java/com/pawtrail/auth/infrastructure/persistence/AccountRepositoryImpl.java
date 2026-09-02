package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.repository.AccountRepository;
import com.pawtrail.auth.infrastructure.persistence.jpa.AccountJpaRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 도메인이 선언한 약속을 스프링 데이터로 구현합니다.
 *
 * 지금은 그대로 넘기기만 하므로 얇아 보이지만 이 자리가 필요한 이유가 있습니다.
 *   도메인이 스프링 데이터를 직접 알지 않게 됨
 *   조회 방식이 바뀌어도 도메인 인터페이스는 그대로임
 *   조회가 복잡해져도 도메인이 보는 것은 이 클래스 하나임
 *
 * 조회 수단을 둘 쓰게 되어도 파일은 이것 하나입니다.
 * AccountJpaRepository 와 JPAQueryFactory 를 함께 주입받아,
 * 단순한 조회는 앞의 것에 위임하고 동적 조건은 뒤의 것으로 짭니다.
 *
 * 이 서비스는 조회가 단순해 아직 QueryDSL 을 쓰지 않습니다.
 */
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {

    private final AccountJpaRepository accountJpaRepository;

    @Override
    public Account save(Account account) {
        return accountJpaRepository.save(account);
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return accountJpaRepository.findById(id);
    }

    @Override
    public Optional<Account> findByEmail(String email) {
        return accountJpaRepository.findByEmail(email);
    }

    @Override
    public boolean existsByEmail(String email) {
        return accountJpaRepository.existsByEmail(email);
    }

    @Override
    public Optional<Account> findByProviderUserId(String providerUserId) {
        return accountJpaRepository.findByProviderUserId(providerUserId);
    }
}

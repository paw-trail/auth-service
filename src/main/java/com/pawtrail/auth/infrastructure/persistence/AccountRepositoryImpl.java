package com.pawtrail.auth.infrastructure.persistence;

import com.pawtrail.auth.domain.enums.AuthProvider;
import com.pawtrail.auth.domain.model.Account;
import com.pawtrail.auth.domain.repository.AccountRepository;
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
 *   QueryDSL 이 필요해지면 여기서 두 구현을 합쳐 내보내면 됨
 *
 * 이 서비스는 조회가 단순해 QueryDSL 을 쓰지 않습니다.
 * 동적 조건이 필요해지는 서비스에서 붙이면 됩니다.
 */
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {

    private final JpaAccountRepository jpaAccountRepository;

    @Override
    public Account save(Account account) {
        return jpaAccountRepository.save(account);
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jpaAccountRepository.findById(id);
    }

    @Override
    public Optional<Account> findByEmail(String email) {
        return jpaAccountRepository.findByEmail(email);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaAccountRepository.existsByEmail(email);
    }

    @Override
    public Optional<Account> findByAuthProviderAndProviderUserId(AuthProvider authProvider,
                                                                  String providerUserId) {
        return jpaAccountRepository.findByAuthProviderAndProviderUserId(authProvider, providerUserId);
    }
}

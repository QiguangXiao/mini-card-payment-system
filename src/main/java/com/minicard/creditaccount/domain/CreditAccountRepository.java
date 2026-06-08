package com.minicard.creditaccount.domain;

import java.util.Optional;
import java.util.UUID;

public interface CreditAccountRepository {

    Optional<CreditAccount> findByIdForUpdate(UUID accountId);

    void update(CreditAccount account);
}

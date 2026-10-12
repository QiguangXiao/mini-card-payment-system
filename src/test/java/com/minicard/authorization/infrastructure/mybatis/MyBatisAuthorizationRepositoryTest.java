package com.minicard.authorization.infrastructure.mybatis;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Currency;

import com.minicard.authorization.domain.Authorization;
import com.minicard.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * claim 异常分类的快速测试，防止把主键或其他约束冲突误判为幂等 loser。
 *
 * <p>关键词：幂等冲突, 异常分类, idempotency claim, duplicate key,
 * 一意制約(いちいせいやく), 例外判定(れいがいはんてい)。</p>
 */
class MyBatisAuthorizationRepositoryTest {

    private final AuthorizationMapper mapper = mock(AuthorizationMapper.class);
    private final MyBatisAuthorizationRepository repository = new MyBatisAuthorizationRepository(mapper);

    @Test
    void successfulInsertClaimsOwnership() {
        when(mapper.insert(any())).thenReturn(1);
        assertThat(repository.claim("key-1", pending())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "uk_authorizations_idempotency_key",
            "authorizations.uk_authorizations_idempotency_key",
            "mini_card.authorizations.uk_authorizations_idempotency_key"
    })
    void recognizesOnlyKnownIdempotencyConstraint(String constraint) {
        when(mapper.insert(any())).thenThrow(duplicate(constraint));
        assertThat(repository.claim("key-1", pending())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIMARY", "authorizations.PRIMARY", "uk_other",
            "uk_authorizations_idempotency_key_suffix"})
    void propagatesOtherConstraintConflicts(String constraint) {
        DuplicateKeyException failure = duplicate(constraint);
        when(mapper.insert(any())).thenThrow(failure);
        assertThatThrownBy(() -> repository.claim("key-1", pending())).isSameAs(failure);
    }

    @Test
    void propagatesUnrecognizedDuplicateWithoutSqlCause() {
        DuplicateKeyException failure = new DuplicateKeyException("unrecognized duplicate");
        when(mapper.insert(any())).thenThrow(failure);
        assertThatThrownBy(() -> repository.claim("key-1", pending())).isSameAs(failure);
    }

    private DuplicateKeyException duplicate(String constraint) {
        return new DuplicateKeyException("insert failed", new SQLException(
                "Duplicate entry 'value' for key '" + constraint + "'", "23000", 1062));
    }

    private Authorization pending() {
        return Authorization.request("fingerprint", "card-123",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                Instant.parse("2026-10-12T00:00:00Z"));
    }
}

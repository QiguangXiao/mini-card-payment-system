package com.minicard.infrastructure.persistence;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 约束分类器单测：错误类型相同不代表都能安全重放，必须匹配具体业务唯一键。
 *
 * <p>关键词：幂等错误分类, duplicate key, constraint identity,
 * 一意制約(いちいせいやく), 例外判定(れいがいはんてい)。</p>
 */
class MySqlDuplicateKeyTest {

    private static final String CONSTRAINT = "uk_repayments_idempotency_key";

    @ParameterizedTest
    @ValueSource(strings = {"uk_repayments_idempotency_key", "repayments.uk_repayments_idempotency_key",
            "mini_card.repayments.uk_repayments_idempotency_key"})
    void recognizesExpectedQualifiedConstraint(String name) {
        assertThat(MySqlDuplicateKey.isConstraintConflict(error(1062, "Duplicate entry 'k' for key '" + name + "'"),
                CONSTRAINT)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIMARY", "repayments.PRIMARY", "uk_repayments_idempotency_key_suffix",
            "uk_authorizations_idempotency_key"})
    void doesNotConfuseOtherConstraints(String name) {
        assertThat(MySqlDuplicateKey.isConstraintConflict(error(1062, "Duplicate entry 'k' for key '" + name + "'"),
                CONSTRAINT)).isFalse();
    }

    @Test
    void constraintNameInsideValueDoesNotOverrideActualPrimaryConflict() {
        assertThat(MySqlDuplicateKey.isConstraintConflict(error(1062,
                "Duplicate entry \"for key '" + CONSTRAINT + "'\" for key 'PRIMARY'"), CONSTRAINT)).isFalse();
    }

    @Test
    void unknownSqlCodeOrMissingCauseIsNotAnIdempotentConflict() {
        assertThat(MySqlDuplicateKey.isConstraintConflict(error(1205, "for key '" + CONSTRAINT + "'"),
                CONSTRAINT)).isFalse();
        assertThat(MySqlDuplicateKey.isConstraintConflict(new DuplicateKeyException("unknown format"),
                CONSTRAINT)).isFalse();
        assertThat(MySqlDuplicateKey.isConstraintConflict(error(1062, null), CONSTRAINT)).isFalse();
    }

    private DuplicateKeyException error(int code, String message) {
        return new DuplicateKeyException("mapper insert failed", new SQLException(message, "23000", code));
    }
}

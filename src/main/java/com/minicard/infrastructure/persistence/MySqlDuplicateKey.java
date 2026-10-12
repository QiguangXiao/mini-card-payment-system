package com.minicard.infrastructure.persistence;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;

/**
 * 精确识别 MySQL 幂等唯一约束，避免把 PRIMARY 或其他索引冲突伪装成正常重放。
 *
 * <p>关键词：约束分类, 幂等冲突, duplicate key, idempotency constraint,
 * 一意制約(いちいせいやく), 例外判定(れいがいはんてい)。</p>
 *
 * <p>MySQL 1062 本身不区分具体约束；Connector/J 在错误末尾给出约束名。
 * 只接受已知名称，未知驱动格式保守失败。该工具仅解释数据库异常，不承担业务重放规则。</p>
 */
public final class MySqlDuplicateKey {

    private static final Pattern CONSTRAINT_NAME = Pattern.compile(
            "for key ['`]([^'`]+)['`]\\s*$", Pattern.CASE_INSENSITIVE);

    private MySqlDuplicateKey() {
    }

    public static boolean isConstraintConflict(DuplicateKeyException exception, String expectedConstraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1062
                    && sqlException.getMessage() != null) {
                Matcher matcher = CONSTRAINT_NAME.matcher(sqlException.getMessage());
                if (matcher.find()) {
                    String qualifiedName = matcher.group(1);
                    String name = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
                    return expectedConstraint.equals(name);
                }
            }
        }
        return false;
    }
}

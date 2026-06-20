package com.minicard.statement.application;

/**
 * Statement 生成被业务规则拒绝的异常。
 *
 * <p>关键词：出账拒绝, 业务异常, 可预期跳过, statement rejection,
 * business exception, skip, 請求明細作成エラー(せいきゅうめいさいさくせいエラー),
 * 業務例外(ぎょうむれいがい)。</p>
 *
 * <p>Batch 遇到这个异常会记为 skipped，而不是 failed；它表示业务条件不满足，
 * 例如无可出账交易或重复账期。</p>
 */
public class StatementGenerationRejectedException extends RuntimeException {

    /**
     * 保存拒绝原因，供 API/batch log 返回给调用方或排查人员。
     */
    public StatementGenerationRejectedException(String message) {
        super(message);
    }
}

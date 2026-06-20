package com.minicard.repayment.application;

/**
 * 自动扣款失败异常。
 *
 * <p>关键词：扣款失败, 任务重试, 异常传播, debit failure,
 * retry, exception propagation, 振替失敗(ふりかえしっぱい),
 * リトライ, 例外伝播(れいがいでんぱ)。</p>
 *
 * <p>抛出该异常会让 DelayJobWorker 将 AUTO_REPAYMENT 标记为 retry 或 DEAD；
 * 不能吞掉失败，否则系统会误以为扣款已经完成。</p>
 */
public class AutoRepaymentFailedException extends RuntimeException {

    /**
     * 保存银行失败原因，便于 structured log 和后续失败通知。
     */
    public AutoRepaymentFailedException(String message) {
        super(message);
    }
}

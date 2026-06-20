package com.minicard.repayment.application;

/**
 * 银行扣款结果（bank debit result / 口座振替結果）。
 *
 * <p>关键词：扣款结果, 成功, 失败原因, debit result,
 * success, failure reason, 振替結果(ふりかえけっか),
 * 成功(せいこう), 失敗理由(しっぱいりゆう)。</p>
 *
 * <p>当前只有 SUCCESS/FAILED 两态；后续如果引入异步银行回调，可以扩展成 RECEIVED/PENDING/CONFIRMED。</p>
 */
public record BankDebitResult(
        /** 银行扣款是否成功。 */
        BankDebitStatus status,
        /** 失败原因；SUCCESS 时为空。 */
        String failureReason
) {

    /**
     * 构造成功结果，表示可以继续做 repayment posting。
     */
    public static BankDebitResult success() {
        return new BankDebitResult(BankDebitStatus.SUCCESS, null);
    }

    /**
     * 构造失败结果；调用方不能入账，只能走 retry/failed flow。
     */
    public static BankDebitResult failed(String failureReason) {
        return new BankDebitResult(BankDebitStatus.FAILED, failureReason);
    }
}

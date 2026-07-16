package com.minicard.repayment.application.autorepayment;

/**
 * 银行扣款结果（bank debit result / 口座振替結果）。
 *
 * <p>关键词：扣款结果, 成功, 失败原因, debit result,
 * success, failure reason, 振替結果(ふりかえけっか),
 * 成功(せいこう), 失敗理由(しっぱいりゆう)。</p>
 *
 * <p>口座振替（自动扣款）只有“成功 / 失败”两种业务结果，所以这里直接用一个 boolean 表达，
 * 不再为两态单独建 enum——枚举在这里只是包装，没有增加业务含义。
 * 如果未来引入异步银行回调（PENDING/RECEIVED/CONFIRMED），再升级成显式状态枚举也不迟。</p>
 */
public record BankDebitResult(
        /** 银行扣款是否成功；false 时调用方不能入账，只能走 retry/failed flow。 */
        boolean successful,
        /** 失败原因；successful=true 时为 null。 */
        String failureReason
) {

    /**
     * 构造成功结果，表示可以继续做 repayment posting。
     */
    public static BankDebitResult success() {
        return new BankDebitResult(true, null);
    }

    /**
     * 构造失败结果；调用方不能入账，只能走 retry/failed flow。
     */
    public static BankDebitResult failed(String failureReason) {
        return new BankDebitResult(false, failureReason);
    }
}

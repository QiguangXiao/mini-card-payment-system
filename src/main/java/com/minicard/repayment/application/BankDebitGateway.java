package com.minicard.repayment.application;

/**
 * 银行扣款端口（bank debit port / 口座振替）。
 *
 * <p>关键词：银行扣款, 外部端口, 扣款结果, bank debit,
 * port, gateway, debit result, 口座振替(こうざふりかえ),
 * 外部連携(がいぶれんけい), 振替結果(ふりかえけっか)。</p>
 *
 * <p>Application layer 只依赖这个接口；当前实现是模拟结果，未来可以换成银行 API、
 * 固定长文件、或者异步扣款结果回调。</p>
 */
public interface BankDebitGateway {

    /**
     * 请求银行从客户绑定账户扣款。
     *
     * <p>返回 SUCCESS 才允许 repayment posting；FAILED 必须走失败恢复/通知路径。</p>
     */
    BankDebitResult debit(BankDebitRequest request);
}

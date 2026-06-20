package com.minicard.repayment.infrastructure.bank;

import com.minicard.repayment.application.AutoDebitProperties;
import com.minicard.repayment.application.BankDebitGateway;
import com.minicard.repayment.application.BankDebitRequest;
import com.minicard.repayment.application.BankDebitResult;
import com.minicard.repayment.application.BankDebitStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 本地模拟银行扣款 gateway。
 *
 * <p>关键词：模拟银行, 自动扣款, 失败路径, simulated bank gateway,
 * auto debit, failure path, 銀行シミュレーション(ぎんこうシミュレーション),
 * 口座振替(こうざふりかえ), 失敗経路(しっぱいけいろ)。</p>
 *
 * <p>真实系统会提交银行扣款文件或调用银行 API，再异步接收 success/failure result。
 * 当前不建 bank account 表，先假设客户已有默认扣款授权；默认 SUCCESS，
 * 保留 FAILED 分支用于后续演示扣款失败、通知和逾期处理。</p>
 */
@Component
@RequiredArgsConstructor
public class SimulatedBankDebitGateway implements BankDebitGateway {

    /** 控制本地模拟返回 SUCCESS 或 FAILED，便于演示 failure path。 */
    private final AutoDebitProperties properties;

    /**
     * 返回预设银行扣款结果。
     *
     * <p>这里不访问外部网络，避免把学习项目变成真实 integration；重点是保留 bank debit result
     * 这条业务分支。</p>
     */
    @Override
    public BankDebitResult debit(BankDebitRequest request) {
        if (properties.simulatedResult() == BankDebitStatus.FAILED) {
            // FAILED 必须显式返回原因，让 DelayJob 和日志有排查线索。
            return BankDebitResult.failed(properties.failureReason());
        }
        return BankDebitResult.success();
    }
}

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
 * <p>真实系统会提交银行扣款文件或调用银行 API，再异步接收 success/failure result。
 * 当前不建 bank account 表，先假设客户已有默认扣款授权；默认 SUCCESS，
 * 保留 FAILED 分支用于后续演示扣款失败、通知和逾期处理。</p>
 */
@Component
@RequiredArgsConstructor
public class SimulatedBankDebitGateway implements BankDebitGateway {

    private final AutoDebitProperties properties;

    @Override
    public BankDebitResult debit(BankDebitRequest request) {
        if (properties.simulatedResult() == BankDebitStatus.FAILED) {
            return BankDebitResult.failed(properties.failureReason());
        }
        return BankDebitResult.success();
    }
}

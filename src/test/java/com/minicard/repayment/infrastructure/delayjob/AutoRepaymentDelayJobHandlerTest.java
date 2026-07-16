package com.minicard.repayment.infrastructure.delayjob;

import java.time.Instant;
import java.util.UUID;

import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobPermanentException;
import com.minicard.delayjob.DelayJobType;
import com.minicard.repayment.application.autorepayment.AutoRepaymentService;
import com.minicard.repayment.application.autorepayment.BankDebitPermanentException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AUTO_REPAYMENT handler 的 contract 校验与"业务 → 框架"异常翻译测试。
 */
class AutoRepaymentDelayJobHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    @Test
    void delegatesStatementIdToAutoRepaymentService() {
        AutoRepaymentService service = mock(AutoRepaymentService.class);
        AutoRepaymentDelayJobHandler handler = new AutoRepaymentDelayJobHandler(service);
        UUID statementId = UUID.randomUUID();

        assertThatCode(() -> handler.handle(job("Statement", statementId.toString())))
                .doesNotThrowAnyException();

        verify(service).debitStatement(statementId);
    }

    @Test
    void rejectsJobTargetingWrongAggregateType() {
        AutoRepaymentDelayJobHandler handler =
                new AutoRepaymentDelayJobHandler(mock(AutoRepaymentService.class));

        // 错误 contract 必须进失败路径，不能把任意 aggregateId 当 statementId 扣款。
        assertThatThrownBy(() -> handler.handle(job("Authorization", UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void translatesBankPermanentFailureIntoDelayJobPermanentException() {
        AutoRepaymentService service = mock(AutoRepaymentService.class);
        AutoRepaymentDelayJobHandler handler = new AutoRepaymentDelayJobHandler(service);
        UUID statementId = UUID.randomUUID();
        doThrow(new BankDebitPermanentException("bank rejected debit request status=400"))
                .when(service).debitStatement(statementId);

        // 银行 4xx 是确定性失败：翻译成框架级 permanent 异常，DelayJobWorker 一次就 DEAD。
        assertThatThrownBy(() -> handler.handle(job("Statement", statementId.toString())))
                .isInstanceOf(DelayJobPermanentException.class)
                .hasMessageContaining(statementId.toString())
                .hasCauseInstanceOf(BankDebitPermanentException.class);
    }

    private DelayJob job(String aggregateType, String aggregateId) {
        return DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTO_REPAYMENT,
                aggregateType,
                aggregateId,
                NOW,
                NOW
        );
    }
}

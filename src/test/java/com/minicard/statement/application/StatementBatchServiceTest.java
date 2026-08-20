package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 月次出账批处理测试。
 *
 * <p>关键词：账期推导, 逐账户事务, 故障隔离, billing cycle, per-account transaction,
 * fault isolation, 締め処理(しめしょり)。</p>
 *
 * <p>这三个用例钉住批处理仅有的三个契约：账期和扣款日怎么推导、单账户失败不连坐、
 * 以及「当月任何一天跑都处理同一个已结束账期」这个 level-triggered 语义。</p>
 */
class StatementBatchServiceTest {

    private StatementBillingRepository billingRepository;
    private StatementGenerationService generationService;
    private BusinessDayCalendar businessDayCalendar;

    @BeforeEach
    void setUp() {
        billingRepository = mock(StatementBillingRepository.class);
        generationService = mock(StatementGenerationService.class);
        businessDayCalendar = mock(BusinessDayCalendar.class);
        // 默认扣款基准日就是营业日；顺延行为由 ConfiguredBusinessDayCalendarTest 覆盖。
        when(businessDayCalendar.nextBusinessDayOnOrAfter(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    // 测试目的：验证账期 = 上一个自然月，扣款日 = 次月基准日，并逐账户调用生成。
    void generatesStatementsForTheClosedCalendarMonth() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        when(billingRepository.findBillableAccountIds(any(), any())).thenReturn(List.of(accountA, accountB));

        int generated = service("2026-07-01T00:00:00Z").run();

        assertThat(generated).isEqualTo(2);
        ArgumentCaptor<GenerateStatementCommand> captor =
                ArgumentCaptor.forClass(GenerateStatementCommand.class);
        verify(generationService, times(2)).generate(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(command -> {
            assertThat(command.periodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
            assertThat(command.periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
            // 扣款日必须严格晚于账期结束日，否则账单刚生成就已到期。
            assertThat(command.dueDate()).isEqualTo(LocalDate.parse("2026-07-27"));
        });
        assertThat(captor.getAllValues().stream().map(GenerateStatementCommand::creditAccountId))
                .containsExactly(accountA, accountB);
    }

    @Test
    // 测试目的：验证单账户失败不中断整批（故障隔离）。
    // variant：第一个账户抛 RuntimeException，第二个账户业务跳过，第三个仍应正常出账。
    void keepsGeneratingAfterOneAccountFails() {
        UUID failing = UUID.randomUUID();
        UUID skipped = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(billingRepository.findBillableAccountIds(any(), any()))
                .thenReturn(List.of(failing, skipped, healthy));
        when(generationService.generate(any())).thenAnswer(invocation -> {
            UUID accountId = invocation.getArgument(0, GenerateStatementCommand.class).creditAccountId();
            if (accountId.equals(failing)) {
                throw new IllegalStateException("deadlock");
            }
            if (accountId.equals(skipped)) {
                throw StatementGenerationException.rejected("no unbilled posted transactions");
            }
            return null;
        });

        int generated = service("2026-07-01T00:00:00Z").run();

        // 只有第三个账户真正出账；前两个不影响它。
        assertThat(generated).isEqualTo(1);
        verify(generationService, times(3)).generate(any());
    }

    @Test
    // 测试目的：验证 level-triggered 语义——当月任何一天跑都处理同一个已结束账期。
    // 反向事实：如果按"昨天是不是关账日"判断，7/1 宕机错过 cron 就再也不会出 6 月账单。
    void processesTheSameClosedCycleOnAnyDayOfTheMonth() {
        when(billingRepository.findBillableAccountIds(any(), any())).thenReturn(List.of(UUID.randomUUID()));

        service("2026-07-15T00:00:00Z").run();

        ArgumentCaptor<GenerateStatementCommand> captor =
                ArgumentCaptor.forClass(GenerateStatementCommand.class);
        verify(generationService).generate(captor.capture());
        assertThat(captor.getValue().periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
    }

    private StatementBatchService service(String nowUtc) {
        return new StatementBatchService(
                billingRepository,
                generationService,
                businessDayCalendar,
                properties(),
                Clock.fixed(Instant.parse(nowUtc), ZoneOffset.UTC)
        );
    }

    private StatementProperties properties() {
        return new StatementProperties(
                new StatementProperties.Batch(true, "0 0 1 * * *", "Asia/Tokyo", 27, Set.of()),
                new StatementProperties.Policy(
                        new BigDecimal("0.10"),
                        Map.of("JPY", new BigDecimal("1000.00"))
                )
        );
    }
}

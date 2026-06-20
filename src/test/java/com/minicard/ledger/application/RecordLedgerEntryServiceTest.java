package com.minicard.ledger.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

import com.minicard.ledger.domain.LedgerDirection;
import com.minicard.ledger.domain.LedgerEntry;
import com.minicard.ledger.domain.LedgerEntryRepository;
import com.minicard.ledger.domain.LedgerEntryType;
import com.minicard.messaging.inbox.ConsumerInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordLedgerEntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void cardTransactionPostedRecordsDebitEntry() {
        ConsumerInboxRepository inboxRepository = mock(ConsumerInboxRepository.class);
        LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
        when(inboxRepository.claim(any(), any(), any())).thenReturn(true);
        when(repository.appendIfAbsent(any(LedgerEntry.class))).thenReturn(true);
        RecordLedgerEntryService service = service(inboxRepository, repository);

        service.record(RecordLedgerEntryCommand.cardTransactionPosted(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("1000.00"),
                JPY,
                NOW
        ));

        ArgumentCaptor<LedgerEntry> entry = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(repository).appendIfAbsent(entry.capture());
        assertThat(entry.getValue().entryType()).isEqualTo(LedgerEntryType.CARD_TRANSACTION_POSTED);
        assertThat(entry.getValue().direction()).isEqualTo(LedgerDirection.DEBIT);
    }

    @Test
    void repaymentReceivedRecordsCreditEntry() {
        ConsumerInboxRepository inboxRepository = mock(ConsumerInboxRepository.class);
        LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
        when(inboxRepository.claim(any(), any(), any())).thenReturn(true);
        when(repository.appendIfAbsent(any(LedgerEntry.class))).thenReturn(true);
        RecordLedgerEntryService service = service(inboxRepository, repository);

        service.record(RecordLedgerEntryCommand.repaymentReceived(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("500.00"),
                JPY,
                NOW
        ));

        ArgumentCaptor<LedgerEntry> entry = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(repository).appendIfAbsent(entry.capture());
        assertThat(entry.getValue().entryType()).isEqualTo(LedgerEntryType.REPAYMENT_RECEIVED);
        assertThat(entry.getValue().direction()).isEqualTo(LedgerDirection.CREDIT);
    }

    @Test
    void duplicateDeliveryDoesNotAppendAnotherLedgerEntry() {
        ConsumerInboxRepository inboxRepository = mock(ConsumerInboxRepository.class);
        LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
        when(inboxRepository.claim(any(), any(), any()))
                .thenReturn(true)
                .thenReturn(false);
        when(repository.appendIfAbsent(any(LedgerEntry.class))).thenReturn(true);
        RecordLedgerEntryService service = service(inboxRepository, repository);
        RecordLedgerEntryCommand command = RecordLedgerEntryCommand.cardTransactionPosted(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("1000.00"),
                JPY,
                NOW
        );

        service.record(command);
        service.record(command);

        // Inbox claim 是第一道 consumer-side idempotency 边界；
        // duplicate delivery 不会继续 append 第二条 ledger entry。
        verify(inboxRepository, times(2)).claim(any(), any(), any());
        verify(repository, times(1)).appendIfAbsent(any(LedgerEntry.class));
    }

    private RecordLedgerEntryService service(
            ConsumerInboxRepository inboxRepository,
            LedgerEntryRepository repository
    ) {
        return new RecordLedgerEntryService(
                inboxRepository,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}

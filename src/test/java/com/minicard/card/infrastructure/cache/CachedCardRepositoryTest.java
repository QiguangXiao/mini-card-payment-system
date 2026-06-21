package com.minicard.card.infrastructure.cache;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.minicard.card.application.CardSnapshot;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardStatus;
import com.minicard.card.infrastructure.mybatis.MyBatisCardRepository;
import com.minicard.infrastructure.cache.SnapshotCache;
import com.minicard.infrastructure.cache.TransactionAwareSnapshotCacheEvictor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachedCardRepositoryTest {

    private static final String CARD_ID = "card-123";
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MyBatisCardRepository delegate;
    private FakeSnapshotCache cache;
    private CachedCardRepository repository;

    @BeforeEach
    void setUp() {
        delegate = mock(MyBatisCardRepository.class);
        cache = new FakeSnapshotCache();
        repository = new CachedCardRepository(
                delegate,
                cache,
                new TransactionAwareSnapshotCacheEvictor()
        );
    }

    @Test
    void cachesCardSnapshotAndRebuildsDomainObject() {
        when(delegate.findById(CARD_ID))
                .thenReturn(Optional.of(new Card(CARD_ID, ACCOUNT_ID, CardStatus.ACTIVE)));

        Optional<Card> first = repository.findById(CARD_ID);
        Optional<Card> second = repository.findById(CARD_ID);

        assertThat(first).contains(new Card(CARD_ID, ACCOUNT_ID, CardStatus.ACTIVE));
        assertThat(second).contains(new Card(CARD_ID, ACCOUNT_ID, CardStatus.ACTIVE));
        verify(delegate, times(1)).findById(CARD_ID);
        assertThat(cache.snapshot).isEqualTo(new CardSnapshot(CARD_ID, ACCOUNT_ID, CardStatus.ACTIVE));
    }

    @Test
    void doesNotNegativeCacheMissingCard() {
        when(delegate.findById(CARD_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Card(CARD_ID, ACCOUNT_ID, CardStatus.ACTIVE)));

        Optional<Card> first = repository.findById(CARD_ID);
        Optional<Card> second = repository.findById(CARD_ID);

        assertThat(first).isEmpty();
        assertThat(second).contains(new Card(CARD_ID, ACCOUNT_ID, CardStatus.ACTIVE));
        verify(delegate, times(2)).findById(CARD_ID);
    }

    @Test
    void evictsCardSnapshotAfterCommit() {
        when(delegate.findById(CARD_ID))
                .thenReturn(Optional.of(new Card(CARD_ID, ACCOUNT_ID, CardStatus.ACTIVE)));
        repository.findById(CARD_ID);

        repository.evictAfterCommit(CARD_ID);
        repository.findById(CARD_ID);

        verify(delegate, times(2)).findById(CARD_ID);
    }

    private static final class FakeSnapshotCache implements SnapshotCache<String, CardSnapshot> {

        private CardSnapshot snapshot;

        @Override
        public CardSnapshot get(String key, Supplier<CardSnapshot> loader) {
            if (snapshot != null) {
                return snapshot;
            }
            snapshot = loader.get();
            return snapshot;
        }

        @Override
        public void evict(String key) {
            snapshot = null;
        }
    }
}

package ti4.spring.api.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OfferedActionStoreTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void issuedOfferCanOnlyBeClaimedByItsGameAndUserAtTheObservedRevision() {
        OfferedActionStore store = new OfferedActionStore(clock, Duration.ofMinutes(5));
        var offer = store.issue("game1", "user1", "channel1", "message1", "FFCC_sol_scPick_1", "revision1");

        assertThrows(
                OfferedActionStore.OfferRejected.class,
                () -> store.claim(offer.token(), "game1", "otherUser", "revision1", "request1"));
        assertThrows(
                OfferedActionStore.OfferRejected.class,
                () -> store.claim(offer.token(), "otherGame", "user1", "revision1", "request1"));
        assertThrows(
                OfferedActionStore.OfferRejected.class,
                () -> store.claim(offer.token(), "game1", "user1", "revision2", "request1"));

        assertEquals(offer, store.claim(offer.token(), "game1", "user1", "revision1", "request1"));
        assertThrows(
                OfferedActionStore.OfferRejected.class,
                () -> store.claim(offer.token(), "game1", "user1", "revision1", "request1"));
        assertThrows(
                OfferedActionStore.OfferRejected.class,
                () -> store.claim(offer.token(), "game1", "user1", "revision1", "request2"));
    }

    @Test
    void theSameRequestCannotExecuteTwoDifferentOffers() {
        OfferedActionStore store = new OfferedActionStore(clock, Duration.ofMinutes(5));
        var first = store.issue("game1", "user1", "channel1", "message1", "pick1", "revision1");
        var second = store.issue("game1", "user1", "channel1", "message2", "pick2", "revision1");

        store.claim(first.token(), "game1", "user1", "revision1", "request1");

        assertThrows(
                OfferedActionStore.OfferRejected.class,
                () -> store.claim(second.token(), "game1", "user1", "revision1", "request1"));
        assertEquals(second, store.claim(second.token(), "game1", "user1", "revision1", "request2"));
    }

    @Test
    void expiredOffersAreNotExecutable() {
        OfferedActionStore store = new OfferedActionStore(clock, Duration.ofSeconds(-1));
        var offer = store.issue("game1", "user1", "channel1", "message1", "pick1", "revision1");

        assertThrows(
                OfferedActionStore.OfferRejected.class,
                () -> store.claim(offer.token(), "game1", "user1", "revision1", "request1"));
    }
}

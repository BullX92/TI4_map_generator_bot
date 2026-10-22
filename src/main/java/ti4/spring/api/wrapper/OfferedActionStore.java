package ti4.spring.api.wrapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OfferedActionStore {

    public record Offer(
            String token,
            String gameName,
            String userId,
            String channelId,
            String messageId,
            String componentId,
            String revision,
            Instant expiresAt) {}

    public static class OfferRejected extends ResponseStatusException {
        public OfferRejected() {
            super(HttpStatus.CONFLICT, "This offer is no longer available. Refresh the offered actions.");
        }
    }

    private record RequestKey(String gameName, String userId, String requestId) {}

    private static final int MAX_ENTRIES = 10_000;
    private final Clock clock;
    private final Duration lifetime;
    private final Map<String, Offer> offers = new LinkedHashMap<>();
    private final Map<RequestKey, Instant> requests = new LinkedHashMap<>();

    public OfferedActionStore() {
        this(Clock.systemUTC(), Duration.ofMinutes(5));
    }

    OfferedActionStore(Clock clock, Duration lifetime) {
        this.clock = clock;
        this.lifetime = lifetime;
    }

    public synchronized Offer issue(
            String gameName, String userId, String channelId, String messageId, String componentId, String revision) {
        prune();
        if (offers.size() >= MAX_ENTRIES)
            offers.remove(offers.keySet().iterator().next());
        Offer offer = new Offer(
                UUID.randomUUID().toString(),
                gameName,
                userId,
                channelId,
                messageId,
                componentId,
                revision,
                clock.instant().plus(lifetime));
        offers.put(offer.token(), offer);
        return offer;
    }

    public synchronized Offer lookup(String token, String gameName, String userId) {
        Offer offer = offers.get(token);
        if (offer == null
                || !offer.gameName().equals(gameName)
                || !offer.userId().equals(userId)
                || !offer.expiresAt().isAfter(clock.instant())) throw new OfferRejected();
        return offer;
    }

    public synchronized Offer claim(String token, String gameName, String userId, String revision, String requestId) {
        prune();
        Offer offer = lookup(token, gameName, userId);
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A request ID is required.");
        }
        RequestKey key = new RequestKey(gameName, userId, requestId);
        if (!offer.revision().equals(revision) || requests.containsKey(key)) throw new OfferRejected();
        if (requests.size() >= MAX_ENTRIES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many recent action requests.");
        }
        offers.remove(token);
        requests.put(key, clock.instant().plus(lifetime));
        return offer;
    }

    private void prune() {
        Instant now = clock.instant();
        offers.values().removeIf(offer -> !offer.expiresAt().isAfter(now));
        requests.values().removeIf(expiry -> !expiry.isAfter(now));
    }
}

package ti4.service.testbed;

import java.util.List;
import java.util.function.Supplier;
import net.dv8tion.jda.api.entities.Member;
import ti4.game.Game;
import ti4.game.Player;

public final class ManagedSeats {
    private static final String OWNER = "wrapperAgentOwner";
    private static final String SEAT = "wrapperAgentSeat_";
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private record Context(Game game, String actor, Player seat) {}

    private ManagedSeats() {}

    public static boolean isManaged(Game game) {
        return game != null && !game.getStoredValue(OWNER).isEmpty();
    }

    public static String owner(Game game) {
        return game.getStoredValue(OWNER);
    }

    public static boolean isAgent(Game game, Player player) {
        return isManaged(game) && player != null && "true".equals(game.getStoredValue(SEAT + player.getUserID()));
    }

    public static List<Player> agents(Game game) {
        return game.getPlayers().values().stream().filter(p -> isAgent(game, p)).toList();
    }

    public static void register(Game game, String owner, List<Player> agents) {
        if (isManaged(game)
                || game.getPlayer(owner) == null
                || agents.size() != 5
                || agents.stream().map(Player::getUserID).distinct().count() != 5
                || agents.stream().anyMatch(p -> owner.equals(p.getUserID()))
                || agents.stream()
                        .anyMatch(p -> !TestBedService.isVirtualSeat(p) || game.getPlayer(p.getUserID()) != p)) {
            throw new IllegalArgumentException("Invalid managed seats");
        }
        TestBedService.markAsTestBed(game, true);
        TestBedService.allowRealPlayers(game);
        game.setStoredValue(OWNER, owner);
        agents.forEach(p -> game.setStoredValue(SEAT + p.getUserID(), "true"));
        TestBedService.clearAllActingAs(game);
        game.removeStoredValue(TestBedPress.FORCED_DICE_KEY);
    }

    public static boolean canUseHumanBridge(Game game) {
        return isManaged(game)
                && TestBedService.isTestBed(game)
                && !game.isFowMode()
                && game.getStoredValue(TestBedPress.FORCED_DICE_KEY).isEmpty();
    }

    public static boolean isActing(Game game, String actor, Player seat) {
        Context context = CURRENT.get();
        return context != null && context.game() == game && context.actor().equals(actor) && context.seat() == seat;
    }

    public static Player resolve(Game game, String actor, Player ownSeat) {
        Context context = CURRENT.get();
        return context != null && context.game() == game && context.actor().equals(actor) ? context.seat() : ownSeat;
    }

    public static Game gameForInteraction(String gameName, String actor) {
        Context context = CURRENT.get();
        return context != null
                        && context.game().getName().equals(gameName)
                        && context.actor().equals(actor)
                ? context.game()
                : null;
    }

    public static <T> T asAgent(Game game, Member actor, Player seat, Supplier<T> action) {
        if (!canUseHumanBridge(game)
                || actor == null
                || !owner(game).equals(actor.getId())
                || !TestBedService.isDeveloper(actor)
                || !isAgent(game, seat)
                || CURRENT.get() != null) {
            throw new IllegalArgumentException("Registered agent and authorized developer required");
        }
        CURRENT.set(new Context(game, actor.getId(), seat));
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }
}

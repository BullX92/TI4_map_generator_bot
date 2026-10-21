package ti4.service.testbed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.Test;
import ti4.discord.JdaService;
import ti4.game.Game;
import ti4.game.Player;
import ti4.settings.GlobalSettings;

class ManagedSeatsTest {
    @Test
    void registeredScopeIsTemporaryAndHumanDoesNotFollowStoredImpersonation() {
        try (var settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS)) {
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(true);
            Game game = registered();
            Player human = game.getPlayer("123");
            Player agent = ManagedSeats.agents(game).getFirst();
            Member actor = mock(Member.class);
            Role role = mock(Role.class);
            List<Role> previous = new ArrayList<>(JdaService.developerRoles);
            JdaService.developerRoles.add(role);
            when(actor.getId()).thenReturn("123");
            when(actor.getRoles()).thenReturn(List.of(role));
            try {
                game.setStoredValue("testBedActingAs_123", agent.getFaction());
                assertSame(human, ManagedSeats.resolve(game, "123", human));
                assertThrows(
                        IllegalStateException.class,
                        () -> ManagedSeats.asAgent(game, actor, agent, () -> {
                            assertSame(agent, ManagedSeats.resolve(game, "123", human));
                            assertSame(human, ManagedSeats.resolve(game, "999", human));
                            throw new IllegalStateException("handler failed");
                        }));
                assertSame(human, ManagedSeats.resolve(game, "123", human));
                assertFalse(ManagedSeats.isActing(game, "123", agent));
                WebPlayerButtonPress.validateActor(game, actor, human, Button.primary("notepadPost", "Notes"));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> WebPlayerButtonPress.validateActor(
                                game, actor, agent, Button.primary("notepadPost", "Notes")));
                assertThrows(IllegalArgumentException.class, () -> TestBedService.setActingAs(game, "123", agent));
                assertThrows(IllegalArgumentException.class, () -> TestBedService.markAsTestBed(game, false));
                assertNotNull(TestBedService.destructiveCommandRefusal(
                        null, game, game.getPlayers().values()));
            } finally {
                JdaService.developerRoles.clear();
                JdaService.developerRoles.addAll(previous);
            }
        }
    }

    @Test
    void disabledTransportAndForcedDiceFailClosed() {
        try (var settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS)) {
            Game game = registered();
            assertFalse(ManagedSeats.canUseHumanBridge(game));
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(true);
            assertTrue(ManagedSeats.canUseHumanBridge(game));
            game.setStoredValue(TestBedPress.FORCED_DICE_KEY, "10");
            assertFalse(ManagedSeats.canUseHumanBridge(game));
        }
    }

    private Game registered() {
        Game game = new Game();
        game.setName("managedproof");
        game.addPlayer("123", "Human").setFaction("hacan");
        List<Player> agents = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            Player player = game.addPlayer(TestBedService.VIRTUAL_SEAT_ID_PREFIX + "0" + index, "Agent " + index);
            player.setFaction("faction" + index);
            agents.add(player);
        }
        ManagedSeats.register(game, "123", agents);
        return game;
    }
}

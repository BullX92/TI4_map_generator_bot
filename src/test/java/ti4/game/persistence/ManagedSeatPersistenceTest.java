package ti4.game.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Player;
import ti4.service.testbed.ManagedSeats;
import ti4.service.testbed.TestBedService;
import ti4.settings.GlobalSettings;
import ti4.testUtils.BaseTi4Test;

class ManagedSeatPersistenceTest extends BaseTi4Test {
    @Test
    void originalFilePreservesRegistrationAndIncompleteStepAcrossReload() {
        try (var settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS);
                var harness = TestGameHarness.forDefaultMap()) {
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(true);
            Game game = harness.load();
            game.getPlayers().clear();
            game.addPlayer("123", "Human").setFaction("hacan");
            List<Player> agents = new ArrayList<>();
            for (int index = 1; index <= 5; index++) {
                agents.add(game.addPlayer(TestBedService.VIRTUAL_SEAT_ID_PREFIX + "0" + index, "Agent " + index));
            }
            ManagedSeats.register(game, "123", agents);
            game.setStoredValue("wrapperAgentActiveRequest", "interrupted");
            game.setStoredValue("wrapperAgentRequest_interrupted", "STARTED");
            assertTrue(GameSaveService.save(game, "managed crash proof"));
            Game loaded = harness.load();
            assertTrue(ManagedSeats.canUseHumanBridge(loaded));
            assertEquals("123", ManagedSeats.owner(loaded));
            assertEquals(5, ManagedSeats.agents(loaded).size());
            assertEquals("interrupted", loaded.getStoredValue("wrapperAgentActiveRequest"));
            assertEquals("STARTED", loaded.getStoredValue("wrapperAgentRequest_interrupted"));
            assertNull(TestBedService.getActingAs(loaded, "123"));
            assertSame(loaded.getPlayer("123"), ManagedSeats.resolve(loaded, "123", loaded.getPlayer("123")));
        }
    }
}

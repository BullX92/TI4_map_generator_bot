package ti4.game.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.StringHelper;
import ti4.service.testbed.TestBedService;
import ti4.settings.GlobalSettings;
import ti4.testUtils.BaseTi4Test;

class VirtualSeatPersistenceProofTest extends BaseTi4Test {
    @Test
    void originalFileSaveRetainsVirtualIdentitiesPrivateStateAndActingSeat() {
        try (var settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS);
                var harness = TestGameHarness.forDefaultMap()) {
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(true);
            Game game = harness.load();
            game.getPlayers().clear();
            game.setPlayerCountForMap(3);
            game.setPhaseOfGame("strategy");
            Player human = seat(game, "123", "Human", "hacan", "yellow", "1001");
            Player a = seat(game, TestBedService.VIRTUAL_SEAT_ID_PREFIX + "01", "Virtual A", "sol", "blue", "1002");
            Player b =
                    seat(game, TestBedService.VIRTUAL_SEAT_ID_PREFIX + "02", "Virtual B", "arborec", "green", "1003");
            a.addSC(1);
            a.setNotes(StringHelper.escape("Private A"));
            b.setNotes(StringHelper.escape("Private B"));
            TestBedService.markAsTestBed(game, true);
            TestBedService.allowRealPlayers(game);
            TestBedService.setActingAs(game, human.getUserID(), b);

            assertTrue(GameSaveService.save(game, "Virtual seat persistence proof"));
            Game loaded = harness.load();

            assertNotNull(loaded);
            assertEquals(
                    Set.of(human.getUserID(), a.getUserID(), b.getUserID()),
                    loaded.getPlayers().keySet());
            assertTrue(TestBedService.isTestBed(loaded));
            assertTrue(TestBedService.allowsRealPlayers(loaded));
            assertEquals(
                    b.getUserID(),
                    TestBedService.getActingAs(loaded, human.getUserID()).getUserID());
            assertEquals(Set.of(1), loaded.getPlayer(a.getUserID()).getSCs());
            assertTrue(loaded.getPlayer(b.getUserID()).getSCs().isEmpty());
            assertEquals(
                    "Private A",
                    StringHelper.unescape(loaded.getPlayer(a.getUserID()).getNotes()));
            assertEquals(
                    "Private B",
                    StringHelper.unescape(loaded.getPlayer(b.getUserID()).getNotes()));
            assertEquals("1002", loaded.getPlayer(a.getUserID()).getCardsInfoThreadID());
            assertEquals("1003", loaded.getPlayer(b.getUserID()).getCardsInfoThreadID());
        }
    }

    private static Player seat(Game game, String id, String name, String faction, String color, String cardsId) {
        Player player = game.addPlayer(id, name);
        player.setFaction(faction);
        player.setColor(color);
        player.setCardsInfoThreadID(cardsId);
        return player;
    }
}

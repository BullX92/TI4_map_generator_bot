package ti4.service.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import ti4.discord.interactions.commands.game.WeirdGameSetup;
import ti4.game.Game;
import ti4.message.MessageHelper;
import ti4.service.fow.GMService;
import ti4.testUtils.BaseTi4Test;

/**
 * The Twilight's Fall homebrew-option flow (reachable from the FoW setup wizard's Step 0) used to post
 * its toggle confirmations unconditionally to {@code game.getMainGameChannel()}, leaking setup chatter
 * to every player in FoW games. {@link TEOptionService} now routes through a FoW-aware channel helper;
 * these tests pin down which channel-resolution path gets used in each mode.
 */
class TEOptionServiceTest extends BaseTi4Test {

    @ParameterizedTest
    @ValueSource(strings = {"newpok", "oldpok", "te"})
    void choosingAnExpansionAfterMiniMiltyRestoresPokComponents(String choice) {
        Game game = new Game();
        game.setPublicObjectives1(new ArrayList<>());
        game.setPublicObjectives2(new ArrayList<>());
        game.addPlayer("1", "Human");
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
        try (MockedStatic<MessageHelper> messages = mockStatic(MessageHelper.class)) {
            assertTrue(WeirdGameSetup.applyBaseGameMode(event, game));
            assertEquals("techs_base", game.getTechnologyDeckID());

            TEOptionService.chooseExp(event, game, "chooseExp_" + choice);

            assertFalse(game.isBaseGameMode());
            assertFalse(game.isMiltyModMode());
            assertEquals("te".equals(choice), game.isThundersEdge());
            assertEquals(
                    "oldpok".equals(choice), !game.getStoredValue("useOldPok").isEmpty());
            assertEquals("techs_pok_c4", game.getTechnologyDeckID());
            assertEquals("agendas_pok", game.getAgendaDeckID());
            assertEquals("relics_pok", game.getRelicDeckID());
            assertEquals("explores_pok", game.getExplorationDeckID());
            assertEquals("te".equals(choice) ? "action_cards_te" : "action_cards_pok", game.getAcDeckID());
            assertEquals("te".equals(choice), game.getAllRelics().contains("quantumcore"));
            assertEquals("te".equals(choice), game.getAllRelics().contains("thesilverflame"));
        }
    }

    @Test
    void expansionSwitchDoesNotSilentlyLeaveAlreadySetUpBasePlayersWithoutLeaders() {
        Game game = new Game();
        game.setPublicObjectives1(new ArrayList<>());
        game.setPublicObjectives2(new ArrayList<>());
        game.addPlayer("1", "Human");
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
        try (MockedStatic<MessageHelper> messages = mockStatic(MessageHelper.class)) {
            assertTrue(WeirdGameSetup.applyBaseGameMode(event, game));
            game.getPlayer("1").setFaction("sol");

            TEOptionService.chooseExp(event, game, "chooseExp_te");

            assertTrue(game.isBaseGameMode());
            assertFalse(game.isThundersEdge());
            assertEquals("techs_base", game.getTechnologyDeckID());
            messages.verify(
                    () -> MessageHelper.sendMessageToChannel(
                            event.getMessageChannel(),
                            "Cannot switch from base game after faction setup. Finish the chosen setup or restart setup first."));
        }
    }

    @Test
    void twilightFallHomebrewRoutesToGmChannelInFowGames() {
        Game game = new Game();
        game.setName("te-homebrew-fow-routing-test");
        game.setFowMode(true);

        TextChannel gmChannel = mock(TextChannel.class);
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);

        try (MockedStatic<GMService> gmService = mockStatic(GMService.class)) {
            gmService.when(() -> GMService.getGMChannel(game)).thenReturn(gmChannel);

            TEOptionService.twilightDSSetup(event, game, "twilightDSSetup_justds");

            gmService.verify(() -> GMService.getGMChannel(game), times(1));
        }
    }

    @Test
    void twilightFallHomebrewDoesNotTouchGmChannelOutsideFow() {
        Game game = new Game();
        game.setName("te-homebrew-nonfow-routing-test");
        game.setFowMode(false);

        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);

        try (MockedStatic<GMService> gmService = mockStatic(GMService.class)) {
            TEOptionService.twilightDSSetup(event, game, "twilightDSSetup_justds");

            gmService.verify(() -> GMService.getGMChannel(game), never());
        }
    }
}

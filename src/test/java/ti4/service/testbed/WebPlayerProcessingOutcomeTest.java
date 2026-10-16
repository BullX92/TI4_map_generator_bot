package ti4.service.testbed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ti4.AsyncTI4DiscordBot;
import ti4.contest.replay.core.CombatContestSettings;
import ti4.executors.CircuitBreaker;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.persistence.GameManager;
import ti4.game.persistence.ManagedGame;
import ti4.logging.BotLogger;
import ti4.message.MessageHelper;
import ti4.service.game.GameNameService;
import ti4.testUtils.BaseTi4Test;

class WebPlayerProcessingOutcomeTest extends BaseTi4Test {

    @Test
    void staleButtonsSelectionsAndModalsNeverReportPressed() {
        try (Fixture fixture = new Fixture()) {
            assertEquals(
                    TestBedPress.Outcome.NOT_FOUND,
                    fixture.press("wrapperUnknownAction").outcome());
            assertEquals(
                    TestBedPress.Outcome.NOT_FOUND,
                    fixture.select("wrapperUnknownAction").outcome());
            assertEquals(
                    TestBedPress.Outcome.NOT_FOUND,
                    fixture.submit("wrapperUnknownAction").outcome());
        }
    }

    @Test
    void originalOwnerChecksRejectAllThreeInteractionKinds() {
        try (Fixture fixture = new Fixture()) {
            assertEquals(
                    TestBedPress.Outcome.REJECTED,
                    fixture.press("FFCC_hacan_notepadPost").outcome());
            assertEquals(
                    TestBedPress.Outcome.REJECTED,
                    fixture.select("FFCC_hacan_jmfA_test").outcome());
            assertEquals(
                    TestBedPress.Outcome.REJECTED,
                    fixture.submit("FFCC_hacan_notepadModal").outcome());
        }
    }

    @Test
    void actualAnnotatedButtonHandlerFailureNeverReportsPressed() {
        try (Fixture fixture = new Fixture()) {
            doThrow(new IllegalStateException("Original note read failed"))
                    .when(fixture.player)
                    .getNotes();
            assertEquals(
                    TestBedPress.Outcome.HANDLER_FAILED,
                    fixture.press("notepadPost").outcome());
            verify(fixture.player).getNotes();
        }
    }

    @Test
    void actualAnnotatedSelectionHandlerFailureNeverReportsPressed() {
        try (Fixture fixture = new Fixture()) {
            doThrow(new IllegalStateException("Original settings failed"))
                    .when(fixture.game)
                    .initializeMiltySettings();
            assertEquals(
                    TestBedPress.Outcome.HANDLER_FAILED,
                    fixture.select("jmfA_test").outcome());
            verify(fixture.game).initializeMiltySettings();
        }
    }

    @Test
    void actualAnnotatedModalHandlerFailureNeverReportsPressed() {
        try (Fixture fixture = new Fixture()) {
            doThrow(new IllegalStateException("Original note write failed"))
                    .when(fixture.player)
                    .setNotes("Notes");
            assertEquals(
                    TestBedPress.Outcome.HANDLER_FAILED,
                    fixture.submit("notepadModal").outcome());
            verify(fixture.player).setNotes("Notes");
        }
    }

    @Test
    void successfulOriginalHandlerStillReportsPressed() {
        try (Fixture fixture = new Fixture()) {
            assertEquals(
                    TestBedPress.Outcome.PRESSED, fixture.press("notepadPost").outcome());
            verify(fixture.player).getNotes();
        }
    }

    @Test
    void originalNotepadFormOpensAndSubmissionUpdatesActualPlayer() {
        try (Fixture fixture = new Fixture()) {
            var opened = fixture.press("notepadEdit~MDL");
            assertEquals(TestBedPress.Outcome.FORM_OPENED, opened.outcome());
            assertEquals("notepadModal", opened.recorder().modalId());
            assertEquals(
                    TestBedPress.Outcome.PRESSED, fixture.submit("notepadModal").outcome());
            verify(fixture.player).setNotes("Notes");
        }
    }

    @Test
    void disabledOriginalFeatureNeverReportsPressed() {
        try (Fixture fixture = new Fixture()) {
            assertEquals(
                    TestBedPress.Outcome.REJECTED,
                    fixture.press("combatReplayDebug_test").outcome());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MockedStatic<GameManager> games = mockStatic(GameManager.class);
        private final MockedStatic<GameNameService> names = mockStatic(GameNameService.class);
        private final MockedStatic<BotLogger> logs = mockStatic(BotLogger.class);
        private final MockedStatic<MessageHelper> messages = mockStatic(MessageHelper.class);
        private final MockedStatic<CombatContestSettings> combat = mockStatic(CombatContestSettings.class);
        private final MockedStatic<CircuitBreaker> breaker = mockStatic(CircuitBreaker.class);
        private final MockedStatic<AsyncTI4DiscordBot> bot = mockStatic(AsyncTI4DiscordBot.class);
        private final Game game = spy(new Game());
        private final Player player;
        private final Member member = mock(Member.class);
        private final Message message = mock(Message.class);

        private Fixture() {
            game.setName("wrapperoutcome");
            player = spy(game.addPlayer("123", "Player"));
            player.setFaction("sol");
            game.getPlayers().put("123", player);
            var managed = mock(ManagedGame.class);
            when(managed.getGame()).thenReturn(game);
            games.when(() -> GameManager.isValid("wrapperoutcome")).thenReturn(true);
            games.when(() -> GameManager.getManagedGame("wrapperoutcome")).thenReturn(managed);
            names.when(() -> GameNameService.getGameNameFromChannel(any(Interaction.class)))
                    .thenReturn("wrapperoutcome");
            bot.when(AsyncTI4DiscordBot::isUnstable).thenReturn(true);
            var user = mock(User.class);
            when(user.getId()).thenReturn("123");
            when(user.getEffectiveName()).thenReturn("Player");
            when(member.getId()).thenReturn("123");
            when(member.getUser()).thenReturn(user);
            var channel = mock(MessageChannelUnion.class);
            when(channel.getName()).thenReturn("wrapperoutcome-actions");
            when(channel.getId()).thenReturn("456");
            when(message.getJDA()).thenReturn(mock(JDA.class));
            when(message.getChannel()).thenReturn(channel);
            when(message.getId()).thenReturn("789");
            when(message.getComponentTree()).thenReturn(MessageComponentTree.of());
            when(message.reply(any(CharSequence.class))).thenReturn(mock(MessageCreateAction.class, RETURNS_SELF));
        }

        private TestBedPress.PressResult press(String id) {
            return WebPlayerButtonPress.press(game, member, player, message, Button.primary(id, "Press"));
        }

        private TestBedPress.PressResult select(String id) {
            return WebPlayerButtonPress.select(
                    game,
                    member,
                    player,
                    message,
                    StringSelectMenu.create(id).addOption("Sol", "sol").build(),
                    List.of("sol"));
        }

        private TestBedPress.PressResult submit(String id) {
            return WebPlayerButtonPress.submitModal(
                    game,
                    member,
                    player,
                    message,
                    Modal.create(id, "Notes")
                            .addComponents(Label.of(
                                    "Notes",
                                    TextInput.create("notes", TextInputStyle.PARAGRAPH)
                                            .build()))
                            .build(),
                    Map.of("notes", "Notes"));
        }

        @Override
        public void close() {
            bot.close();
            breaker.close();
            combat.close();
            messages.close();
            logs.close();
            names.close();
            games.close();
        }
    }
}

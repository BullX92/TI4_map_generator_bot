package ti4.service.testbed;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import org.junit.jupiter.api.Test;
import ti4.discord.interactions.routing.InteractionProcessingResult;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.FoWHelper;
import ti4.message.MessageHelper;
import ti4.settings.users.RefreshMapStyle;
import ti4.settings.users.UserSettings;
import ti4.settings.users.UserSettingsManager;

class WebPlayerButtonPressTest {

    @Test
    void originalSplitMapHandlerDeletesOnlyItsThinkingReply() throws Exception {
        Message message = mock(Message.class);
        when(message.getJDA()).thenReturn(mock(JDA.class));
        var channel = mock(MessageChannelUnion.class);
        when(message.getChannel()).thenReturn(channel);
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("123");
        UserSettings settings = mock(UserSettings.class);
        when(settings.getRefreshMapStyle()).thenReturn(RefreshMapStyle.SPLIT);
        var recorder = new TestBedPress.Recorder();
        var event =
                TestBedPress.humanEvent(message, Button.secondary("showGameAgain", "Refresh"), member, recorder, null);
        Game game = new Game();
        try (var users = mockStatic(UserSettingsManager.class);
                var fog = mockStatic(FoWHelper.class);
                var sends = mockStatic(MessageHelper.class)) {
            users.when(() -> UserSettingsManager.get("123")).thenReturn(settings);
            fog.when(() -> FoWHelper.canSeeWholeMap(game, event)).thenReturn(true);
            var handler = Class.forName("ti4.discord.interactions.buttons.handlers.game.ShowGameButtonHandler")
                    .getDeclaredMethod(
                            "simpleShowGame",
                            Game.class,
                            net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent.class,
                            String.class);
            handler.setAccessible(true);
            handler.invoke(null, game, event, "showGameAgain");
            sends.verify(() -> MessageHelper.sendMessageToChannelWithButtons(
                    org.mockito.ArgumentMatchers.eq(channel),
                    org.mockito.ArgumentMatchers.eq("Which part of the map do you want to see?"),
                    any()));
        }
        verify(message, never()).delete();
        verify(message, never()).editMessage(any(CharSequence.class));
        assertEquals(List.of(), recorder.unsupportedCalls());
        assertEquals(
                TestBedPress.Outcome.PRESSED,
                WebPlayerButtonPress.outcome(InteractionProcessingResult.COMPLETED, false, recorder));
    }

    @Test
    void ordinaryDeferredEditStillEditsAndDeletesItsSourceMessage() {
        Message message = mock(Message.class);
        when(message.getJDA()).thenReturn(mock(JDA.class));
        MessageEditAction edit = mock(MessageEditAction.class, RETURNS_SELF);
        when(message.editMessage(any(CharSequence.class))).thenReturn(edit);
        AuditableRestAction<Void> delete = mock(AuditableRestAction.class);
        when(message.delete()).thenReturn(delete);
        var recorder = new TestBedPress.Recorder();
        var event = TestBedPress.humanEvent(
                message, Button.primary("scPick_1", "Choose"), mock(Member.class), recorder, null);
        event.getHook().editOriginal("Updated choices").queue();
        event.getHook().deleteOriginal().queue();
        verify(edit).queue();
        verify(delete).queue();
        assertEquals(
                TestBedPress.Outcome.PRESSED,
                WebPlayerButtonPress.outcome(InteractionProcessingResult.COMPLETED, false, recorder));
    }

    @Test
    void separateReplyEditsAreCapturedWithoutEditingThePublicSource() {
        Message message = mock(Message.class);
        when(message.getJDA()).thenReturn(mock(JDA.class));
        var recorder = new TestBedPress.Recorder();
        var privateChannel = mock(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel.class);
        var create = mock(net.dv8tion.jda.api.requests.restaction.MessageCreateAction.class);
        when(privateChannel.sendMessage(any(net.dv8tion.jda.api.utils.messages.MessageCreateData.class)))
                .thenReturn(create);
        when(create.complete()).thenReturn(mock(Message.class));
        var event = TestBedPress.humanEvent(
                message,
                Button.secondary("showGameAgain", "Refresh"),
                mock(Member.class),
                recorder,
                new TestBedPress.Repost(privateChannel, "sol", false));
        event.getHook().editOriginal("Your private result").queue();
        verify(message, never()).editMessage(any(CharSequence.class));
        assertEquals(List.of("Your private result"), recorder.replies());
        assertEquals(
                TestBedPress.Outcome.PRESSED,
                WebPlayerButtonPress.outcome(InteractionProcessingResult.COMPLETED, false, recorder));
    }

    @Test
    void originalSelectAndModalEventsRetainActualActorAndSubmittedValues() {
        Message message = mock(Message.class);
        JDA jda = mock(JDA.class);
        when(message.getJDA()).thenReturn(jda);
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getUser()).thenReturn(user);
        var menu = StringSelectMenu.create("choose_faction")
                .addOption("Sol", "sol")
                .build();
        var selection = WebPlayerButtonPress.selectEvent(
                message, member, menu, List.of("sol"), new TestBedPress.Recorder(), null);
        assertSame(user, selection.getUser());
        assertSame(member, selection.getMember());
        assertSame(menu, selection.getSelectMenu());
        assertEquals(List.of("sol"), selection.getValues());

        var modal = Modal.create("setup", "Setup")
                .addComponents(Label.of(
                        "Name", TextInput.create("name", TextInputStyle.SHORT).build()))
                .build();
        var submission = WebPlayerButtonPress.modalEvent(
                message, member, modal, Map.of("name", "TI4"), new TestBedPress.Recorder(), null);
        assertSame(user, submission.getUser());
        assertEquals("setup", submission.getModalId());
        assertEquals("TI4", submission.getValue("name").getAsString());
    }

    @Test
    void humanInputUsesTheAuthenticatedPlayersActualSeat() {
        Game game = new Game();
        Player player = game.addPlayer("123", "Player");
        Member member = mock(Member.class);
        when(member.getId()).thenReturn("123");

        assertDoesNotThrow(
                () -> WebPlayerButtonPress.validateActor(game, member, player, Button.primary("scPick_1", "Pick")));
        Player foreign = game.addPlayer("456", "Other");
        assertThrows(
                IllegalArgumentException.class,
                () -> WebPlayerButtonPress.validateActor(game, member, foreign, Button.primary("scPick_1", "Pick")));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebPlayerButtonPress.validateActor(
                        game, member, player, Button.primary("dummyPlayerSpoofsol_scPick_1", "Pick")));
    }
}

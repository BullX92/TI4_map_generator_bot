package ti4.service.testbed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ti4.discord.interactions.routing.InteractionProcessingResult;

class WebInteractionRepliesTest {
    private Message source;
    private Message reply;
    private MessageChannel privateChannel;
    private MessageChannelUnion responseChannel;
    private MessageCreateAction send;
    private TestBedPress.Recorder recorder;
    private net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent event;

    @BeforeEach
    void setUp() {
        source = mock(Message.class);
        reply = mock(Message.class);
        when(source.getJDA()).thenReturn(mock(JDA.class));
        when(reply.getId()).thenReturn("9001");
        when(reply.getJumpUrl()).thenReturn("https://discord.com/channels/1/private/9001");
        privateChannel = mock(MessageChannel.class);
        responseChannel = mock(MessageChannelUnion.class);
        when(reply.getChannel()).thenReturn(responseChannel);
        send = mock(MessageCreateAction.class);
        when(privateChannel.sendMessage(any(MessageCreateData.class))).thenReturn(send);
        when(send.complete()).thenReturn(reply);
        recorder = new TestBedPress.Recorder();
        event = TestBedPress.humanEvent(
                source,
                Button.secondary("showGameAgain", "Refresh"),
                mock(Member.class),
                recorder,
                new TestBedPress.Repost(privateChannel, "sol", false));
    }

    @Test
    void ordinaryButtonDirectTextReplyHookEditsAndDeletesThePrivateResponse() {
        assertDirectReplyHookTargetsPrivateResponse(false);
    }

    @Test
    void ordinaryButtonDirectComponentReplyHookEditsAndDeletesThePrivateResponse() {
        assertDirectReplyHookTargetsPrivateResponse(true);
    }

    private void assertDirectReplyHookTargetsPrivateResponse(boolean components) {
        var ordinary = TestBedPress.humanEvent(
                source,
                Button.primary("scPick_1", "Pick"),
                mock(Member.class),
                recorder,
                new TestBedPress.Repost(privateChannel, "sol", false));
        assertFalse(ti4.discord.interactions.listeners.ButtonListener.shouldShowBotIsThinking(ordinary));
        var read = mock(RestAction.class);
        when(responseChannel.retrieveMessageById("9001")).thenReturn(read);
        when(read.complete()).thenReturn(reply);
        var edit = mock(MessageEditAction.class);
        when(reply.editMessage(any(MessageEditData.class))).thenReturn(edit);
        when(edit.complete()).thenReturn(reply);
        var delete = mock(AuditableRestAction.class);
        when(reply.delete()).thenReturn(delete);
        var action = components
                ? ordinary.replyComponents(ActionRow.of(Button.primary("scPick_2", "Next choice")))
                : ordinary.reply("Private text");
        var returned = new AtomicReference<Message>();
        action.queue(hook -> {
            returned.set(hook.retrieveOriginal().complete());
            hook.editOriginal("Private update").queue(updated -> assertEquals("9001", updated.getId()));
            hook.deleteOriginal().queue();
        });
        assertEquals("9001", returned.get().getId());
        var capture = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(privateChannel).sendMessage(capture.capture());
        if (components) {
            assertFalse(capture.getValue().getComponents().isEmpty());
        } else {
            assertEquals("Private text", capture.getValue().getContent());
        }
        verify(reply).editMessage(any(MessageEditData.class));
        verify(delete).complete();
        verify(source, never()).getChannel();
        verify(source, never()).delete();
        verify(source, never()).editMessage(any(MessageEditData.class));
        assertEquals(List.of(), recorder.unsupportedCalls());
    }

    @Test
    void ordinaryButtonDeferredEditKeepsItsSourceMessageTarget() {
        var ordinary = TestBedPress.humanEvent(
                source,
                Button.primary("scPick_1", "Pick"),
                mock(Member.class),
                recorder,
                new TestBedPress.Repost(privateChannel, "sol", false));
        var edit = mock(MessageEditAction.class);
        when(source.editMessage(any(CharSequence.class))).thenReturn(edit);
        when(source.editMessage(any(MessageEditData.class))).thenReturn(edit);
        when(edit.complete()).thenReturn(source);
        ordinary.deferEdit().queue(hook -> hook.editOriginal("Public update").complete());
        ordinary.editMessage("Public component update").queue(hook -> assertNotNull(hook.getInteraction()));
        verify(source).editMessage("Public update");
        var capture = ArgumentCaptor.forClass(MessageEditData.class);
        verify(source).editMessage(capture.capture());
        assertEquals("Public component update", capture.getValue().getContent());
        verify(privateChannel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test
    void ordinaryDeferredEditReturnsItsHookForCompleteAndSubmit() {
        var ordinary = TestBedPress.humanEvent(
                source,
                Button.primary("scPick_1", "Pick"),
                mock(Member.class),
                recorder,
                new TestBedPress.Repost(privateChannel, "sol", false));
        assertSame(ordinary.getInteraction(), ordinary.deferEdit().complete().getInteraction());
        assertSame(
                ordinary.getInteraction(), ordinary.deferEdit().submit().join().getInteraction());
        verify(privateChannel, never()).sendMessage(any(MessageCreateData.class));
    }

    @Test
    void modalSpawnerRemainsUnacknowledgedUntilItsResponseExecutes() {
        var modalEvent = TestBedPress.humanEvent(
                source,
                Button.primary("create~MDL", "Form"),
                mock(Member.class),
                recorder,
                new TestBedPress.Repost(privateChannel, "sol", false));
        assertFalse(modalEvent.isAcknowledged());
        var modal = net.dv8tion.jda.api.modals.Modal.create("form", "Form")
                .addComponents(net.dv8tion.jda.api.components.label.Label.of(
                        "Name",
                        net.dv8tion.jda.api.components.textinput.TextInput.create(
                                        "name", net.dv8tion.jda.api.components.textinput.TextInputStyle.SHORT)
                                .build()))
                .build();
        var response = modalEvent.replyModal(modal);
        assertFalse(modalEvent.isAcknowledged());
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        response.queue(ignored -> called.set(true));
        assertTrue(called.get());
        assertTrue(modalEvent.isAcknowledged());
    }

    @Test
    void deferredReplyAcknowledgesOnExecutionAndReturnsPrivateHook() {
        var modalEvent = TestBedPress.humanEvent(
                source,
                Button.primary("create~MDL", "Form"),
                mock(Member.class),
                recorder,
                new TestBedPress.Repost(privateChannel, "sol", false));
        var action = modalEvent.deferReply(true);
        assertFalse(modalEvent.isAcknowledged());
        action.queue(hook -> assertEquals(
                "9001", hook.editOriginal("Private response").complete().getId()));
        assertTrue(modalEvent.isAcknowledged());
        verify(source, never()).editMessage(any(MessageEditData.class));
    }

    @Test
    void privateComponentReplyCanBeRetrievedEditedAndDeletedWithoutChangingSource() {
        var read = mock(RestAction.class);
        when(responseChannel.retrieveMessageById("9001")).thenReturn(read);
        when(read.complete()).thenReturn(reply);
        var edit = mock(MessageEditAction.class);
        when(reply.editMessage(any(MessageEditData.class))).thenReturn(edit);
        when(edit.complete()).thenReturn(reply);
        var delete = mock(AuditableRestAction.class);
        when(reply.delete()).thenReturn(delete);
        var initial = new AtomicReference<Message>();
        event.getHook()
                .sendMessage("Private choices")
                .addComponents(ActionRow.of(Button.primary("scPick_1", "Pick")))
                .queue(initial::set);
        assertEquals("9001", initial.get().getId());
        assertEquals("9001", event.getHook().retrieveOriginal().complete().getId());
        var updated = new AtomicReference<Message>();
        event.getHook().editOriginal("Updated choices").setComponents(List.of()).queue(updated::set);
        assertEquals("9001", updated.get().getId());
        var capture = ArgumentCaptor.forClass(MessageEditData.class);
        verify(reply).editMessage(capture.capture());
        assertEquals("Updated choices", capture.getValue().getContent());
        assertEquals(List.of(), capture.getValue().getComponents());
        event.getHook().deleteOriginal().queue();
        verify(delete).complete();
        verify(source, never()).delete();
        verify(source, never()).editMessage(any(MessageEditData.class));
        verify(privateChannel, times(1)).sendMessage(any(MessageCreateData.class));
        assertEquals(List.of(), recorder.unsupportedCalls());
    }

    @Test
    void textOnlyReplyReturnsTheActualMessageAndDeferredReplyCallbackReturnsItsHook() {
        var callback = new AtomicReference<Message>();
        event.getHook().sendMessage("Only for this player").queue(callback::set);
        assertEquals("9001", callback.get().getId());
        var deferred = new AtomicReference<Object>();
        event.deferReply(true).queue(deferred::set);
        assertNotNull(deferred.get());
        var capture = ArgumentCaptor.forClass(MessageCreateData.class);
        verify(privateChannel, times(1)).sendMessage(capture.capture());
        assertEquals("Only for this player", capture.getValue().getContent());
        assertEquals(List.of(), capture.getValue().getComponents());
    }

    @Test
    void creatingTheInitialReplyByEditingADeferredReplyReturnsAMessage() {
        event.deferReply(true).queue();
        assertEquals(
                "9001",
                event.getHook().editOriginal("Private response").complete().getId());
        assertEquals(
                "9001", event.getHook().sendMessage("Follow-up").submit().join().getId());
        verify(privateChannel, times(2)).sendMessage(any(MessageCreateData.class));
        verify(source, never()).getChannel();
    }

    @Test
    void failedSendCallsFailureCallbackAndCannotProduceSuccessfulOutcome() {
        RuntimeException unavailable = new IllegalStateException("Discord unavailable");
        when(send.complete()).thenThrow(unavailable);
        var failure = new AtomicReference<Throwable>();
        event.getHook().sendMessage("Secret").queue(value -> fail("Must not succeed"), failure::set);
        assertSame(unavailable, failure.get());
        assertEquals(
                TestBedPress.Outcome.HANDLER_FAILED,
                WebPlayerButtonPress.outcome(InteractionProcessingResult.COMPLETED, false, recorder));
        assertTrue(event.getHook().sendMessage("Secret").submit().isCompletedExceptionally());
        verify(source, never()).delete();
    }

    @Test
    void failedPrivateEditOrDeleteIsReportedInsteadOfSilentSuccess() {
        event.getHook().sendMessage("Private choices").complete();
        var edit = mock(MessageEditAction.class);
        when(reply.editMessage(any(MessageEditData.class))).thenReturn(edit);
        when(edit.complete()).thenThrow(new IllegalStateException("Edit failed"));
        assertThrows(
                IllegalStateException.class,
                () -> event.getHook().editOriginal("New secret").complete());
        assertEquals(
                TestBedPress.Outcome.HANDLER_FAILED,
                WebPlayerButtonPress.outcome(InteractionProcessingResult.COMPLETED, false, recorder));
    }

    @Test
    void directCallbackMessageMutationsSettleAndReportFailuresBeforeReturning() {
        var delete = mock(AuditableRestAction.class);
        when(reply.delete()).thenReturn(delete);
        when(delete.complete()).thenThrow(new IllegalStateException("Delete failed"));
        var failure = new AtomicReference<Throwable>();
        event.getHook()
                .sendMessage("Private result")
                .queue(sent -> sent.delete().queue(null, failure::set));
        assertNotNull(failure.get());
        verify(delete).complete();
        verify(delete, never()).queue();
        assertEquals(
                TestBedPress.Outcome.HANDLER_FAILED,
                WebPlayerButtonPress.outcome(InteractionProcessingResult.COMPLETED, false, recorder));
    }

    @Test
    void initialDeferredEditPreservesOriginalFileUploads() throws java.io.IOException {
        try (var upload = net.dv8tion.jda.api.utils.FileUpload.fromData(new byte[] {1, 2, 3}, "map.png")) {
            event.deferReply(true).queue();
            event.getHook().editOriginal(MessageEditData.fromFiles(upload)).complete();
            var capture = ArgumentCaptor.forClass(MessageCreateData.class);
            verify(privateChannel).sendMessage(capture.capture());
            assertEquals("map.png", capture.getValue().getFiles().getFirst().getName());
        }
    }

    @Test
    void deletingTheOriginalDoesNotPromoteALaterFollowupToOriginal() {
        event.getHook().sendMessage("Original").complete();
        var delete = mock(AuditableRestAction.class);
        when(reply.delete()).thenReturn(delete);
        event.getHook().deleteOriginal().complete();
        event.getHook().sendMessage("Follow-up").complete();
        assertThrows(
                IllegalStateException.class,
                () -> event.getHook().retrieveOriginal().complete());
        assertThrows(
                IllegalStateException.class,
                () -> event.getHook().editOriginal("Resurrect").complete());
        verify(privateChannel, times(2)).sendMessage(any(MessageCreateData.class));
        verify(source, never()).delete();
    }

    @Test
    void missingPrivateDestinationNeverFallsBackToThePublicSourceChannel() {
        var noDestination = TestBedPress.humanEvent(
                source, Button.secondary("showGameAgain", "Refresh"), mock(Member.class), recorder, null);
        assertThrows(
                IllegalStateException.class,
                () -> noDestination.getHook().sendMessage("Secret").complete());
        verify(source, never()).getChannel();
        assertEquals(
                TestBedPress.Outcome.HANDLER_FAILED,
                WebPlayerButtonPress.outcome(InteractionProcessingResult.COMPLETED, false, recorder));
    }
}

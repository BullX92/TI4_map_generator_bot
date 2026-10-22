package ti4.service.testbed;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.InteractionType;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectInteraction;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.data.DataObject;
import ti4.discord.interactions.buttons.ButtonProcessor;
import ti4.discord.interactions.listeners.ModalListener;
import ti4.discord.interactions.routing.ComponentIdEnvelope;
import ti4.discord.interactions.routing.InteractionProcessingResult;
import ti4.discord.interactions.selections.SelectionMenuProcessor;
import ti4.game.Game;
import ti4.game.Player;

public final class WebPlayerButtonPress {

    private WebPlayerButtonPress() {}

    public static TestBedPress.PressResult press(
            Game game, Member member, Player player, Message message, Button button) {
        validateActor(game, member, player, button);
        TestBedPress.Recorder recorder = new TestBedPress.Recorder();
        TestBedSyncMessage synced = new TestBedSyncMessage(message);
        TestBedPress.Repost repost = player.getCardsInfoThread() == null
                ? null
                : new TestBedPress.Repost(player.getCardsInfoThread(), player.getFaction(), false);
        var event = TestBedPress.humanEvent(synced.proxy(), button, member, recorder, repost);
        return result(synced, recorder, ButtonProcessor.processNow(event));
    }

    public static TestBedPress.PressResult select(
            Game game, Member member, Player player, Message message, StringSelectMenu menu, List<String> values) {
        validateActor(game, member, player, Button.primary(menu.getCustomId(), "Select"));
        TestBedPress.Recorder recorder = new TestBedPress.Recorder();
        TestBedSyncMessage synced = new TestBedSyncMessage(message);
        var event = selectEvent(synced.proxy(), member, menu, values, recorder, repost(player));
        return result(synced, recorder, SelectionMenuProcessor.processNow(event));
    }

    public static TestBedPress.PressResult submitModal(
            Game game, Member member, Player player, Message message, Modal modal, Map<String, String> values) {
        validateActor(game, member, player, Button.primary(modal.getId(), "Submit"));
        TestBedPress.Recorder recorder = new TestBedPress.Recorder();
        TestBedSyncMessage synced = new TestBedSyncMessage(message);
        var event = modalEvent(synced.proxy(), member, modal, values, recorder, repost(player));
        return result(synced, recorder, ModalListener.getInstance().processNow(event));
    }

    private static TestBedPress.Repost repost(Player player) {
        var channel = player.getCardsInfoThread();
        return channel == null ? null : new TestBedPress.Repost(channel, player.getFaction(), false);
    }

    private static TestBedPress.PressResult result(
            TestBedSyncMessage synced, TestBedPress.Recorder recorder, InteractionProcessingResult processingResult) {
        boolean settled = synced.awaitPendingEdits(Duration.ofSeconds(15));
        TestBedPress.Outcome outcome = outcome(processingResult, synced.handlerFailed() || !settled, recorder);
        String detail =
                switch (processingResult) {
                    case COMPLETED ->
                        settled ? "Original handler completed the interaction." : "Discord updates are still pending.";
                    case REJECTED -> "Original interaction context rejected the interaction.";
                    case UNROUTED -> "No original handler matched the interaction.";
                    case UNSUPPORTED -> "The original handler is disabled or unsupported.";
                    case FAILED -> "The original handler failed while processing the interaction.";
                };
        return new TestBedPress.PressResult(outcome, detail, recorder);
    }

    static TestBedPress.Outcome outcome(
            InteractionProcessingResult result, boolean handlerFailed, TestBedPress.Recorder recorder) {
        if (handlerFailed || recorder.transportFailed() || result == InteractionProcessingResult.FAILED)
            return TestBedPress.Outcome.HANDLER_FAILED;
        return switch (result) {
            case COMPLETED -> TestBedPress.outcome(false, recorder);
            case UNROUTED -> TestBedPress.Outcome.NOT_FOUND;
            case REJECTED, UNSUPPORTED -> TestBedPress.Outcome.REJECTED;
            case FAILED -> TestBedPress.Outcome.HANDLER_FAILED;
        };
    }

    static StringSelectInteractionEvent selectEvent(
            Message message,
            Member member,
            StringSelectMenu menu,
            List<String> values,
            TestBedPress.Recorder recorder,
            TestBedPress.Repost repost) {
        TestBedPress.ReplyState state = new TestBedPress.ReplyState(false);
        List<String> selected = List.copyOf(values);
        var interaction = (StringSelectInteraction) Proxy.newProxyInstance(
                StringSelectInteraction.class.getClassLoader(),
                new Class<?>[] {StringSelectInteraction.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getSelectMenu", "getComponent" -> menu;
                    case "getCustomId", "getComponentId" -> menu.getCustomId();
                    case "getUniqueId" -> menu.getUniqueId();
                    case "getComponentType" -> menu.getType();
                    case "getValues" -> selected;
                    default ->
                        TestBedPress.answerInteraction(
                                (Interaction) self,
                                method,
                                args,
                                message,
                                Button.primary(menu.getCustomId(), "Select"),
                                member,
                                recorder,
                                repost,
                                state);
                });
        return new StringSelectInteractionEvent(message.getJDA(), 0, interaction);
    }

    static ModalInteractionEvent modalEvent(
            Message message,
            Member member,
            Modal modal,
            Map<String, String> values,
            TestBedPress.Recorder recorder,
            TestBedPress.Repost repost) {
        TestBedPress.ReplyState state = new TestBedPress.ReplyState(false);
        List<ModalMapping> mappings = modal.getComponentTree().findAll(TextInput.class).stream()
                .map(input -> new ModalMapping(
                        null,
                        DataObject.empty(),
                        DataObject.empty()
                                .put("id", input.getUniqueId())
                                .put("type", input.getType().getKey())
                                .put("custom_id", input.getCustomId())
                                .put("value", values.getOrDefault(input.getCustomId(), ""))))
                .toList();
        var interaction = (ModalInteraction) Proxy.newProxyInstance(
                ModalInteraction.class.getClassLoader(),
                new Class<?>[] {ModalInteraction.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getModalId", "getCustomId" -> modal.getId();
                    case "getValues" -> mappings;
                    case "getType" -> InteractionType.MODAL_SUBMIT;
                    case "getTypeRaw" -> InteractionType.MODAL_SUBMIT.getKey();
                    default ->
                        TestBedPress.answerInteraction(
                                (Interaction) self,
                                method,
                                args,
                                message,
                                Button.primary(modal.getId(), "Submit"),
                                member,
                                recorder,
                                repost,
                                state);
                });
        return new ModalInteractionEvent(message.getJDA(), 0, interaction);
    }

    static void validateActor(Game game, Member member, Player player, Button button) {
        boolean managed = ManagedSeats.canUseHumanBridge(game);
        boolean agent = managed && member != null && ManagedSeats.isActing(game, member.getId(), player);
        if (member == null
                || player == null
                || (!agent && (!member.getId().equals(player.getUserID()) || game.getPlayer(member.getId()) != player))
                || (ManagedSeats.isManaged(game) && !managed)
                || (TestBedService.isTestBed(game) && !managed)) {
            throw new IllegalArgumentException("A regular game and the authenticated player's own seat are required.");
        }
        if (ComponentIdEnvelope.decode(button.getCustomId()).spoofedFaction() != null) {
            throw new IllegalArgumentException("A web player cannot spoof another seat.");
        }
    }
}

package ti4.spring.api.wrapper;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.components.ActionComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ti4.discord.JdaService;
import ti4.discord.interactions.buttons.handlers.faction.homebrew.wftv.onyxxa.OnyxxaAbilityHandler;
import ti4.discord.interactions.routing.ComponentIdEnvelope;
import ti4.discord.utility.DiscordChannelUtility;
import ti4.discord.utility.DiscordErrorUtility;
import ti4.executors.ExecutionLockManager;
import ti4.executors.ExecutionLockType;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.persistence.GameManager;
import ti4.service.game.GameNameService;
import ti4.service.testbed.TestBedService;
import ti4.service.testbed.WebPlayerButtonPress;
import ti4.spring.service.deploy.ActiveLeaseService;
import ti4.spring.service.wrapper.WrapperDisplayContent;
import ti4.spring.service.wrapper.WrapperMessageIndex;

@Service
public class WrapperInteractionService {

    public record Option(String label, String value, String description) {}

    public record Action(
            String id,
            String token,
            String label,
            String style,
            String type,
            List<Option> options,
            int minValues,
            int maxValues,
            String placeholder) {}

    public record OfferedMessage(
            String id,
            String channelId,
            String content,
            List<Action> actions,
            List<WrapperDisplayContent.Image> images) {}

    public record Observation(String gameName, String seat, String phase, int round, List<OfferedMessage> messages) {}

    public record Field(
            String id,
            String label,
            String style,
            boolean required,
            int minLength,
            int maxLength,
            String value,
            String placeholder) {}

    public record Form(String token, String title, List<Field> fields) {}

    public record ActionResult(
            String status,
            String detail,
            List<String> replies,
            String modalId,
            List<String> unsupportedCalls,
            Form modal) {}

    private record OpenedModal(Modal definition, Message source) {}

    private final OfferedActionStore offers;
    private final ActiveLeaseService lease;
    private final WrapperMessageIndex messageIndex;
    private final Cache<String, List<ThreadChannel>> archivedThreads = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();
    private final Cache<String, OpenedModal> modals = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public WrapperInteractionService(
            OfferedActionStore offers, ActiveLeaseService lease, WrapperMessageIndex messageIndex) {
        this.offers = offers;
        this.lease = lease;
        this.messageIndex = messageIndex;
    }

    public Observation observe(String gameName, String userId) {
        requireReady();
        ExecutionLockManager.lock(gameName, ExecutionLockType.READ);
        try {
            Game game = game(gameName);
            Player player = player(game, userId);
            List<OfferedMessage> messages = new ArrayList<>();
            for (MessageChannel channel : channels(game, player).values()) {
                for (Message message : messageIndex.offeredMessages(gameName, channel)) {
                    if (!isOriginalMessage(message)) continue;
                    List<Action> actions = new ArrayList<>();
                    for (Button button : message.getComponentTree().findAll(Button.class)) {
                        if (!canOffer(game, message, button, player)) continue;
                        var offer = offers.issue(
                                gameName,
                                userId,
                                channel.getId(),
                                message.getId(),
                                button.getCustomId(),
                                revision(game, message));
                        actions.add(new Action(
                                actionId(message, button.getCustomId()),
                                offer.token(),
                                WrapperDisplayContent.buttonLabel(button),
                                button.getStyle().name(),
                                "BUTTON",
                                List.of(),
                                0,
                                0,
                                null));
                    }
                    for (StringSelectMenu menu : message.getComponentTree().findAll(StringSelectMenu.class)) {
                        if (!canOffer(game, message, menu.getCustomId(), menu.isDisabled(), player)) continue;
                        var offer = offers.issue(
                                gameName,
                                userId,
                                channel.getId(),
                                message.getId(),
                                menu.getCustomId(),
                                revision(game, message));
                        var options = menu.getOptions().stream()
                                .map(option ->
                                        new Option(option.getLabel(), option.getValue(), option.getDescription()))
                                .toList();
                        actions.add(new Action(
                                actionId(message, menu.getCustomId()),
                                offer.token(),
                                menu.getPlaceholder(),
                                null,
                                "STRING_SELECT",
                                options,
                                menu.getMinValues(),
                                menu.getMaxValues(),
                                menu.getPlaceholder()));
                    }
                    if (!actions.isEmpty() || WrapperDisplayContent.isMiltyInformation(message)) {
                        messages.add(new OfferedMessage(
                                message.getId(),
                                channel.getId(),
                                message.getContentRaw(),
                                actions,
                                WrapperDisplayContent.images(message)));
                    }
                }
            }
            return new Observation(gameName, player.getFaction(), game.getPhaseOfGame(), game.getRound(), messages);
        } finally {
            ExecutionLockManager.unlock(gameName, ExecutionLockType.READ);
        }
    }

    public ActionResult act(String gameName, String userId, String token, String requestId, List<String> values) {
        requireReady();
        if (!lease.mayMutate()) throw unavailable("This instance does not own the active game lease.");
        ExecutionLockManager.lock(gameName, ExecutionLockType.WRITE);
        try {
            Game game = game(gameName);
            Player player = player(game, userId);
            var offer = offers.lookup(token, gameName, userId);
            MessageChannel channel = channels(game, player).get(offer.channelId());
            if (channel == null) throw new OfferedActionStore.OfferRejected();
            Message message;
            try {
                message = channel.retrieveMessageById(offer.messageId()).complete();
            } catch (RuntimeException e) {
                throw new OfferedActionStore.OfferRejected();
            }
            if (!isOriginalMessage(message) || !gameName.equals(GameNameService.getGameNameFromChannel(channel))) {
                throw new OfferedActionStore.OfferRejected();
            }
            Button button = message.getComponentTree().findAll(Button.class).stream()
                    .filter(candidate -> offer.componentId().equals(candidate.getCustomId()))
                    .filter(candidate -> canOffer(game, message, candidate, player))
                    .findFirst()
                    .orElse(null);
            StringSelectMenu menu = message.getComponentTree().findAll(StringSelectMenu.class).stream()
                    .filter(candidate -> offer.componentId().equals(candidate.getCustomId()))
                    .filter(candidate ->
                            canOffer(game, message, candidate.getCustomId(), candidate.isDisabled(), player))
                    .findFirst()
                    .orElse(null);
            if (button == null && menu == null) throw new OfferedActionStore.OfferRejected();
            if (menu != null) validateSelection(menu, values);
            Member member = game.getGuild().retrieveMemberById(userId).complete();
            offers.claim(token, gameName, userId, revision(game, message), requestId);
            var result = menu == null
                    ? WebPlayerButtonPress.press(game, member, player, message, button)
                    : WebPlayerButtonPress.select(game, member, player, message, menu, values);
            return result(game, userId, message, result);
        } finally {
            ExecutionLockManager.unlock(gameName, ExecutionLockType.WRITE);
        }
    }

    public ActionResult submitModal(
            String gameName, String userId, String token, String requestId, Map<String, String> fields) {
        requireReady();
        if (!lease.mayMutate()) throw unavailable("This instance does not own the active game lease.");
        ExecutionLockManager.lock(gameName, ExecutionLockType.WRITE);
        try {
            Game game = game(gameName);
            Player player = player(game, userId);
            var offer = offers.lookup(token, gameName, userId);
            OpenedModal opened = modals.getIfPresent(token);
            if (opened == null || !channels(game, player).containsKey(offer.channelId())) {
                throw new OfferedActionStore.OfferRejected();
            }
            validateFields(opened.definition(), fields);
            Member member = game.getGuild().retrieveMemberById(userId).complete();
            offers.claim(token, gameName, userId, modalRevision(game), requestId);
            modals.invalidate(token);
            var result = WebPlayerButtonPress.submitModal(
                    game, member, player, opened.source(), opened.definition(), fields);
            return result(game, userId, opened.source(), result);
        } finally {
            ExecutionLockManager.unlock(gameName, ExecutionLockType.WRITE);
        }
    }

    static void validateSelection(StringSelectMenu menu, List<String> values) {
        if (values == null
                || values.size() < menu.getMinValues()
                || values.size() > menu.getMaxValues()
                || values.stream().distinct().count() != values.size()
                || values.stream()
                        .anyMatch(value -> menu.getOptions().stream()
                                .noneMatch(option -> option.getValue().equals(value)))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Select only offered values within the selection limit.");
        }
    }

    static void validateFields(Modal modal, Map<String, String> values) {
        if (!supportsModal(modal)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "This form contains unsupported input types.");
        }
        if (values == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form values are required.");
        var inputs = modal.getComponentTree().findAll(TextInput.class);
        if (values.keySet().stream()
                .anyMatch(id ->
                        inputs.stream().noneMatch(input -> input.getCustomId().equals(id)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown form field.");
        }
        for (TextInput input : inputs) {
            String value = values.getOrDefault(input.getCustomId(), "");
            int min = Math.max(input.isRequired() ? 1 : 0, input.getMinLength());
            int max = input.getMaxLength() < 0 ? TextInput.MAX_VALUE_LENGTH : input.getMaxLength();
            if (value == null
                    || value.length() > max
                    || (value.length() < min && (input.isRequired() || !value.isEmpty()))) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A form field does not satisfy its length limits.");
            }
        }
    }

    private ActionResult result(
            Game game, String userId, Message message, ti4.service.testbed.TestBedPress.PressResult result) {
        Form form = null;
        Modal modal = result.recorder().modalDefinition();
        if (modal != null && !supportsModal(modal)) {
            List<String> unsupported = new ArrayList<>(result.recorder().unsupportedCalls());
            unsupported.add("Modal contains unsupported input types.");
            return new ActionResult(
                    "REJECTED",
                    "This form contains unsupported input types.",
                    result.recorder().replies(),
                    modal.getId(),
                    unsupported,
                    null);
        }
        if (modal != null) {
            var offer = offers.issue(
                    game.getName(),
                    userId,
                    message.getChannel().getId(),
                    message.getId(),
                    modal.getId(),
                    modalRevision(game));
            modals.put(offer.token(), new OpenedModal(modal, message));
            Map<String, String> labels = new LinkedHashMap<>();
            for (Label label : modal.getComponentTree().findAll(Label.class)) {
                if (label.getChild().getType() == net.dv8tion.jda.api.components.Component.Type.TEXT_INPUT) {
                    labels.put(label.getChild().asTextInput().getCustomId(), label.getLabel());
                }
            }
            var fields = modal.getComponentTree().findAll(TextInput.class).stream()
                    .map(input -> new Field(
                            input.getCustomId(),
                            labels.getOrDefault(input.getCustomId(), input.getCustomId()),
                            input.getStyle().name(),
                            input.isRequired(),
                            Math.max(0, input.getMinLength()),
                            input.getMaxLength() < 0 ? TextInput.MAX_VALUE_LENGTH : input.getMaxLength(),
                            input.getValue(),
                            input.getPlaceHolder()))
                    .toList();
            form = new Form(offer.token(), modal.getTitle(), fields);
        }
        return new ActionResult(
                result.outcome().name(),
                result.detail(),
                result.recorder().replies(),
                result.recorder().modalId(),
                result.recorder().unsupportedCalls(),
                form);
    }

    private static String modalRevision(Game game) {
        return "modal:" + game.getLastModifiedDate();
    }

    static boolean supportsModal(Modal modal) {
        return modal.getComponentTree().findAll(Label.class).stream()
                        .allMatch(label ->
                                label.getChild().getType() == net.dv8tion.jda.api.components.Component.Type.TEXT_INPUT)
                && modal.getComponentTree().findAll(ActionComponent.class).isEmpty();
    }

    static boolean canOffer(Game game, Message message, Button button, Player player) {
        return canOffer(game, message, button.getCustomId(), button.isDisabled(), player);
    }

    private static boolean canOffer(Game game, Message message, String componentId, boolean disabled, Player player) {
        if (componentId == null || disabled) return false;
        var envelope = ComponentIdEnvelope.decode(componentId);
        if (envelope.spoofedFaction() != null) return false;
        String owner = envelope.ownerFaction();
        return owner == null
                || owner.equals(player.getFaction())
                || ("firmament".equals(owner)
                        && player.getFaction() != null
                        && player.getFaction().contains("obsidian"))
                || OnyxxaAbilityHandler.hasGrantedHolderAccess(game, message, player, owner);
    }

    static String revision(Game game, Message message) {
        String components = message.getComponentTree().findAll(ActionComponent.class).stream()
                .map(WrapperInteractionService::componentRevision)
                .reduce("", (left, right) -> left + "|" + right);
        String source = game.getLastModifiedDate() + "|" + message.getId() + "|" + message.getTimeEdited() + "|"
                + message.getContentRaw() + "|" + components;
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String actionId(Message message, String componentId) {
        String source = message.getChannel().getId() + ":" + message.getId() + ":" + componentId;
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String componentRevision(ActionComponent component) {
        var data = net.dv8tion.jda.api.utils.data.DataObject.empty()
                .put("type", component.getType().name())
                .put("id", component.getCustomId())
                .put("disabled", component.isDisabled());
        if (component instanceof Button button) {
            data.put("label", button.getLabel()).put("style", button.getStyle().name());
        }
        if (component instanceof StringSelectMenu menu) {
            data.put("min", menu.getMinValues())
                    .put("max", menu.getMaxValues())
                    .put("placeholder", menu.getPlaceholder());
            data.put(
                    "options",
                    menu.getOptions().stream()
                            .map(option -> net.dv8tion.jda.api.utils.data.DataObject.empty()
                                    .put("value", option.getValue())
                                    .put("label", option.getLabel())
                                    .put("description", option.getDescription()))
                            .toList());
        }
        return data.toString();
    }

    private static Game game(String gameName) {
        var managed = GameManager.getManagedGame(gameName);
        if (managed == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown game.");
        Game game = managed.getGame();
        if (game == null || TestBedService.isTestBed(game)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A regular human game is required.");
        }
        return game;
    }

    private static Player player(Game game, String userId) {
        Player player = game.getPlayer(userId);
        if (player == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not occupy a seat.");
        return player;
    }

    private Map<String, MessageChannel> channels(Game game, Player player) {
        Map<String, MessageChannel> channels = new LinkedHashMap<>();
        if (!game.isFowMode()) {
            addPublicChannel(channels, game.getMainGameChannel());
            addPublicChannel(channels, game.getActionsChannel());
            for (String id : messageIndex.channelIds(game.getName())) {
                if (channels.containsKey(id)) continue;
                ThreadChannel thread = retrieveThread(game, id);
                if (thread != null && isPublicGameThread(game, thread)) add(channels, thread);
            }
        }
        add(channels, player.getPrivateChannel());
        String threadId = player.getCardsInfoThreadID();
        if (threadId != null && !threadId.isBlank() && !"null".equals(threadId)) {
            add(channels, retrieveThread(game, threadId));
        }
        return channels;
    }

    private static ThreadChannel retrieveThread(Game game, String id) {
        try {
            return DiscordChannelUtility.retrieveThreadChannelById(game.getGuild(), id)
                    .complete();
        } catch (RuntimeException e) {
            if (DiscordErrorUtility.isUnknownChannelError(e)) return null;
            throw e;
        }
    }

    static boolean isPublicGameThread(Game game, ThreadChannel thread) {
        if (game.isFowMode() || thread.getType() != ChannelType.GUILD_PUBLIC_THREAD) return false;
        String parentId = thread.getParentChannel().getId();
        return game.getMainGameChannel() != null
                        && parentId.equals(game.getMainGameChannel().getId())
                || game.getActionsChannel() != null
                        && parentId.equals(game.getActionsChannel().getId());
    }

    private void addPublicChannel(Map<String, MessageChannel> channels, TextChannel channel) {
        add(channels, channel);
        if (channel == null) return;
        channel.getThreadChannels().stream()
                .filter(thread -> thread.getType() == ChannelType.GUILD_PUBLIC_THREAD)
                .forEach(thread -> add(channels, thread));
        for (ThreadChannel thread : archivedThreads.get(channel.getId(), ignored -> {
            List<ThreadChannel> all = new ArrayList<>();
            for (ThreadChannel archived : channel.retrieveArchivedPublicThreadChannels()) all.add(archived);
            return List.copyOf(all);
        })) {
            if (thread.getType() == ChannelType.GUILD_PUBLIC_THREAD
                    && channel.getId().equals(thread.getParentChannel().getId())) add(channels, thread);
        }
    }

    private static void add(Map<String, MessageChannel> channels, MessageChannel channel) {
        if (channel != null) channels.put(channel.getId(), channel);
    }

    private static boolean isOriginalMessage(Message message) {
        return message.getAuthor().getId().equals(JdaService.jda.getSelfUser().getId());
    }

    private static void requireReady() {
        if (JdaService.jda == null || !JdaService.isReadyToReceiveCommands()) {
            throw unavailable("The original game server is not ready.");
        }
    }

    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}

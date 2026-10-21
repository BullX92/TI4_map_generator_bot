package ti4.spring.api.wrapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ti4.AsyncTI4DiscordBot;
import ti4.discord.JdaService;
import ti4.discord.utility.DiscordChannelUtility;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.persistence.GameManager;
import ti4.service.async.ReserveGameNumberService;
import ti4.service.game.CreateGameService;
import ti4.service.testbed.ManagedSeats;
import ti4.service.testbed.TestBedService;
import ti4.spring.service.deploy.ActiveLeaseService;
import ti4.spring.service.wrapper.WrapperCreationOperation;
import ti4.spring.service.wrapper.WrapperCreationOperations;

@Service
public class WrapperGameCreationService {
    public record EligibleMember(String id, String name) {}

    public record Options(boolean canCreate, List<EligibleMember> members, boolean canCreateAgents) {
        public Options(boolean canCreate, List<EligibleMember> members) {
            this(canCreate, members, false);
        }
    }

    public record Operation(String requestId, String gameName, String state, String message) {}

    private final WrapperCreationOperations operations;
    private final ActiveLeaseService lease;

    public WrapperGameCreationService(WrapperCreationOperations operations, ActiveLeaseService lease) {
        this.operations = operations;
        this.lease = lease;
    }

    public Options options(String actorId) {
        Member actor = requireAdministrator(actorId);
        try {
            List<EligibleMember> members = actor.getGuild().loadMembers().get().stream()
                    .filter(member ->
                            !member.getUser().isBot() && !member.getUser().isSystem())
                    .map(member -> new EligibleMember(member.getId(), member.getEffectiveName()))
                    .sorted(Comparator.comparing(EligibleMember::name).thenComparing(EligibleMember::id))
                    .toList();
            return new Options(true, members, TestBedService.isEnabled() && TestBedService.isDeveloper(actor));
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Own-server member list unavailable");
        }
    }

    public Operation status(String actorId, String requestId) {
        requireAdministrator(actorId);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request ID");
        }
        return known(operations
                .find(actorId, requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creation request not found")));
    }

    public Operation create(String actorId, String requestId, String title, List<String> memberIds) {
        return create(actorId, requestId, title, memberIds, 0);
    }

    public Operation create(String actorId, String requestId, String title, List<String> memberIds, int botCount) {
        Member actor = requireAdministrator(actorId);
        requireLease();
        String normalizedTitle = validate(requestId, title, memberIds, actorId);
        if (botCount != 0 && (botCount != 5 || memberIds.size() != 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent games require you and five agents");
        }
        if (botCount > 0 && (!TestBedService.isEnabled() || !TestBedService.isDeveloper(actor))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent transport requires enabled testbed and developer role");
        }
        String hash = hash(botCount == 0 ? normalizedTitle : normalizedTitle + "|agents=5", memberIds);
        var previous = operations.find(actorId, requestId);
        if (previous.isPresent()) {
            if (!hash.equals(previous.get().getPayloadHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request ID already used for different input");
            }
            return known(previous.get());
        }
        Guild guild = actor.getGuild();
        List<Member> members = new ArrayList<>();
        for (String id : memberIds) {
            Member member;
            try {
                member = guild.retrieveMemberById(id).useCache(false).complete();
            } catch (RuntimeException failure) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected member is unavailable");
            }
            if (member == null
                    || !guild.equals(member.getGuild())
                    || !id.equals(member.getId())
                    || member.getUser().isBot()
                    || member.getUser().isSystem()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Only real own-server human members are allowed");
            }
            members.add(member);
        }
        synchronized (CreateGameService.class) {
            previous = operations.find(actorId, requestId);
            if (previous.isPresent()) {
                if (!hash.equals(previous.get().getPayloadHash())) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Request ID already used for different input");
                }
                return known(previous.get());
            }
            requireLease();
            preflight(guild);
            TextChannel replyChannel = guild.getTextChannelsByName("bot-log", true).stream()
                    .filter(channel -> guild.equals(channel.getGuild()) && channel.canTalk())
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "Own bot-log channel unavailable"));
            int candidateNumber = GameManager.getLatestPbdNumber();
            String gameName;
            do {
                gameName = "pbd" + (++candidateNumber);
            } while (ReserveGameNumberService.isGameNumReserved(gameName)
                    || operations.isReserved(gameName)
                    || CreateGameService.gameOrRoleAlreadyExists(gameName));
            String categoryName = CreateGameService.getCategoryNameForGame(gameName, guild);
            Category existing = guild.getCategoriesByName(categoryName, false).stream()
                    .findFirst()
                    .orElse(null);
            if (existing != null) verifyCategory(guild, existing);
            if (existing == null && !CreateGameService.serverCanHostNewGameCategory(guild)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Own server cannot host another game category");
            }
            String allocated;
            do {
                allocated = CreateGameService.getNextPbdGameName();
            } while (operations.isReserved(allocated) || CreateGameService.gameOrRoleAlreadyExists(allocated));
            if (!allocated.equals(gameName)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Game allocation changed; retry the request");
            }
            WrapperCreationOperation operation = new WrapperCreationOperation(
                    actorId + ":" + requestId,
                    actorId,
                    requestId,
                    hash,
                    gameName,
                    AsyncTI4DiscordBot.INSTANCE_ID,
                    "CREATING");
            try {
                operation = operations.save(operation);
            } catch (RuntimeException failure) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Could not reserve creation request; no game provisioned");
            }
            try {
                requireLease();
                Category category = CreateGameService.createNewGameCategory(categoryName, guild);
                verifyCategory(guild, category);
                requireLease();
                Game game = CreateGameService.createGameChannels(
                        members, replyChannel, normalizedTitle, gameName, actor, category, this::requireLease);
                if (game == null) throw new IllegalStateException("Original creation incomplete");
                verifyAccess(game, guild, members);
                if (botCount > 0) provisionAgents(game, actorId);
                requireLease();
                GameManager.save(game, "Created game channels through own web wrapper");
                requireLease();
                operation = operations.save(operation.withState("COMPLETED"));
            } catch (RuntimeException failure) {
                var origin = failure.getStackTrace();
                ti4.logging.BotLogger.warning("Own web game creation stopped for " + gameName + ": "
                        + failure.getClass().getSimpleName() + (origin.length == 0 ? "" : " at " + origin[0]));
                operation = operations.save(operation.withState("NEEDS_REPAIR"));
            }
            return result(operation);
        }
    }

    private static final Permission[] REQUIRED_PERMISSIONS = {
        Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.MANAGE_ROLES,
        Permission.MANAGE_THREADS, Permission.CREATE_PUBLIC_THREADS, Permission.CREATE_PRIVATE_THREADS,
        Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.MESSAGE_HISTORY,
        Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ATTACH_FILES, Permission.PIN_MESSAGES
    };

    private void provisionAgents(Game game, String actorId) {
        List<Player> agents = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            agents.add(game.addPlayer(TestBedService.VIRTUAL_SEAT_ID_PREFIX + "0" + index, "Agent " + index));
        }
        ManagedSeats.register(game, actorId, agents);
        game.setPlayerCountForMap(6);
        game.setStrategyCardsPerPlayer(1);
        game.setVp(10);
        game.setMapTemplateID("6pStandard");
        GameManager.save(game, "Registered managed virtual seats");
        for (Player agent : agents) {
            requireLease();
            var cards = agent.createCardsInfoThread();
            if (cards == null
                    || cards.isPublic()
                    || cards.isInvitable()
                    || !game.getActionsChannel()
                            .getId()
                            .equals(cards.getParentChannel().getId())) {
                throw new IllegalStateException("Invalid agent private cards channel");
            }
            agent.sendCardsInfoThreadGreeting(cards);
            GameManager.save(game, "Created managed agent private cards channel");
        }
    }

    private static void preflight(Guild guild) {
        if (!CreateGameService.isGameCreationAllowed() || !CreateGameService.serverCanHostNewGame(guild)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Original game creation disabled or own server full");
        }
        if (!guild.getSelfMember().hasPermission(REQUIRED_PERMISSIONS)
                || !guild.getSelfMember().canInteract(guild.getPublicRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bot permissions or role hierarchy insufficient");
        }
    }

    private static void verifyCategory(Guild guild, Category category) {
        if (category == null
                || !guild.equals(category.getGuild())
                || category.getChannels().size() > 48
                || category.getPermissionOverride(guild.getPublicRole()) == null
                || !category.getPermissionOverride(guild.getPublicRole())
                        .getDenied()
                        .contains(Permission.VIEW_CHANNEL)
                || !guild.getSelfMember().hasPermission(category, REQUIRED_PERMISSIONS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Private own-server category unavailable");
        }
    }

    private void verifyAccess(Game game, Guild guild, List<Member> members) {
        TextChannel actions = game.getActionsChannel();
        TextChannel chat = game.getTableTalkChannel();
        if (actions == null
                || chat == null
                || !guild.equals(actions.getGuild())
                || !guild.equals(chat.getGuild())
                || game.getBotMapUpdatesThread() == null) throw new IllegalStateException("Missing game channels");
        Role role =
                guild.getRolesByName(game.getName(), true).stream().findFirst().orElseThrow();
        for (Member member : members) {
            requireLease();
            Member actual =
                    guild.retrieveMemberById(member.getId()).useCache(false).complete();
            if (actual == null
                    || !actual.getRoles().contains(role)
                    || !actual.hasPermission(actions, Permission.VIEW_CHANNEL)
                    || !actual.hasPermission(chat, Permission.VIEW_CHANNEL))
                throw new IllegalStateException("Missing player access");
            String cardsId = game.getPlayer(member.getId()).getCardsInfoThreadID();
            if (cardsId == null) throw new IllegalStateException("Missing private cards channel");
            var cards = DiscordChannelUtility.retrieveThreadChannelById(guild, cardsId)
                    .complete();
            if (cards == null
                    || cards.isPublic()
                    || cards.isInvitable()
                    || !guild.equals(cards.getGuild())
                    || !actions.getId().equals(cards.getParentChannel().getId()))
                throw new IllegalStateException("Invalid private cards channel");
            cards.addThreadMemberById(member.getId()).complete();
            if (cards.retrieveThreadMemberById(member.getId()).useCache(false).complete() == null) {
                throw new IllegalStateException("Missing private cards membership");
            }
        }
    }

    private Operation known(WrapperCreationOperation operation) {
        if ("CREATING".equals(operation.getState())
                && !AsyncTI4DiscordBot.INSTANCE_ID.equals(operation.getInstanceId())) {
            requireLease();
            operation = operations.save(operation.withState("NEEDS_REPAIR"));
        }
        return result(operation);
    }

    private static String validate(String requestId, String title, List<String> memberIds, String actorId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request ID");
        }
        if (title == null
                || title.isBlank()
                || title.length() > 60
                || title.codePoints().anyMatch(c -> Character.isISOControl(c) || "@<>`/#\\".indexOf(c) >= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid game title");
        }
        if (memberIds == null
                || memberIds.isEmpty()
                || memberIds.size() > 8
                || memberIds.stream().anyMatch(id -> id == null || !id.matches("[1-9][0-9]{0,19}"))
                || new HashSet<>(memberIds).size() != memberIds.size()
                || !memberIds.contains(actorId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Choose 1 to 8 different real members including yourself");
        }
        return title.strip();
    }

    private static String hash(String title, List<String> memberIds) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((title + "\n" + String.join(",", memberIds)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Operation result(WrapperCreationOperation operation) {
        return new Operation(
                operation.getRequestId(),
                operation.getGameName(),
                operation.getState(),
                "NEEDS_REPAIR".equals(operation.getState())
                        ? "Die Einrichtung wurde angehalten. Ein Administrator muss sie prüfen; bitte keine Ersatzpartie anlegen."
                        : null);
    }

    private void requireLease() {
        if (!lease.mayMutate() || lease.isDraining() || !lease.isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Active game server required");
        }
    }

    private Member requireAdministrator(String actorId) {
        Guild guild = JdaService.guildPrimary;
        if (guild == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Own server unavailable");
        Member member;
        try {
            member = guild.retrieveMemberById(actorId).useCache(false).complete();
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Own server administrator required");
        }
        if (member == null
                || !guild.equals(member.getGuild())
                || (!member.isOwner()
                        && member.getRoles().stream()
                                .noneMatch(role ->
                                        guild.equals(role.getGuild()) && JdaService.adminRoles.contains(role)))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Own server administrator required");
        }
        return member;
    }
}

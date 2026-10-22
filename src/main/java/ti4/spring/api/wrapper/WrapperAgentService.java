package ti4.spring.api.wrapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ti4.discord.JdaService;
import ti4.discord.interactions.routing.ComponentIdEnvelope;
import ti4.discord.utility.DiscordErrorUtility;
import ti4.executors.ExecutionLockManager;
import ti4.executors.ExecutionLockType;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.persistence.GameManager;
import ti4.helpers.Units.UnitType;
import ti4.service.testbed.ManagedSeats;
import ti4.service.testbed.TestBedPress;
import ti4.service.testbed.TestBedService;
import ti4.service.testbed.WebPlayerButtonPress;
import ti4.spring.service.deploy.ActiveLeaseService;
import ti4.spring.service.wrapper.WrapperMessageIndex;

@Service
public class WrapperAgentService {
    public record Seat(String id, String name, String faction, boolean human) {}

    public record View(
            String gameName,
            String phase,
            int round,
            boolean paused,
            List<Seat> seats,
            String waitingFor,
            String message,
            String status,
            String detail) {}

    private record Decision(Player player, Message message, Button button, String kind) {}

    private static final String ACTIVE_REQUEST = "wrapperAgentActiveRequest";
    private static final String REQUEST_PREFIX = "wrapperAgentRequest_";
    private static final Set<String> SUPPORTED_FACTIONS = Set.of(
            "sol",
            "hacan",
            "l1z1x",
            "letnev",
            "jolnar",
            "sardakk",
            "mentak",
            "yssaril",
            "muaat",
            "saar",
            "ghost",
            "nekro",
            "arborec",
            "yin",
            "xxcha",
            "empyrean",
            "naalu",
            "nomad",
            "mahact",
            "naaz",
            "titans",
            "cabal");
    private final ActiveLeaseService lease;
    private final WrapperMessageIndex messages;

    @Scheduled(fixedDelay = 5000, initialDelay = 15_000)
    public void tick() {
        if (!lease.mayMutate() || !lease.isReady() || lease.isDraining()) return;
        for (var managed : GameManager.getManagedGames()) {
            if (managed.isHasEnded()) continue;
            Game game = managed.getGame();
            if (game == null || !"true".equals(game.getStoredValue("wrapperAgentsRunning"))) continue;
            try {
                step(game.getName(), ManagedSeats.owner(game), "auto-" + UUID.randomUUID());
            } catch (RuntimeException failure) {
                ExecutionLockManager.lock(game.getName(), ExecutionLockType.WRITE);
                try {
                    Game latest = managed.getGame();
                    latest.removeStoredValue("wrapperAgentsRunning");
                    GameManager.save(latest, "Agent automatic run paused after failure");
                } finally {
                    ExecutionLockManager.unlock(game.getName(), ExecutionLockType.WRITE);
                }
            }
        }
    }

    public View running(String gameName, String actor, boolean enabled) {
        if (!lease.mayMutate() || !lease.isReady() || lease.isDraining())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Active ready server required");
        ExecutionLockManager.lock(gameName, ExecutionLockType.WRITE);
        try {
            Game game = authorized(gameName, actor);
            if (enabled && !game.getStoredValue(ACTIVE_REQUEST).isEmpty())
                return view(game, "UNCERTAIN", "Letzten Schritt zuerst prüfen.");
            game.setStoredValue("wrapperAgentsRunning", Boolean.toString(enabled));
            GameManager.save(game, enabled ? "Automatic agents enabled" : "Automatic agents paused");
            return view(game, enabled ? "RUNNING" : "PAUSED", enabled ? "Bots spielen automatisch." : "Bots pausiert.");
        } finally {
            ExecutionLockManager.unlock(gameName, ExecutionLockType.WRITE);
        }
    }

    public WrapperAgentService(ActiveLeaseService lease, WrapperMessageIndex messages) {
        this.lease = lease;
        this.messages = messages;
    }

    public View observe(String gameName, String actor) {
        ExecutionLockManager.lock(gameName, ExecutionLockType.READ);
        try {
            Game game = authorized(gameName, actor);
            return game.getStoredValue(ACTIVE_REQUEST).isEmpty()
                    ? view(
                            game,
                            "true".equals(game.getStoredValue("wrapperAgentsRunning")) ? "RUNNING" : "PAUSED",
                            "Einfache Botexpansion über Originalhandler.")
                    : view(
                            game,
                            "UNCERTAIN",
                            "Ein Schritt hat keinen bestätigten Abschluss. Vor Fortsetzung den Originalzustand prüfen.");
        } finally {
            ExecutionLockManager.unlock(gameName, ExecutionLockType.READ);
        }
    }

    public View step(String gameName, String actorId, String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid step request ID");
        }
        if (!lease.mayMutate() || !lease.isReady() || lease.isDraining()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Active ready server required");
        }
        ExecutionLockManager.lock(gameName, ExecutionLockType.WRITE);
        try {
            Game game = authorized(gameName, actorId);
            String previous = game.getStoredValue(REQUEST_PREFIX + requestId);
            if (!previous.isEmpty()) return view(game, previous, "Dieser Einzelschritt wird nicht erneut ausgeführt.");
            if (!game.getStoredValue(ACTIVE_REQUEST).isEmpty()) {
                return view(
                        game,
                        "UNCERTAIN",
                        "Ein früherer Schritt hat keinen bestätigten Abschluss. Zustand zuerst prüfen.");
            }
            if (game.getStoredValueMap().keySet().stream()
                            .filter(k -> k.startsWith(REQUEST_PREFIX))
                            .count()
                    >= 200) {
                return view(game, "PAUSED", "Das Limit dieses technischen Startnachweises ist erreicht.");
            }
            Member actor =
                    game.getGuild().retrieveMemberById(actorId).useCache(false).complete();
            if (!TestBedService.isDeveloper(actor)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Developer role required for agent control");
            }
            Decision offered = decision(game);
            if (offered == null) return view(game, "WAITING", waitingMessage(game));
            Decision decision = freshDecision(game, offered);
            if (decision == null)
                return view(game, "WAITING", "Das Originalangebot hat sich geändert. Aktuelle Angebote neu laden.");
            if (!lease.mayMutate())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Active lease lost");
            game.setStoredValue(ACTIVE_REQUEST, requestId);
            game.setStoredValue(REQUEST_PREFIX + requestId, "STARTED");
            GameManager.save(game, "Agent step intent recorded");
            int beforeDraft = game.getMiltyDraftManager().getDraftIndex();
            int beforeSecrets = decision.player().getSo();
            int beforeCards = decision.player().getSCs().size();
            var plan = "Taktischer Schritt".equals(decision.kind())
                    ? WrapperTacticalPolicy.current(game, decision.player())
                    : null;
            if (plan != null) game.setStoredValue(WrapperTacticalPolicy.PLAN, plan.encode());
            int beforeTokens = decision.player().getTacticalCC();
            int beforeCarriers = plan == null
                    ? 0
                    : game.getTileByPosition(plan.origin())
                            .getSpaceUnitHolder()
                            .getUnitCount(UnitType.Carrier, decision.player());
            int beforeInfantry = plan == null
                    ? 0
                    : game.getTileByPosition(plan.origin())
                            .getUnitHolders()
                            .get(plan.sourcePlanet())
                            .getUnitCount(UnitType.Infantry, decision.player());
            try {
                TestBedPress.PressResult result;
                try {
                    result = ManagedSeats.asAgent(
                            game,
                            actor,
                            decision.player(),
                            () -> WebPlayerButtonPress.press(
                                    game, actor, decision.player(), decision.message(), decision.button()));
                } finally {
                    messages.refresh(decision.message());
                }
                boolean changed =
                        switch (decision.kind()) {
                            case "Milty-Auswahl" -> game.getMiltyDraftManager().getDraftIndex() > beforeDraft;
                            case "Geheimes Startziel abgelegt" ->
                                decision.player().getSo() == beforeSecrets - 1;
                            case "Strategiekarte gewählt" ->
                                decision.player().getSCs().size() == beforeCards + 1;
                            case "Taktischer Schritt" ->
                                WrapperTacticalPolicy.changed(
                                        game, decision.player(), plan, beforeTokens, beforeCarriers, beforeInfantry);
                            default -> false;
                        };
                String status = result.outcome() == TestBedPress.Outcome.PRESSED && changed ? "COMPLETED" : "UNCERTAIN";
                if ("COMPLETED".equals(status) && plan != null) {
                    if (plan.stage() == 11) game.removeStoredValue(WrapperTacticalPolicy.PLAN);
                    else
                        game.setStoredValue(
                                WrapperTacticalPolicy.PLAN, plan.next().encode());
                }
                if ("UNCERTAIN".equals(status)) game.removeStoredValue("wrapperAgentsRunning");
                game.setStoredValue(REQUEST_PREFIX + requestId, status);
                if ("COMPLETED".equals(status)) game.removeStoredValue(ACTIVE_REQUEST);
                GameManager.save(game, "Agent step " + status);
                return view(
                        game,
                        status,
                        decision.player().getUserName() + ": " + decision.kind()
                                + ("COMPLETED".equals(status)
                                        ? " abgeschlossen."
                                        : " angehalten; Originalzustand prüfen."));
            } catch (RuntimeException failure) {
                game.removeStoredValue("wrapperAgentsRunning");
                game.setStoredValue(ACTIVE_REQUEST, requestId);
                game.setStoredValue(REQUEST_PREFIX + requestId, "UNCERTAIN");
                GameManager.save(game, "Agent step uncertain; no automatic retry");
                return view(
                        game, "UNCERTAIN", "Der Schritt wurde angehalten. Vor Fortsetzung den Originalzustand prüfen.");
            }
        } finally {
            ExecutionLockManager.unlock(gameName, ExecutionLockType.WRITE);
        }
    }

    private Decision decision(Game game) {
        var draft = game.getMiltyDraftManager();
        if ("miltydraft".equals(game.getPhaseOfGame()) && !draft.isFinished()) {
            Player current = draft.getCurrentDraftPlayer(game);
            if (!ManagedSeats.isAgent(game, current)) return null;
            var picks = draft.getPlayerDraft(current);
            List<Button> choices = new ArrayList<>();
            if (picks.getFaction() == null)
                choices.addAll(draft.getFactionButtons().stream()
                        .filter(button ->
                                SUPPORTED_FACTIONS.contains(button.getCustomId().replace("milty_faction_", "")))
                        .sorted(Comparator.comparing(Button::getCustomId))
                        .toList());
            else if (picks.getSlice() == null) choices.addAll(draft.getSliceButtons());
            else if (picks.getPosition() == null) choices.addAll(draft.getPositionButtons());
            List<String> ids = choices.stream()
                    .map(button ->
                            ComponentIdEnvelope.decode(button.getCustomId()).handlerId())
                    .toList();
            return offered(game, current, ids, "Milty-Auswahl");
        }
        for (Player agent : ManagedSeats.agents(game)) {
            if (agent.getSo() > 1
                    && game.getRound() == 1
                    && List.of("playerSetup", "strategy", "miltydraft", "action")
                            .contains(game.getPhaseOfGame())) {
                List<String> discards = agent.getSecrets().values().stream()
                        .sorted()
                        .flatMap(value -> List.of("discardSecret_" + value, "SODISCARD_" + value).stream())
                        .toList();
                Decision discard = offered(game, agent, discards, "Geheimes Startziel abgelegt");
                if (discard != null) return discard;
            }
        }
        Player active = game.getActivePlayer();
        if ("strategy".equals(game.getPhaseOfGame())
                && ManagedSeats.isAgent(game, active)
                && active.getSCs().size() < game.getStrategyCardsPerPlayer()) {
            List<String> choices = ti4.helpers.Helper.getRemainingSCButtons(game, active).stream()
                    .map(b -> ComponentIdEnvelope.decode(b.getCustomId()).handlerId())
                    .sorted()
                    .toList();
            return offered(game, active, choices, "Strategiekarte gewählt");
        }
        if ("action".equals(game.getPhaseOfGame()) && ManagedSeats.isAgent(game, active)) {
            var plan = WrapperTacticalPolicy.current(game, active);
            if (plan != null) return offered(game, active, plan.buttons(active), "Taktischer Schritt");
        }
        return null;
    }

    private Decision freshDecision(Game game, Decision offered) {
        Message source;
        try {
            source = offered.message()
                    .getChannel()
                    .retrieveMessageById(offered.message().getId())
                    .complete();
        } catch (RuntimeException failure) {
            if (!DiscordErrorUtility.isUnknownMessageError(failure)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Original agent offer unavailable");
            }
            messages.remove(offered.message().getId());
            return null;
        }
        if (!source.getAuthor().getId().equals(JdaService.jda.getSelfUser().getId())) return null;
        messages.capture(source);
        Button button = source.getComponentTree().findAll(Button.class).stream()
                .filter(candidate ->
                        Objects.equals(candidate.getCustomId(), offered.button().getCustomId()))
                .filter(candidate -> WrapperInteractionService.canOffer(game, source, candidate, offered.player()))
                .findFirst()
                .orElse(null);
        return button == null ? null : new Decision(offered.player(), source, button, offered.kind());
    }

    private Decision offered(Game game, Player player, List<String> orderedIds, String kind) {
        List<MessageChannel> channels = new ArrayList<>();
        if (game.getMainGameChannel() != null) channels.add(game.getMainGameChannel());
        if (game.getActionsChannel() != null && !channels.contains(game.getActionsChannel()))
            channels.add(game.getActionsChannel());
        if (player.getCardsInfoThread() != null) channels.add(player.getCardsInfoThread());
        List<Decision> candidates = new ArrayList<>();
        for (MessageChannel channel : channels) {
            for (Message message : messages.offeredMessages(game.getName(), channel)) {
                if (!message.getAuthor()
                        .getId()
                        .equals(JdaService.jda.getSelfUser().getId())) continue;
                for (Button button : message.getComponentTree().findAll(Button.class)) {
                    if (button.getCustomId() == null) continue;
                    String id = ComponentIdEnvelope.decode(button.getCustomId()).handlerId();
                    if (orderedIds.contains(id) && WrapperInteractionService.canOffer(game, message, button, player)) {
                        candidates.add(new Decision(player, message, button, kind));
                    }
                }
            }
        }
        return candidates.stream()
                .min(Comparator.comparingInt((Decision d) -> orderedIds.indexOf(
                                ComponentIdEnvelope.decode(d.button().getCustomId())
                                        .handlerId()))
                        .thenComparing(d -> d.message().getId(), Comparator.reverseOrder()))
                .orElse(null);
    }

    private static Game authorized(String gameName, String actor) {
        var managed = GameManager.getManagedGame(gameName);
        Game game = managed == null ? null : managed.getGame();
        if (game == null || !ManagedSeats.isManaged(game)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No managed agent game");
        }
        if (!Objects.equals(actor, ManagedSeats.owner(game)) || game.getPlayer(actor) == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent game owner required");
        }
        if (!ManagedSeats.canUseHumanBridge(game)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent transport disabled or game unsupported");
        }
        return game;
    }

    private static View view(Game game, String status, String detail) {
        Player waiting = "miltydraft".equals(game.getPhaseOfGame())
                ? game.getMiltyDraftManager().getCurrentDraftPlayer(game)
                : game.getActivePlayer();
        return new View(
                game.getName(),
                game.getPhaseOfGame(),
                game.getRound(),
                !"true".equals(game.getStoredValue("wrapperAgentsRunning")),
                game.getPlayers().values().stream()
                        .map(p -> new Seat(
                                p.getUserID(), p.getUserName(), displayFaction(p), !ManagedSeats.isAgent(game, p)))
                        .toList(),
                waiting == null ? null : waiting.getUserName(),
                waitingMessage(game),
                status,
                detail);
    }

    private static String waitingMessage(Game game) {
        if ("action".equals(game.getPhaseOfGame())
                && game.getPlayers().size() == 6
                && game.getRealPlayers().size() == 6
                && game.getRealPlayers().stream()
                        .allMatch(p -> p.getSo() == 1 && p.getSCs().size() == 1))
            return "Spielstart erreicht. Bots expandieren mit Träger und Infanterie in unbesetzte Nachbarsysteme; dein Sitz wartet auf dich.";
        for (Player agent : ManagedSeats.agents(game)) {
            if (displayFaction(agent) != null && !SUPPORTED_FACTIONS.contains(agent.getFaction()))
                return agent.getUserName() + ": Fraktionsaufbau für " + agent.getFaction()
                        + " ist noch nicht automatisiert.";
        }
        if ("miltydraft".equals(game.getPhaseOfGame())) {
            Player current = game.getMiltyDraftManager().getCurrentDraftPlayer(game);
            if (ManagedSeats.isAgent(game, current)) {
                var picks = game.getMiltyDraftManager().getPlayerDraft(current);
                if (picks != null
                        && picks.getFaction() == null
                        && game.getMiltyDraftManager().getFactionButtons().stream()
                                .noneMatch(button -> SUPPORTED_FACTIONS.contains(
                                        button.getCustomId().replace("milty_faction_", "")))) {
                    return current.getUserName()
                            + ": Im Draft ist keine unterstützte Fraktion mit festem Startaufbau mehr frei.";
                }
            }
        }
        if ("miltydraft".equals(game.getPhaseOfGame()))
            return "Milty: menschliche Auswahl selbst treffen; Agenten mit Einzelschritt bedienen.";
        return "Original-Einrichtung beziehungsweise menschliche Entscheidung ausführen; danach Agentenschritt prüfen.";
    }

    private static String displayFaction(Player player) {
        String faction = player.getFaction();
        return faction == null || faction.isBlank() || "null".equals(faction) ? null : faction;
    }
}

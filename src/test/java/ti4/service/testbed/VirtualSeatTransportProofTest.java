package ti4.service.testbed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.requests.restaction.*;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import ti4.AsyncTI4DiscordBot;
import ti4.contest.replay.core.CombatContestSettings;
import ti4.discord.JdaService;
import ti4.discord.interactions.buttons.ButtonProcessor;
import ti4.discord.interactions.routing.InteractionProcessingResult;
import ti4.discord.utility.DiscordChannelUtility;
import ti4.executors.*;
import ti4.game.*;
import ti4.game.persistence.*;
import ti4.helpers.Helper;
import ti4.logging.BotLogger;
import ti4.message.MessageHelper;
import ti4.service.game.GameNameService;
import ti4.settings.GlobalSettings;
import ti4.testUtils.BaseTi4Test;

class VirtualSeatTransportProofTest extends BaseTi4Test {
    @Test
    void virtualSeatStagesCarrierAndInfantryThroughOriginalMovementHandlers() {
        try (Fixture f = new Fixture()) {
            f.game.setPhaseOfGame("action");
            f.game.setActivePlayerID(f.a.getUserID());
            f.game.setActiveSystem("201");
            Tile home = new Tile("01", "301");
            Tile target = new Tile("19", "201");
            f.game.setTile(home);
            f.game.setTile(target);
            f.a.getPlanets().add("jord");
            var carrier = ti4.helpers.Units.getUnitKey(ti4.helpers.Units.UnitType.Carrier, "blue");
            var infantry = ti4.helpers.Units.getUnitKey(ti4.helpers.Units.UnitType.Infantry, "blue");
            home.getSpaceUnitHolder().addUnit(carrier, 1);
            home.getUnitHolders().get("jord").addUnit(infantry, 2);
            Button moveCarrier = ti4.helpers.ButtonHelper.buildMoveUnitButton(
                    f.a, home, home.getSpaceUnitHolder(), ti4.helpers.Units.UnitState.none, carrier, 1, false, false);
            assertEquals("FFCC_sol_unitTacticalMove_301_1_cv_blue", moveCarrier.getCustomId());
            assertEquals(InteractionProcessingResult.COMPLETED, f.press(f.a, moveCarrier));
            assertEquals(0, home.getSpaceUnitHolder().getUnitCount(carrier));
            Button moveInfantry = ti4.helpers.ButtonHelper.buildMoveUnitButton(
                    f.a,
                    home,
                    home.getUnitHolders().get("jord"),
                    ti4.helpers.Units.UnitState.none,
                    infantry,
                    1,
                    false,
                    false);
            assertEquals("FFCC_sol_unitTacticalMove_301_1_gf_jord_blue", moveInfantry.getCustomId());
            assertEquals(InteractionProcessingResult.COMPLETED, f.press(f.a, moveInfantry));
            assertEquals(1, home.getUnitHolders().get("jord").getUnitCount(infantry));
            assertEquals(
                    1,
                    f.game.getTacticalActionDisplacement()
                            .get("301-space")
                            .get(carrier)
                            .getFirst());
            assertEquals(
                    1,
                    f.game.getTacticalActionDisplacement()
                            .get("301-jord")
                            .get(infantry)
                            .getFirst());
            assertTrue(f.human.getPlanets().isEmpty());
        }
    }

    @Test
    void managedAgentUsesOriginalStrategyHandlerWithoutPersistentHumanImpersonation() {
        try (Fixture f = new Fixture()) {
            List<Player> agents = new java.util.ArrayList<>(List.of(f.a, f.b));
            for (int index = 3; index <= 5; index++) {
                agents.add(f.game.addPlayer(TestBedService.VIRTUAL_SEAT_ID_PREFIX + "0" + index, "Agent " + index));
            }
            ManagedSeats.register(f.game, "123", agents);
            Game independentlyLoadedGame = new Game();
            independentlyLoadedGame.setName(f.game.getName());
            independentlyLoadedGame.addPlayer("123", "Human").setFaction("hacan");
            independentlyLoadedGame.addPlayer(f.a.getUserID(), "Virtual A").setFaction("sol");
            ManagedGame managed = mock(ManagedGame.class);
            when(managed.getGame()).thenReturn(independentlyLoadedGame);
            f.games.when(() -> GameManager.getManagedGame(f.game.getName())).thenReturn(managed);
            Button button = Helper.getRemainingSCButtons(f.game, f.a).stream()
                    .filter(candidate -> "FFCC_sol_scPick_1".equals(candidate.getCustomId()))
                    .findFirst()
                    .orElseThrow();
            var result = ManagedSeats.asAgent(
                    f.game,
                    f.developer,
                    f.a,
                    () -> WebPlayerButtonPress.press(f.game, f.developer, f.a, f.message, button));
            assertEquals(TestBedPress.Outcome.PRESSED, result.outcome());
            assertEquals(java.util.Set.of(1), f.a.getSCs());
            assertTrue(f.b.getSCs().isEmpty());
            assertTrue(f.human.getSCs().isEmpty());
            assertTrue(
                    independentlyLoadedGame.getPlayer(f.a.getUserID()).getSCs().isEmpty());
            f.games.verify(() -> GameManager.save(eq(f.game), anyString()), atLeastOnce());
            assertSame(f.human, ManagedSeats.resolve(f.game, "123", f.human));
            assertNull(TestBedService.getActingAs(f.game, "123"));
        }
    }

    @Test
    void originalStrategyCardPickChangesOnlyVirtualSeatAndRestoresPriorActingAs() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.a.isRealPlayer());
            assertTrue(f.b.isRealPlayer());
            assertFalse(f.a.isNpc());
            assertFalse(f.a.isDummy());
            Button offered = Helper.getRemainingSCButtons(f.game, f.a).stream()
                    .filter(button -> "FFCC_sol_scPick_1".equals(button.getCustomId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(InteractionProcessingResult.COMPLETED, f.press(f.a, offered));
            assertEquals(java.util.Set.of(1), f.a.getSCs());
            assertTrue(f.b.getSCs().isEmpty());
            assertTrue(f.human.getSCs().isEmpty());
            assertEquals(f.b, TestBedService.getActingAs(f.game, "123"));
            assertEquals("", f.game.getStoredValue(TestBedPress.FORCED_DICE_KEY));
        }
    }

    @Test
    void originalPrivateNotepadPostIsDeliveredOnlyToVirtualSeatCardsThread() {
        try (Fixture f = new Fixture()) {
            f.a.setNotes("Private A secret");
            f.b.setNotes("Private B secret");
            f.human.setNotes("Human secret");
            assertEquals(
                    InteractionProcessingResult.COMPLETED,
                    f.press(f.a, Button.primary("FFCC_sol_notepadPost", "Post notes")));
            ArgumentCaptor<MessageCreateData> sent = ArgumentCaptor.forClass(MessageCreateData.class);
            verify(f.cardsA).sendMessage(sent.capture());
            assertTrue(sent.getValue().getContent().contains("Private A secret"));
            assertFalse(sent.getValue().getContent().contains("Private B secret"));
            verify(f.cardsB, never()).sendMessage(any(MessageCreateData.class));
            verify(f.cardsHuman, never()).sendMessage(any(MessageCreateData.class));
            assertEquals(f.b, TestBedService.getActingAs(f.game, "123"));
        }
    }

    @Test
    void originalNotepadModalUpdatesOnlySelectedVirtualSeatAndRestoresPreviousSeat() {
        try (Fixture f = new Fixture()) {
            f.a.setNotes("Old A");
            f.b.setNotes("Old B");
            f.human.setNotes("Human note");
            assertEquals(
                    InteractionProcessingResult.COMPLETED,
                    f.press(f.a, Button.primary("FFCC_sol_notepadEdit~MDL", "Edit notes")));
            var modal = f.recorder.modalDefinition();
            assertNotNull(modal);
            assertEquals(InteractionProcessingResult.COMPLETED, f.submitNotes(f.a, modal, "New private A"));
            assertEquals("New private A", ti4.helpers.StringHelper.unescape(f.a.getNotes()));
            assertEquals("Old B", f.b.getNotes());
            assertEquals("Human note", f.human.getNotes());
            assertEquals(f.b, TestBedService.getActingAs(f.game, "123"));
            verify(f.cardsB, never()).sendMessage(any(MessageCreateData.class));
            verify(f.cardsHuman, never()).sendMessage(any(MessageCreateData.class));
        }
    }

    @Test
    void factionSelectionKeepsFollowUpButtonsOnTheResolvedVirtualSeat() {
        try (Fixture f = new Fixture()) {
            assertEquals(InteractionProcessingResult.COMPLETED, f.selectFaction(f.a, "naalu"));
            var sent = ArgumentCaptor.forClass(MessageCreateData.class);
            verify(f.channel, atLeastOnce()).sendMessage(sent.capture());
            var buttons = sent.getAllValues().stream()
                    .flatMap(data -> data.getComponentTree().findAll(Button.class).stream())
                    .toList();
            assertFalse(buttons.isEmpty());
            assertTrue(buttons.stream()
                    .allMatch(button -> button.getCustomId().startsWith("setupStep3_" + f.a.getUserID() + "_naalu_")));
            assertEquals(f.b, TestBedService.getActingAs(f.game, "123"));
            assertEquals("hacan", f.human.getFaction());
        }
    }

    @Test
    void regularFactionSelectionRetainsTheHumanPlayersIdentity() {
        try (Fixture f = new Fixture()) {
            TestBedService.markAsTestBed(f.game, false);
            assertEquals(InteractionProcessingResult.COMPLETED, f.selectFaction(f.human, "naalu"));
            var sent = ArgumentCaptor.forClass(MessageCreateData.class);
            verify(f.channel, atLeastOnce()).sendMessage(sent.capture());
            var buttons = sent.getAllValues().stream()
                    .flatMap(data -> data.getComponentTree().findAll(Button.class).stream())
                    .toList();
            assertFalse(buttons.isEmpty());
            assertTrue(buttons.stream().allMatch(button -> button.getCustomId().startsWith("setupStep3_123_naalu_")));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final List<Throwable> failures = new java.util.ArrayList<>();
        final MockedStatic<GameManager> games = mockStatic(GameManager.class);
        final MockedStatic<GameNameService> names = mockStatic(GameNameService.class);
        final MockedStatic<ti4.service.persistence.DatabasePersistenceGate> database =
                mockStatic(ti4.service.persistence.DatabasePersistenceGate.class);
        final MockedStatic<BotLogger> logs = mockStatic(BotLogger.class, call -> {
            for (Object argument : call.getArguments()) {
                if (argument instanceof Throwable failure) failures.add(failure);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        final MockedStatic<MessageHelper> messages = mockStatic(MessageHelper.class, CALLS_REAL_METHODS);
        final MockedStatic<CombatContestSettings> combat = mockStatic(CombatContestSettings.class);
        final MockedStatic<CircuitBreaker> breaker = mockStatic(CircuitBreaker.class);
        final MockedStatic<AsyncTI4DiscordBot> bot = mockStatic(AsyncTI4DiscordBot.class);
        final MockedStatic<GlobalSettings> settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS);
        final MockedStatic<DiscordChannelUtility> channels = mockStatic(DiscordChannelUtility.class);
        final Game game = new Game();
        final Player human;
        final Player a;
        final Player b;
        final Member developer = mock(Member.class);
        final Message message = mock(Message.class);
        final MessageChannelUnion channel = mock(MessageChannelUnion.class);
        final JDA previousJda = JdaService.jda;
        final List<Role> previousRoles = List.copyOf(JdaService.developerRoles);
        final JDA jda = mock(JDA.class);
        final Guild guild = mock(Guild.class);
        final TextChannel main = mock(TextChannel.class);
        final ThreadChannel cardsA = mock(ThreadChannel.class);
        final ThreadChannel cardsB = mock(ThreadChannel.class);
        final ThreadChannel cardsHuman = mock(ThreadChannel.class);
        TestBedPress.Recorder recorder;

        Fixture() {
            database.when(ti4.service.persistence.DatabasePersistenceGate::isDisabled)
                    .thenReturn(true);
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(true);
            JdaService.jda = jda;
            when(jda.getPresence()).thenReturn(mock(net.dv8tion.jda.api.managers.Presence.class));
            Role role = mock(Role.class);
            JdaService.developerRoles.clear();
            JdaService.developerRoles.add(role);
            when(developer.getRoles()).thenReturn(List.of(role));
            User user = mock(User.class);
            when(user.getId()).thenReturn("123");
            when(user.getName()).thenReturn("Real developer carrier");
            when(user.getEffectiveName()).thenReturn("Real developer carrier");
            when(developer.getId()).thenReturn("123");
            when(developer.getUser()).thenReturn(user);
            game.setName("virtualproof");
            game.setMainChannelID("456");
            game.setStrategyCardSet("pok");
            game.setPhaseOfGame("strategy");
            human = player("123", "Human", "hacan", "yellow", "1001", cardsHuman);
            a = player("90000000000000001", "Virtual A", "sol", "blue", "1002", cardsA);
            b = player("90000000000000002", "Virtual B", "arborec", "green", "1003", cardsB);
            game.setSpeakerUserID(a.getUserID());
            TestBedService.markAsTestBed(game, true);
            TestBedService.setActingAs(game, "123", b);
            ManagedGame managed = mock(ManagedGame.class);
            when(managed.getGame()).thenReturn(game);
            games.when(() -> GameManager.isValid("virtualproof")).thenReturn(true);
            games.when(() -> GameManager.getManagedGame("virtualproof")).thenReturn(managed);
            names.when(() -> GameNameService.getGameNameFromChannel(any(Interaction.class)))
                    .thenReturn("virtualproof");
            bot.when(AsyncTI4DiscordBot::isUnstable).thenReturn(true);
            when(jda.getTextChannelById("456")).thenReturn(main);
            when(main.getGuild()).thenReturn(guild);
            when(main.getId()).thenReturn("456");
            when(main.getAsMention()).thenReturn("<#456>");
            when(main.canTalk()).thenReturn(true);
            when(main.sendMessage(any(MessageCreateData.class)))
                    .thenReturn(mock(MessageCreateAction.class, RETURNS_SELF));
            when(channel.getName()).thenReturn("virtualproof-actions");
            when(channel.getId()).thenReturn("456");
            when(channel.getAsMention()).thenReturn("<#456>");
            when(channel.canTalk()).thenReturn(true);
            when(channel.sendMessage(any(MessageCreateData.class)))
                    .thenReturn(mock(MessageCreateAction.class, RETURNS_SELF));
            when(message.getChannel()).thenReturn(channel);
            when(message.getJDA()).thenReturn(jda);
            when(message.getGuild()).thenReturn(guild);
            when(message.getId()).thenReturn("789");
            when(message.getComponentTree()).thenReturn(MessageComponentTree.of());
            MessageEditAction edit = mock(MessageEditAction.class, RETURNS_SELF);
            when(edit.submit()).thenReturn(CompletableFuture.completedFuture(message));
            when(message.editMessage(any(CharSequence.class))).thenReturn(edit);
            when(message.reply(any(CharSequence.class))).thenReturn(mock(MessageCreateAction.class, RETURNS_SELF));
            AuditableRestAction<Void> deletion = mock(AuditableRestAction.class);
            when(deletion.submit()).thenReturn(CompletableFuture.completedFuture(null));
            when(message.delete()).thenReturn(deletion);
        }

        Player player(String id, String name, String faction, String color, String threadId, ThreadChannel cards) {
            Player player = game.addPlayer(id, name);
            player.setFaction(faction);
            player.setColor(color);
            player.setCardsInfoThreadID(threadId);
            when(cards.getId()).thenReturn(threadId);
            when(cards.getAsMention()).thenReturn("<#" + threadId + ">");
            when(cards.canTalk()).thenReturn(true);
            MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
            Message delivered = mock(Message.class);
            when(delivered.getId()).thenReturn(threadId + "1");
            when(delivered.getJumpUrl())
                    .thenReturn("https://discord.com/channels/1/" + threadId + "/" + threadId + "1");
            when(send.complete()).thenReturn(delivered);
            when(cards.sendMessage(any(MessageCreateData.class))).thenReturn(send);
            CacheRestAction<ThreadChannel> retrieve = mock(CacheRestAction.class);
            when(retrieve.complete()).thenReturn(cards);
            channels.when(() -> DiscordChannelUtility.retrieveThreadChannelById(guild, Long.parseLong(threadId)))
                    .thenReturn(retrieve);
            return player;
        }

        InteractionProcessingResult press(Player seat, Button button) {
            recorder = new TestBedPress.Recorder();
            TestBedSyncMessage synced = new TestBedSyncMessage(message);
            return asSeat(seat, () -> {
                var event = TestBedPress.humanEvent(
                        synced.proxy(),
                        button,
                        developer,
                        recorder,
                        new TestBedPress.Repost(seat.getCardsInfoThread(), seat.getFaction(), false));
                InteractionProcessingResult result = ButtonProcessor.processNow(event);
                assertTrue(synced.awaitPendingEdits(Duration.ofSeconds(2)));
                assertFalse(
                        synced.handlerFailed(),
                        () -> failures.stream()
                                .map(org.apache.commons.lang3.exception.ExceptionUtils::getStackTrace)
                                .collect(java.util.stream.Collectors.joining("\n")));
                assertFalse(
                        recorder.transportFailed(),
                        () -> recorder.unsupportedCalls().toString());
                return result;
            });
        }

        InteractionProcessingResult submitNotes(Player seat, net.dv8tion.jda.api.modals.Modal modal, String text) {
            recorder = new TestBedPress.Recorder();
            return asSeat(seat, () -> {
                var event = WebPlayerButtonPress.modalEvent(
                        message,
                        developer,
                        modal,
                        java.util.Map.of("notes", text),
                        recorder,
                        new TestBedPress.Repost(seat.getCardsInfoThread(), seat.getFaction(), false));
                var result = ti4.discord.interactions.listeners.ModalListener.getInstance()
                        .processNow(event);
                assertFalse(
                        recorder.transportFailed(),
                        () -> recorder.unsupportedCalls().toString());
                return result;
            });
        }

        InteractionProcessingResult selectFaction(Player seat, String faction) {
            recorder = new TestBedPress.Recorder();
            return asSeat(seat, () -> {
                var menu = net.dv8tion.jda.api.components.selections.StringSelectMenu.create("selectFaction")
                        .addOption("Naalu", faction)
                        .build();
                var event = WebPlayerButtonPress.selectEvent(
                        message,
                        developer,
                        menu,
                        List.of(faction),
                        recorder,
                        new TestBedPress.Repost(seat.getCardsInfoThread(), seat.getFaction(), false));
                return ti4.discord.interactions.selections.SelectionMenuProcessor.processNow(event);
            });
        }

        <T> T asSeat(Player seat, Supplier<T> action) {
            ExecutionLockManager.lock(game.getName(), ExecutionLockType.WRITE);
            String previous = TestBedService.rawActingAs(game, developer.getId());
            try {
                TestBedService.setActingAs(game, developer.getId(), seat);
                return action.get();
            } finally {
                TestBedService.restoreActingAs(game, developer.getId(), previous);
                ExecutionLockManager.unlock(game.getName(), ExecutionLockType.WRITE);
            }
        }

        public void close() {
            JdaService.jda = previousJda;
            JdaService.developerRoles.clear();
            JdaService.developerRoles.addAll(previousRoles);
            channels.close();
            settings.close();
            bot.close();
            breaker.close();
            combat.close();
            messages.close();
            logs.close();
            database.close();
            names.close();
            games.close();
        }
    }
}

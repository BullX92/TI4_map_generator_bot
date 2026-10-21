package ti4.spring.api.wrapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ti4.discord.JdaService;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.persistence.GameManager;
import ti4.game.persistence.ManagedGame;
import ti4.service.testbed.ManagedSeats;
import ti4.service.testbed.TestBedPress;
import ti4.service.testbed.TestBedService;
import ti4.service.testbed.WebPlayerButtonPress;
import ti4.settings.GlobalSettings;
import ti4.spring.service.deploy.ActiveLeaseService;
import ti4.spring.service.wrapper.WrapperMessageIndex;

class WrapperAgentServiceTest {
    @Test
    void failedFinalSaveKeepsUncertainBarrierAfterTheOriginalActionChangedState() {
        JDA previousJda = JdaService.jda;
        List<Role> previousRoles = new ArrayList<>(JdaService.developerRoles);
        try (var games = mockStatic(GameManager.class);
                var settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS);
                var transport = mockStatic(WebPlayerButtonPress.class)) {
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(true);
            Game game = spy(registered());
            game.setPhaseOfGame("playerSetup");
            game.setRound(1);
            Player agent = spy(ManagedSeats.agents(game).getFirst());
            game.getPlayers().put(agent.getUserID(), agent);
            doReturn(null).when(agent).getCardsInfoThread();
            agent.getSecrets().put("first", 1);
            agent.getSecrets().put("second", 2);
            Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
            doReturn(guild).when(game).getGuild();
            TextChannel channel = mock(TextChannel.class);
            doReturn(channel).when(game).getMainGameChannel();
            doReturn(channel).when(game).getActionsChannel();
            Member actor = mock(Member.class);
            Role developer = mock(Role.class);
            when(actor.getId()).thenReturn("123");
            when(actor.getRoles()).thenReturn(List.of(developer));
            JdaService.developerRoles.add(developer);
            when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(actor);
            JDA jda = mock(JDA.class, RETURNS_DEEP_STUBS);
            JdaService.jda = jda;
            when(jda.getSelfUser().getId()).thenReturn("999");
            Message message = mock(Message.class, RETURNS_DEEP_STUBS);
            when(message.getAuthor().getId()).thenReturn("999");
            when(message.getId()).thenReturn("111");
            Button button = Button.primary("discardSecret_1", "Discard");
            MessageComponentTree tree = mock(MessageComponentTree.class);
            when(tree.findAll(Button.class)).thenReturn(List.of(button));
            when(message.getComponentTree()).thenReturn(tree);
            WrapperMessageIndex messages = mock(WrapperMessageIndex.class);
            when(messages.offeredMessages("agentproof", channel)).thenReturn(List.of(message));
            ActiveLeaseService lease = mock(ActiveLeaseService.class);
            when(lease.mayMutate()).thenReturn(true);
            when(lease.isReady()).thenReturn(true);
            ManagedGame managed = mock(ManagedGame.class);
            when(managed.getGame()).thenReturn(game);
            games.when(() -> GameManager.getManagedGame("agentproof")).thenReturn(managed);
            AtomicInteger saves = new AtomicInteger();
            games.when(() -> GameManager.save(eq(game), anyString())).thenAnswer(invocation -> {
                if (saves.incrementAndGet() == 2) throw new IllegalStateException("disk unavailable");
                return null;
            });
            transport
                    .when(() -> WebPlayerButtonPress.press(game, actor, agent, message, button))
                    .thenAnswer(invocation -> {
                        agent.getSecrets().remove("first");
                        return new TestBedPress.PressResult(
                                TestBedPress.Outcome.PRESSED, "ok", new TestBedPress.Recorder());
                    });
            var service = new WrapperAgentService(lease, messages);
            assertEquals(
                    "UNCERTAIN",
                    service.step("agentproof", "123", "save-failure").status());
            assertEquals(1, agent.getSo());
            assertEquals("UNCERTAIN", service.observe("agentproof", "123").status());
            assertEquals("UNCERTAIN", service.step("agentproof", "123", "next").status());
            assertEquals("save-failure", game.getStoredValue("wrapperAgentActiveRequest"));
            transport.verify(() -> WebPlayerButtonPress.press(game, actor, agent, message, button), times(1));
        } finally {
            JdaService.jda = previousJda;
            JdaService.developerRoles.clear();
            JdaService.developerRoles.addAll(previousRoles);
        }
    }

    @Test
    void crashIntentIsVisibleAndNoNewOrRepeatedRequestReplaysIt() {
        try (var games = mockStatic(GameManager.class);
                var settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS)) {
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(true);
            Game game = registered();
            ManagedGame managed = mock(ManagedGame.class);
            when(managed.getGame()).thenReturn(game);
            games.when(() -> GameManager.getManagedGame("agentproof")).thenReturn(managed);
            ActiveLeaseService lease = mock(ActiveLeaseService.class);
            when(lease.mayMutate()).thenReturn(true);
            when(lease.isReady()).thenReturn(true);
            WrapperMessageIndex messages = mock(WrapperMessageIndex.class);
            var service = new WrapperAgentService(lease, messages);
            game.setStoredValue("wrapperAgentActiveRequest", "old");
            game.setStoredValue("wrapperAgentRequest_old", "STARTED");
            assertEquals("UNCERTAIN", service.observe("agentproof", "123").status());
            assertEquals("UNCERTAIN", service.step("agentproof", "123", "new").status());
            assertEquals("STARTED", service.step("agentproof", "123", "old").status());
            verifyNoInteractions(messages);
            games.verify(() -> GameManager.save(any(Game.class), anyString()), never());
            assertEquals(6, service.observe("agentproof", "123").seats().size());
        }
    }

    @Test
    void foreignOwnerAndDisabledTransportCannotReadOrRunAgents() {
        try (var games = mockStatic(GameManager.class);
                var settings = mockStatic(GlobalSettings.class, CALLS_REAL_METHODS)) {
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(true);
            Game game = registered();
            ManagedGame managed = mock(ManagedGame.class);
            when(managed.getGame()).thenReturn(game);
            games.when(() -> GameManager.getManagedGame("agentproof")).thenReturn(managed);
            var service = new WrapperAgentService(mock(ActiveLeaseService.class), mock(WrapperMessageIndex.class));
            assertEquals(
                    403,
                    assertThrows(ResponseStatusException.class, () -> service.observe("agentproof", "999"))
                            .getStatusCode()
                            .value());
            settings.when(() -> GlobalSettings.getSetting("testbed_enabled", Object.class, false))
                    .thenReturn(false);
            assertEquals(
                    409,
                    assertThrows(ResponseStatusException.class, () -> service.observe("agentproof", "123"))
                            .getStatusCode()
                            .value());
        }
    }

    private Game registered() {
        Game game = new Game();
        game.setName("agentproof");
        game.addPlayer("123", "Human");
        List<Player> agents = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            agents.add(game.addPlayer(TestBedService.VIRTUAL_SEAT_ID_PREFIX + "0" + index, "Agent " + index));
        }
        ManagedSeats.register(game, "123", agents);
        return game;
    }
}

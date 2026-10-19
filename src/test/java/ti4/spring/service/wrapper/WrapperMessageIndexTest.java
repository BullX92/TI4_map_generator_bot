package ti4.spring.service.wrapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;
import ti4.discord.JdaService;
import ti4.game.persistence.GameManager;
import ti4.service.game.GameNameService;

class WrapperMessageIndexTest {
    @Test
    void startupMessagesDoNotWaitForGameNamesOrAccessStorage() {
        JDA previous = JdaService.jda;
        JDA jda = mock(JDA.class);
        SelfUser bot = mock(SelfUser.class);
        when(bot.getId()).thenReturn("bot");
        when(jda.getSelfUser()).thenReturn(bot);
        JdaService.jda = jda;
        Message message = mock(Message.class);
        var channel = mock(MessageChannelUnion.class);
        when(message.getAuthor()).thenReturn(bot);
        when(message.getChannel()).thenReturn(channel);
        var repository = mock(WrapperMessageRepository.class);
        var cursors = mock(WrapperChannelRepository.class);
        try (var gameManager = mockStatic(GameManager.class);
                var names = mockStatic(GameNameService.class)) {
            names.when(() -> GameNameService.getGameNameFromChannel(channel))
                    .thenThrow(new AssertionError("Startup must not resolve game names before they are loaded"));

            new WrapperMessageIndex(repository, cursors).capture(message);

            names.verifyNoInteractions();
            verifyNoInteractions(repository, cursors);
        } finally {
            JdaService.jda = previous;
        }
    }

    @Test
    void oldOffersAndOfflineEditsRemainDiscoverableAcrossHistoryPagesAndRestart() {
        JDA previous = JdaService.jda;
        JDA jda = mock(JDA.class);
        SelfUser bot = mock(SelfUser.class);
        when(bot.getId()).thenReturn("bot");
        when(jda.getSelfUser()).thenReturn(bot);
        JdaService.jda = jda;
        var channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn("channel");
        when(channel.getType()).thenReturn(ChannelType.TEXT);
        var history = mock(MessageHistory.class);
        when(channel.getHistory()).thenReturn(history);
        RestAction<List<Message>> request = mock(RestAction.class);
        when(history.retrievePast(100)).thenReturn(request);
        List<Message> all = new ArrayList<>();
        for (long id = 220; id > 0; id--) {
            Message message = mock(Message.class);
            when(message.getId()).thenReturn(Long.toString(id));
            when(message.getIdLong()).thenReturn(id);
            when(message.getAuthor()).thenReturn(bot);
            when(message.getChannel()).thenReturn(channel);
            when(message.getComponentTree()).thenReturn(MessageComponentTree.of());
            all.add(message);
        }
        Message oldest = all.getLast();
        Message offlineEdited = all.get(218);
        var actionable = MessageComponentTree.of(ActionRow.of(Button.primary("turn", "Turn")));
        when(oldest.getComponentTree()).thenReturn(actionable);
        var repository = mock(WrapperMessageRepository.class);
        var cursors = mock(WrapperChannelRepository.class);
        Map<String, WrapperMessageEntity> rows = new HashMap<>();
        Map<String, WrapperChannelEntity> positions = new HashMap<>();
        when(repository.save(any())).thenAnswer(call -> {
            WrapperMessageEntity row = call.getArgument(0);
            rows.put(row.getMessageId(), row);
            return row;
        });
        doAnswer(call -> {
                    rows.remove(call.getArgument(0));
                    return null;
                })
                .when(repository)
                .deleteById(anyString());
        when(repository.findByGameNameAndChannelIdOrderByMessageIdDesc("game", "channel"))
                .thenAnswer(ignored -> new ArrayList<>(rows.values()));
        when(cursors.findById(anyString())).thenAnswer(call -> Optional.ofNullable(positions.get(call.getArgument(0))));
        when(cursors.save(any())).thenAnswer(call -> {
            WrapperChannelEntity row = call.getArgument(0);
            positions.put(row.getChannelId(), row);
            return row;
        });
        try (var gameManager = mockStatic(GameManager.class);
                var names = mockStatic(GameNameService.class)) {
            names.when(() -> GameNameService.getGameNameFromChannel(channel)).thenReturn("game");
            when(request.complete()).thenReturn(all.subList(0, 100), all.subList(100, 200), all.subList(200, 220));
            var index = new WrapperMessageIndex(repository, cursors);
            index.capture(oldest);
            assertThat(rows).isEmpty();

            gameManager.when(GameManager::isGameNamesLoaded).thenReturn(true);
            assertThat(index.offeredMessages("game", channel)).containsExactly(oldest);

            // The old message gains its first button while gateway events are unavailable.
            when(offlineEdited.getComponentTree()).thenReturn(actionable);
            when(oldest.getComponentTree()).thenReturn(MessageComponentTree.of());
            index.reconcileAfterReconnect();
            when(request.complete()).thenReturn(all.subList(0, 100), all.subList(100, 200), all.subList(200, 220));
            assertThat(index.offeredMessages("game", channel)).containsExactly(offlineEdited);

            when(request.complete()).thenReturn(all.subList(0, 100), all.subList(100, 200), all.subList(200, 220));
            assertThat(new WrapperMessageIndex(repository, cursors).offeredMessages("game", channel))
                    .containsExactly(offlineEdited);
        } finally {
            JdaService.jda = previous;
        }
    }
}

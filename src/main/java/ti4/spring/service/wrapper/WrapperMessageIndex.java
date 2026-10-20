package ti4.spring.service.wrapper;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.components.ActionComponent;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.springframework.stereotype.Service;
import ti4.discord.JdaService;
import ti4.discord.utility.DiscordErrorUtility;
import ti4.game.persistence.GameManager;
import ti4.service.game.GameNameService;
import ti4.spring.context.SpringContext;

@Service
public class WrapperMessageIndex {
    private final WrapperMessageRepository repository;
    private final WrapperChannelRepository channels;
    private final Cache<String, Message> messages = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();
    private final Cache<String, Boolean> seededChannels = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private final Cache<String, Boolean> reconciledChannels = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public WrapperMessageIndex(WrapperMessageRepository repository, WrapperChannelRepository channels) {
        this.repository = repository;
        this.channels = channels;
    }

    public void capture(Message message) {
        if (!GameManager.isGameNamesLoaded()
                || JdaService.jda == null
                || message.isEphemeral()
                || !message.getAuthor()
                        .getId()
                        .equals(JdaService.jda.getSelfUser().getId())) return;
        String gameName = GameNameService.getGameNameFromChannel(message.getChannel());
        if (gameName == null) return;
        if (!isVisibleMessage(message)) {
            remove(message.getId());
            return;
        }
        repository.save(new WrapperMessageEntity(
                message.getId(),
                message.getChannel().getId(),
                gameName,
                message.getChannel().getType().isThread()));
        messages.put(message.getId(), message);
    }

    public void remember(String gameName, String channelId, String messageId) {
        repository.save(new WrapperMessageEntity(messageId, channelId, gameName, false));
    }

    public List<String> messageIds(String gameName, String channelId) {
        return repository.findByGameNameAndChannelIdOrderByMessageIdDesc(gameName, channelId).stream()
                .map(WrapperMessageEntity::getMessageId)
                .toList();
    }

    public List<String> channelIds(String gameName) {
        return repository.findChannelIds(gameName);
    }

    public List<Message> offeredMessages(String gameName, MessageChannel channel) {
        seededChannels.get(channel.getId(), ignored -> {
            catchUp(channel);
            return true;
        });
        List<Message> offered = new ArrayList<>();
        for (String id : messageIds(gameName, channel.getId())) {
            Message message = messages.getIfPresent(id);
            if (message == null) {
                try {
                    message = channel.retrieveMessageById(id).complete();
                    capture(message);
                } catch (RuntimeException e) {
                    if (!DiscordErrorUtility.isUnknownMessageError(e)) throw e;
                    remove(id);
                    continue;
                }
            }
            if (isVisibleMessage(message)) offered.add(message);
        }
        return offered;
    }

    private static boolean isVisibleMessage(Message message) {
        return !message.getComponentTree().findAll(ActionComponent.class).isEmpty()
                || WrapperDisplayContent.isMiltyInformation(message);
    }

    private void catchUp(MessageChannel channel) {
        boolean fullScan = reconciledChannels.getIfPresent(channel.getId()) == null;
        long cursor = fullScan
                ? 0L
                : channels.findById(channel.getId())
                        .map(WrapperChannelEntity::getLastMessageId)
                        .orElse(0L);
        long newest = cursor;
        var history = channel.getHistory();
        boolean finished = false;
        while (!finished) {
            List<Message> page = history.retrievePast(100).complete();
            if (page.isEmpty()) break;
            for (Message message : page) {
                newest = Math.max(newest, message.getIdLong());
                if (message.getIdLong() <= cursor) {
                    finished = true;
                    break;
                }
                capture(message);
            }
            if (page.size() < 100) break;
        }
        channels.save(new WrapperChannelEntity(channel.getId(), newest));
        if (fullScan) reconciledChannels.put(channel.getId(), true);
    }

    public void reconcileAfterReconnect() {
        seededChannels.invalidateAll();
        reconciledChannels.invalidateAll();
        messages.invalidateAll();
    }

    public void remove(String messageId) {
        messages.invalidate(messageId);
        repository.deleteById(messageId);
    }

    public static WrapperMessageIndex getBean() {
        return SpringContext.getBean(WrapperMessageIndex.class);
    }
}

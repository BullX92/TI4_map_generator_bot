package ti4.discord.interactions.listeners;

import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ti4.discord.JdaService;
import ti4.logging.BotLogger;
import ti4.spring.context.SpringContext;
import ti4.spring.service.wrapper.WrapperMessageIndex;

@Component
public class WrapperMessageListener extends ListenerAdapter {
    private final WrapperMessageIndex index;
    private final boolean enabled;

    public WrapperMessageListener(WrapperMessageIndex index, @Value("${ti4.wrapper.enabled:false}") boolean enabled) {
        this.index = index;
        this.enabled = enabled;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!enabled
                || !event.isFromGuild()
                || !JdaService.isValidGuild(event.getGuild().getId())) return;
        safely(() -> index.capture(event.getMessage()));
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!enabled
                || !event.isFromGuild()
                || !JdaService.isValidGuild(event.getGuild().getId())) return;
        safely(() -> index.capture(event.getMessage()));
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (!enabled
                || !event.isFromGuild()
                || !JdaService.isValidGuild(event.getGuild().getId())) return;
        safely(() -> index.remove(event.getMessageId()));
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        if (!enabled || !JdaService.isValidGuild(event.getGuild().getId())) return;
        safely(() -> event.getMessageIds().forEach(index::remove));
    }

    private static void safely(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            BotLogger.error("Failed to update the wrapper action message index.", e);
        }
    }

    @Override
    public void onSessionResume(SessionResumeEvent event) {
        if (enabled) index.reconcileAfterReconnect();
    }

    @Override
    public void onSessionRecreate(SessionRecreateEvent event) {
        if (enabled) index.reconcileAfterReconnect();
    }

    public static WrapperMessageListener getBean() {
        return SpringContext.getBean(WrapperMessageListener.class);
    }
}

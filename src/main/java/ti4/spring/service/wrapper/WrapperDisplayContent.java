package ti4.spring.service.wrapper;

import java.net.URI;
import java.util.List;
import java.util.Set;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;

public final class WrapperDisplayContent {
    public record Image(String name, String url, String description) {}

    private WrapperDisplayContent() {}

    public static String buttonLabel(Button button) {
        String label = button.getLabel();
        if (label != null && !label.isBlank()) return label;
        return button.getEmoji() == null ? "Aktion" : button.getEmoji().getName();
    }

    public static boolean isMiltyInformation(Message message) {
        String text = message.getContentRaw();
        return text != null && text.startsWith("# **__Draft Picks So Far__**:")
                || !images(message).isEmpty();
    }

    public static List<Image> images(Message message) {
        return message.getAttachments().stream()
                .filter(attachment -> attachment.isImage()
                        && attachment.getFileName().contains("_miltydraft")
                        && (attachment.getFileName().endsWith(".webp")
                                || attachment.getFileName().endsWith(".png"))
                        && isDiscordAttachment(attachment.getUrl()))
                .map(attachment ->
                        new Image(attachment.getFileName(), attachment.getUrl(), attachment.getDescription()))
                .toList();
    }

    private static boolean isDiscordAttachment(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && Set.of("cdn.discordapp.com", "media.discordapp.net").contains(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getPath().startsWith("/attachments/");
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}

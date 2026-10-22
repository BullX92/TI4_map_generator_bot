package ti4.spring.service.wrapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.junit.jupiter.api.Test;

class WrapperDisplayContentTest {
    @Test
    void onlyOriginalMiltyImagesFromDiscordAttachmentHostsAreExposed() {
        Message message = mock(Message.class);
        Message.Attachment attachment = mock(Message.Attachment.class);
        when(message.getAttachments()).thenReturn(List.of(attachment));
        when(attachment.isImage()).thenReturn(true);
        when(attachment.getFileName()).thenReturn("pbd1_miltydraft_20261020.webp");
        when(attachment.getUrl()).thenReturn("https://cdn.discordapp.com/attachments/1/2/draft.webp?ex=example");
        when(attachment.getDescription()).thenReturn("Slice A: 19,20,21,22,23");
        assertThat(WrapperDisplayContent.images(message))
                .singleElement()
                .extracting(WrapperDisplayContent.Image::description)
                .isEqualTo("Slice A: 19,20,21,22,23");
        for (String url : List.of(
                "https:cdn.discordapp.com/attachments/1/2/draft.webp",
                "http://cdn.discordapp.com/attachments/1/2/draft.webp",
                "https://evil.example/attachments/draft.webp",
                "https://cdn.discordapp.com.evil.example/attachments/draft.webp",
                "https://cdn.discordapp.com@evil.example/attachments/draft.webp",
                "https://cdn.discordapp.com/other/draft.webp",
                "data:image/png;base64,abcd")) {
            when(attachment.getUrl()).thenReturn(url);
            assertThat(WrapperDisplayContent.images(message)).as(url).isEmpty();
        }
        when(attachment.getUrl()).thenReturn("https://cdn.discordapp.com/attachments/1/2/draft.webp");
        when(attachment.getFileName()).thenReturn("private-card.webp");
        assertThat(WrapperDisplayContent.images(message)).isEmpty();
        when(attachment.getFileName()).thenReturn("pbd1_miltydraft.svg");
        assertThat(WrapperDisplayContent.images(message)).isEmpty();
    }

    @Test
    void emojiOnlyOriginalPicksKeepDistinctAccessibleNames() {
        Button slice = Button.primary("pick", "A")
                .withEmoji(Emoji.fromCustom("sliceA", 123, false))
                .withLabel("");
        Button position = Button.primary("order", "1")
                .withEmoji(Emoji.fromCustom("position1", 124, false))
                .withLabel(" ");
        assertThat(WrapperDisplayContent.buttonLabel(slice)).isEqualTo("sliceA");
        assertThat(WrapperDisplayContent.buttonLabel(position)).isEqualTo("position1");
        assertThat(WrapperDisplayContent.buttonLabel(Button.primary("plain", "Choose")))
                .isEqualTo("Choose");
    }
}

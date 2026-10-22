package ti4.service.emoji;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ti4.discord.JdaService;
import ti4.helpers.Storage;
import ti4.logging.BotLogger;
import ti4.service.emoji.ApplicationEmojiCacheService.CachedEmoji;

class ApplicationEmojiStartupTest {
    @TempDir
    Path directory;

    @Test
    void unavailableDiscordInventoryDoesNotGuessMissingEmojisFromAnOldCache() throws Exception {
        JDA previousJda = JdaService.jda;
        Map<Field, Object> previousState = saveState();
        JDA jda = mock(JDA.class, RETURNS_DEEP_STUBS);
        CachedEmoji cached = new CachedEmoji(emoji("cached", "101"));
        try (var cache = mockStatic(ApplicationEmojiCacheService.class)) {
            cache.when(ApplicationEmojiCacheService::readCachedEmojis).thenReturn(List.of(cached));
            when(jda.retrieveApplicationEmojis().complete())
                    .thenThrow(new IllegalStateException("Discord unavailable"));
            JdaService.jda = jda;
            clearState();

            assertThrows(IllegalStateException.class, ApplicationEmojiService::uploadNewEmojis);

            verify(jda, never()).createApplicationEmoji(anyString(), any(Icon.class));
            cache.verify(() -> ApplicationEmojiCacheService.saveCachedEmojis(anyList()), never());
        } finally {
            JdaService.jda = previousJda;
            restoreState(previousState);
        }
    }

    @Test
    void restartReconcilesInterruptedUploadsWithDiscordBeforeCreatingMissingEmojis() throws Exception {
        JDA previousJda = JdaService.jda;
        Map<Field, Object> previousState = saveState();
        JDA jda = mock(JDA.class, RETURNS_DEEP_STUBS);
        ApplicationEmoji cached = emoji("cached", "101");
        ApplicationEmoji uploadedBeforeInterruption = emoji("interrupted", "102");
        ApplicationEmoji missing = emoji("missing", "103");
        AtomicReference<List<CachedEmoji>> persisted = new AtomicReference<>(List.of(new CachedEmoji(cached)));
        for (String name : List.of("cached", "interrupted", "missing")) {
            Files.write(directory.resolve(name + ".png"), new byte[] {1, 2, 3});
        }
        try (var storage = mockStatic(Storage.class);
                var cache = mockStatic(ApplicationEmojiCacheService.class);
                var logger = mockStatic(BotLogger.class)) {
            storage.when(Storage::getAppEmojiDirectory).thenReturn(directory.toFile());
            cache.when(ApplicationEmojiCacheService::readCachedEmojis).thenAnswer(ignored -> persisted.get());
            cache.when(() -> ApplicationEmojiCacheService.saveCachedEmojis(anyList()))
                    .thenAnswer(call -> {
                        persisted.set(List.copyOf(call.getArgument(0)));
                        return null;
                    });
            when(jda.retrieveApplicationEmojis().complete()).thenReturn(List.of(cached, uploadedBeforeInterruption));
            when(jda.createApplicationEmoji(anyString(), any(Icon.class))).thenAnswer(call -> {
                RestAction<ApplicationEmoji> create = mock(RestAction.class);
                when(create.complete())
                        .thenReturn("missing".equals(call.getArgument(0)) ? missing : uploadedBeforeInterruption);
                return create;
            });
            JdaService.jda = jda;
            clearState();

            ApplicationEmojiService.uploadNewEmojis();

            verify(jda).createApplicationEmoji(eq("missing"), any(Icon.class));
            verify(jda, times(1)).createApplicationEmoji(anyString(), any(Icon.class));
            assertEquals(
                    List.of("101", "102", "103"),
                    persisted.get().stream().map(CachedEmoji::getId).sorted().toList());
            assertEquals(
                    "102",
                    ApplicationEmojiService.getApplicationEmoji("interrupted").getId());

            clearInvocations(jda);
            when(jda.retrieveApplicationEmojis().complete())
                    .thenReturn(List.of(cached, uploadedBeforeInterruption, missing));
            clearState();
            ApplicationEmojiService.uploadNewEmojis();
            verify(jda, never()).createApplicationEmoji(anyString(), any(Icon.class));
            assertEquals(3, persisted.get().size());
        } finally {
            JdaService.jda = previousJda;
            restoreState(previousState);
        }
    }

    private static ApplicationEmoji emoji(String name, String id) {
        ApplicationEmoji emoji = mock(ApplicationEmoji.class);
        when(emoji.getName()).thenReturn(name);
        when(emoji.getId()).thenReturn(id);
        when(emoji.getFormatted()).thenReturn("<:" + name + ":" + id + ">");
        when(emoji.getTimeCreated()).thenReturn(OffsetDateTime.now());
        return emoji;
    }

    private static Map<Field, Object> saveState() throws Exception {
        Map<Field, Object> state = new HashMap<>();
        for (String name : List.of("emojis", "emojiFiles", "spoofing", "cacheInitialized", "filesInitialized")) {
            Field field = ApplicationEmojiService.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            state.put(field, value instanceof Map<?, ?> map ? new HashMap<>(map) : value);
        }
        return state;
    }

    private static void clearState() throws Exception {
        for (Field field : saveState().keySet()) {
            Object value = field.get(null);
            if (value instanceof Map<?, ?> map) map.clear();
            else field.set(null, false);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreState(Map<Field, Object> state) throws Exception {
        for (var entry : state.entrySet()) {
            if (entry.getValue() instanceof Map map) {
                Map original = (Map) entry.getKey().get(null);
                original.clear();
                original.putAll(map);
            } else entry.getKey().set(null, entry.getValue());
        }
    }
}

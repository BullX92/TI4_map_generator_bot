package ti4.spring.api.wrapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ti4.discord.JdaService;
import ti4.game.Game;
import ti4.game.persistence.GameManager;
import ti4.helpers.ButtonHelper;
import ti4.helpers.Storage;
import ti4.helpers.ThreadArchiveHelper;
import ti4.message.MessageHelper;
import ti4.service.game.StartPhaseService;
import ti4.service.persistence.GameDatabaseSyncPipeline;
import ti4.spring.service.deploy.ActiveLeaseService;
import ti4.spring.service.wrapper.WrapperCreationOperation;
import ti4.spring.service.wrapper.WrapperCreationOperations;
import ti4.testUtils.BaseTi4Test;

class WrapperGameProvisioningTest extends BaseTi4Test {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "success",
                "role",
                "actions",
                "cards",
                "role-assignment",
                "lease",
                "native",
                "full-category",
                "unsafe-category",
                "bot-permissions",
                "reserved",
                "new-category"
            })
    void browserCreationUsesOriginalGameDefaultsPrivateThreadsAndMiltySetupAndFinalSave(String failurePoint)
            throws Exception {
        Files.createDirectories(directory.resolve("maps"));
        JDA previousJda = JdaService.jda;
        Guild previousGuild = JdaService.guildPrimary;
        JDA jda = mock(JDA.class, RETURNS_DEEP_STUBS);
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member owner = mock(Member.class, RETURNS_DEEP_STUBS);
        net.dv8tion.jda.api.entities.SelfMember bot =
                mock(net.dv8tion.jda.api.entities.SelfMember.class, RETURNS_DEEP_STUBS);
        Role everyone = mock(Role.class);
        Role role = mock(Role.class);
        Category category = mock(Category.class, RETURNS_DEEP_STUBS);
        TextChannel reply = mock(TextChannel.class, RETURNS_DEEP_STUBS);
        TextChannel chat = mock(TextChannel.class, RETURNS_DEEP_STUBS);
        TextChannel actions = mock(TextChannel.class, RETURNS_DEEP_STUBS);
        ThreadChannel mapThread = mock(ThreadChannel.class, RETURNS_DEEP_STUBS);
        ThreadChannel cards = mock(ThreadChannel.class, RETURNS_DEEP_STUBS);
        int originalNumber = GameManager.getLatestPbdNumber();
        String name = "pbd" + (originalNumber + ("reserved".equals(failurePoint) ? 2 : 1));
        String categoryName = ti4.service.game.CreateGameService.getCategoryNameForGame(name);
        when(owner.getId()).thenReturn("123");
        when(owner.getEffectiveName()).thenReturn("Owner");
        when(owner.getGuild()).thenReturn(guild);
        when(owner.isOwner()).thenReturn(true);
        when(owner.hasPermission(
                        any(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class),
                        any(Permission[].class)))
                .thenReturn(true);
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(owner);
        when(guild.getSelfMember()).thenReturn(bot);
        when(guild.getMemberById("123")).thenReturn(owner);
        when(jda.getUserById(anyString())).thenReturn(null);
        when(bot.hasPermission(any(Permission[].class))).thenReturn(true);
        when(bot.hasPermission(
                        any(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class),
                        any(Permission[].class)))
                .thenReturn(true);
        when(bot.canInteract(any(Role.class))).thenReturn(true);
        when(guild.getPublicRole()).thenReturn(everyone);
        when(everyone.getName()).thenReturn("@everyone");
        when(guild.getMembers()).thenReturn(List.of(owner, bot));
        var roleCreated = new java.util.concurrent.atomic.AtomicBoolean();
        when(guild.getRoles()).thenAnswer(call -> roleCreated.get() ? List.of(everyone, role) : List.of(everyone));
        when(guild.getRolesByName(name, true)).thenReturn(List.of(role));
        when(role.getName()).thenReturn(name);
        when(role.getIdLong()).thenReturn(77L);
        when(guild.getId()).thenReturn("999");
        when(guild.getJDA()).thenReturn(jda);
        when(jda.getGuilds()).thenReturn(List.of(guild));
        when(jda.getGuildById("999")).thenReturn(guild);
        when(guild.getCategories()).thenReturn(List.of(category));
        when(guild.getCategoriesByName(categoryName, false)).thenReturn(List.of(category));
        when(category.getName()).thenReturn(categoryName);
        when(category.getGuild()).thenReturn(guild);
        var everyoneOverride = mock(net.dv8tion.jda.api.entities.PermissionOverride.class);
        when(everyoneOverride.getDenied()).thenReturn(java.util.EnumSet.of(Permission.VIEW_CHANNEL));
        when(category.getPermissionOverride(everyone)).thenReturn(everyoneOverride);
        when(guild.getTextChannelsByName("bot-log", true)).thenReturn(List.of(reply));
        when(reply.getGuild()).thenReturn(guild);
        when(reply.canTalk()).thenReturn(true);
        when(guild.createRole().setName(name).setMentionable(true).complete()).thenAnswer(call -> {
            roleCreated.set(true);
            return role;
        });
        when(guild.addRoleToMember(owner, role).submit())
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(owner.getRoles()).thenReturn(List.of(role));
        when(guild.createTextChannel(name + "-Browser-Game", category)
                        .syncPermissionOverrides()
                        .addRolePermissionOverride(eq(77L), anyLong(), eq(0L))
                        .complete())
                .thenReturn(chat);
        when(guild.createTextChannel(name + "-actions", category)
                        .syncPermissionOverrides()
                        .addRolePermissionOverride(eq(77L), anyLong(), eq(0L))
                        .complete())
                .thenReturn(actions);
        when(chat.getId()).thenReturn("201");
        when(actions.getId()).thenReturn("202");
        when(chat.getGuild()).thenReturn(guild);
        when(actions.getGuild()).thenReturn(guild);
        when(jda.getTextChannelById("201")).thenReturn(chat);
        when(jda.getTextChannelById("202")).thenReturn(actions);
        when(actions.createThreadChannel(anyString())
                        .setAutoArchiveDuration(any())
                        .complete())
                .thenReturn(mapThread);
        when(actions.createThreadChannel(anyString(), eq(true))
                        .setAutoArchiveDuration(any())
                        .setInvitable(false)
                        .complete())
                .thenReturn(cards);
        when(mapThread.getId()).thenReturn("301");
        when(cards.getId()).thenReturn("302");
        when(cards.getGuild()).thenReturn(guild);
        when(cards.retrieveThreadMemberById("123").useCache(false).complete())
                .thenReturn(mock(net.dv8tion.jda.api.entities.ThreadMember.class));
        when(cards.getParentChannel().getId()).thenReturn("202");
        when(jda.getThreadChannelById("301")).thenReturn(mapThread);
        when(guild.getThreadChannelById("302")).thenReturn(cards);
        @SuppressWarnings("unchecked")
        net.dv8tion.jda.api.requests.restaction.ChannelAction<Category> categoryAction =
                mock(net.dv8tion.jda.api.requests.restaction.ChannelAction.class, RETURNS_SELF);
        if ("new-category".equals(failurePoint)) {
            var ownBotAccess = new java.util.concurrent.atomic.AtomicBoolean();
            when(bot.getIdLong()).thenReturn(456L);
            when(bot.hasPermission(Permission.ADMINISTRATOR)).thenReturn(false);
            when(bot.hasPermission(eq(category), any(Permission[].class))).thenAnswer(call -> ownBotAccess.get());
            when(categoryAction.addMemberPermissionOverride(
                            eq(456L), eq(java.util.EnumSet.of(Permission.VIEW_CHANNEL)), isNull()))
                    .thenAnswer(call -> {
                        ownBotAccess.set(true);
                        return categoryAction;
                    });
            var categoryCreated = new java.util.concurrent.atomic.AtomicBoolean();
            when(guild.getCategories()).thenReturn(List.of());
            when(guild.getCategoriesByName(categoryName, false))
                    .thenAnswer(call -> categoryCreated.get() ? List.of(category) : List.of());
            when(guild.getRolesByName("@everyone", true)).thenReturn(List.of(everyone));
            when(everyone.getIdLong()).thenReturn(11L);
            when(guild.createCategory(categoryName)).thenReturn(categoryAction);
            when(categoryAction.complete()).thenAnswer(call -> {
                categoryCreated.set(true);
                return category;
            });
        }
        var records = new HashMap<String, WrapperCreationOperation>();
        WrapperCreationOperations operations = mock(WrapperCreationOperations.class);
        when(operations.find(anyString(), anyString()))
                .thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0) + ":" + call.getArgument(1))));
        when(operations.save(any())).thenAnswer(call -> {
            WrapperCreationOperation operation = call.getArgument(0);
            records.put(operation.getOperationId(), operation);
            return operation;
        });
        ActiveLeaseService lease = mock(ActiveLeaseService.class);
        when(lease.mayMutate()).thenReturn(true);
        when(lease.isReady()).thenReturn(true);
        if ("role".equals(failurePoint)) {
            when(guild.createRole().setName(name).setMentionable(true).complete())
                    .thenThrow(new IllegalStateException("synthetic role failure"));
        }
        if ("actions".equals(failurePoint)) {
            when(guild.createTextChannel(name + "-actions", category)
                            .syncPermissionOverrides()
                            .addRolePermissionOverride(eq(77L), anyLong(), eq(0L))
                            .complete())
                    .thenThrow(new IllegalStateException("synthetic channel failure"));
        }
        if ("cards".equals(failurePoint)) {
            when(actions.createThreadChannel(anyString(), eq(true))
                            .setAutoArchiveDuration(any())
                            .setInvitable(false)
                            .complete())
                    .thenThrow(new IllegalStateException("synthetic thread failure"));
        }
        if ("role-assignment".equals(failurePoint)) {
            when(guild.addRoleToMember(owner, role).submit())
                    .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("synthetic role assignment failure")));
            when(owner.getRoles()).thenReturn(List.of());
        }
        if ("lease".equals(failurePoint)) {
            when(lease.mayMutate()).thenAnswer(call -> !roleCreated.get());
        }
        if ("full-category".equals(failurePoint)) {
            when(category.getChannels()).thenReturn(java.util.Collections.nCopies(49, chat));
        }
        if ("unsafe-category".equals(failurePoint)) {
            when(everyoneOverride.getDenied()).thenReturn(java.util.EnumSet.noneOf(Permission.class));
        }
        if ("bot-permissions".equals(failurePoint)) {
            when(bot.hasPermission(any(Permission[].class))).thenReturn(false);
        }
        if ("reserved".equals(failurePoint)) {
            when(operations.isReserved("pbd" + (originalNumber + 1))).thenReturn(true);
        }
        roleCreated.set(false);
        JdaService.jda = jda;
        JdaService.guildPrimary = guild;
        try (var messages = mockStatic(MessageHelper.class);
                var storage = mockStatic(Storage.class, CALLS_REAL_METHODS);
                var archive = mockStatic(ThreadArchiveHelper.class);
                var buttons = mockStatic(ButtonHelper.class);
                var surveys = mockStatic(StartPhaseService.class);
                var database = mockStatic(GameDatabaseSyncPipeline.class)) {
            storage.when(Storage::getStoragePath).thenReturn(directory.toString());
            clearInvocations(guild, jda, actions, cards);
            var service = new WrapperGameCreationService(operations, lease);
            if ("native".equals(failurePoint)) {
                var event = mock(net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent.class);
                when(event.getMessageChannel()).thenReturn(reply);
                Game nativeGame = ti4.service.game.CreateGameService.createGameChannels(
                        List.of(owner), event, "Browser Game", name, owner, category);
                GameManager.save(nativeGame, "Native entrypoint final save");
                assertThat(GameManager.reload(name).getPlayer("123").getCardsInfoThreadID())
                        .isEqualTo("302");
                verifyNoInteractions(operations);
                return;
            }
            if (List.of("full-category", "unsafe-category", "bot-permissions").contains(failurePoint)) {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> service.create("123", "real-original", "Browser Game", List.of("123")))
                        .hasMessageContaining("409");
                assertThat(GameManager.getLatestPbdNumber()).isEqualTo(originalNumber);
                verify(operations, never()).save(any());
                verify(guild, never()).createRole();
                return;
            }
            var result = service.create("123", "real-original", "Browser Game", List.of("123"));
            if (!List.of("success", "reserved", "new-category").contains(failurePoint)) {
                assertThat(result.state()).isEqualTo("NEEDS_REPAIR");
                assertThat(service.status("123", "real-original")).isEqualTo(result);
                if (!"lease".equals(failurePoint)) {
                    assertThat(service.create("123", "real-original", "Browser Game", List.of("123")))
                            .isEqualTo(result);
                }
                verify(guild, times(1)).createRole();
                verify(role, never()).delete();
                return;
            }
            assertThat(result.state()).isEqualTo("COMPLETED");
            Game saved = GameManager.reload(result.gameName());
            assertThat(saved).isNotNull();
            assertThat(saved.getOwnerID()).isEqualTo("123");
            assertThat(saved.getCustomName()).isEqualTo("Browser Game");
            assertThat(saved.isThundersEdge()).isTrue();
            assertThat(saved.getPlayer("123").getCardsInfoThreadID()).isEqualTo("302");
            assertThat(saved.getMainChannelID()).isEqualTo("202");
            messages.verify(() -> MessageHelper.sendMessageToChannelWithButtons(
                    eq(actions),
                    anyString(),
                    argThat(list -> list.stream().anyMatch(button -> "miltySetup".equals(button.getCustomId())))));
            assertThat(service.create("123", "real-original", "Browser Game", List.of("123")))
                    .isEqualTo(result);
            verify(guild, times(1)).createRole();
            var order = inOrder(operations, guild);
            order.verify(operations).save(argThat(operation -> "CREATING".equals(operation.getState())));
            if ("new-category".equals(failurePoint)) {
                order.verify(guild).createCategory(categoryName);
                verify(categoryAction)
                        .addRolePermissionOverride(11L, null, java.util.EnumSet.of(Permission.VIEW_CHANNEL));
                verify(categoryAction)
                        .addMemberPermissionOverride(456L, java.util.EnumSet.of(Permission.VIEW_CHANNEL), null);
            }
            order.verify(guild).createRole();
        } finally {
            JdaService.jda = previousJda;
            JdaService.guildPrimary = previousGuild;
        }
    }
}

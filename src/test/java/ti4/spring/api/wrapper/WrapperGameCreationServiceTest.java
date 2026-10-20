package ti4.spring.api.wrapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;
import ti4.discord.JdaService;
import ti4.spring.service.deploy.ActiveLeaseService;
import ti4.spring.service.wrapper.WrapperCreationOperation;
import ti4.spring.service.wrapper.WrapperCreationOperations;

class WrapperGameCreationServiceTest {
    @Test
    void leadingZeroAliasCannotTurnOneHumanIntoTwoSeats() {
        Guild previous = JdaService.guildPrimary;
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member owner = mock(Member.class, RETURNS_DEEP_STUBS);
        when(owner.getGuild()).thenReturn(guild);
        when(owner.isOwner()).thenReturn(true);
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(owner);
        JdaService.guildPrimary = guild;
        WrapperCreationOperations operations = mock(WrapperCreationOperations.class);
        ActiveLeaseService lease = mock(ActiveLeaseService.class);
        when(lease.mayMutate()).thenReturn(true);
        when(lease.isReady()).thenReturn(true);
        try {
            var service = new WrapperGameCreationService(operations, lease);
            assertThatThrownBy(() -> service.create("123", "request-1", "Game", List.of("123", "0123")))
                    .hasMessageContaining("400");
            verifyNoInteractions(operations);
            verify(guild, never()).createRole();
        } finally {
            JdaService.guildPrimary = previous;
        }
    }

    @Test
    void unknownSelectedMemberIsRejectedBeforeReservingAName() {
        Guild previous = JdaService.guildPrimary;
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member owner = mock(Member.class, RETURNS_DEEP_STUBS);
        when(owner.getGuild()).thenReturn(guild);
        when(owner.getId()).thenReturn("123");
        when(owner.isOwner()).thenReturn(true);
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(owner);
        when(guild.retrieveMemberById("456").useCache(false).complete())
                .thenThrow(new IllegalArgumentException("Unknown member"));
        JdaService.guildPrimary = guild;
        WrapperCreationOperations operations = mock(WrapperCreationOperations.class);
        when(operations.find("123", "request-1")).thenReturn(Optional.empty());
        ActiveLeaseService lease = mock(ActiveLeaseService.class);
        when(lease.mayMutate()).thenReturn(true);
        when(lease.isReady()).thenReturn(true);
        try {
            var service = new WrapperGameCreationService(operations, lease);
            assertThatThrownBy(() -> service.create("123", "request-1", "Game", List.of("123", "456")))
                    .hasMessageContaining("400");
            verify(operations, never()).save(any());
            verify(guild, never()).createRole();
            verify(guild, never()).createCategory(any());
        } finally {
            JdaService.guildPrimary = previous;
        }
    }

    @Test
    void interruptedCreationRequiresRepairAndNeverCreatesReplacement() throws Exception {
        Guild previous = JdaService.guildPrimary;
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member owner = mock(Member.class);
        when(owner.getGuild()).thenReturn(guild);
        when(owner.isOwner()).thenReturn(true);
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(owner);
        JdaService.guildPrimary = guild;
        WrapperCreationOperations operations = mock(WrapperCreationOperations.class);
        String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest("Game\n123".getBytes(StandardCharsets.UTF_8)));
        when(operations.find("123", "request-1"))
                .thenReturn(Optional.of(new WrapperCreationOperation(
                        "123:request-1", "123", "request-1", hash, "pbd7", "dead-process", "CREATING")));
        when(operations.save(any())).thenAnswer(call -> call.getArgument(0));
        ActiveLeaseService lease = mock(ActiveLeaseService.class);
        when(lease.mayMutate()).thenReturn(true);
        when(lease.isReady()).thenReturn(true);
        try {
            var service = new WrapperGameCreationService(operations, lease);
            assertThat(service.create("123", "request-1", "Game", List.of("123"))
                            .state())
                    .isEqualTo("NEEDS_REPAIR");
            verify(guild, never()).createRole();
            verify(operations)
                    .save(argThat(operation ->
                            "pbd7".equals(operation.getGameName()) && "NEEDS_REPAIR".equals(operation.getState())));
        } finally {
            JdaService.guildPrimary = previous;
        }
    }

    @Test
    void completedRequestSurvivesServiceRestartWithoutProvisioningAgain() throws Exception {
        Guild previous = JdaService.guildPrimary;
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member owner = mock(Member.class);
        when(owner.getGuild()).thenReturn(guild);
        when(owner.isOwner()).thenReturn(true);
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(owner);
        JdaService.guildPrimary = guild;
        WrapperCreationOperations operations = mock(WrapperCreationOperations.class);
        String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest("Game\n123".getBytes(StandardCharsets.UTF_8)));
        when(operations.find("123", "request-1"))
                .thenReturn(Optional.of(new WrapperCreationOperation(
                        "123:request-1", "123", "request-1", hash, "pbd7", "old-instance", "COMPLETED")));
        ActiveLeaseService lease = mock(ActiveLeaseService.class);
        when(lease.mayMutate()).thenReturn(true);
        when(lease.isReady()).thenReturn(true);
        try {
            var service = new WrapperGameCreationService(operations, lease);
            assertThat(service.create("123", "request-1", "Game", List.of("123"))
                            .gameName())
                    .isEqualTo("pbd7");
            verify(guild, never()).createRole();
            verify(operations, never()).save(any());
            assertThatThrownBy(() -> service.create("123", "request-1", "Changed", List.of("123")))
                    .hasMessageContaining("409");
        } finally {
            JdaService.guildPrimary = previous;
        }
    }

    @Test
    void inactiveLeaseRejectsEvenTheOwnGuildOwnerBeforeReservation() {
        Guild previous = JdaService.guildPrimary;
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member owner = mock(Member.class);
        when(owner.getGuild()).thenReturn(guild);
        when(owner.isOwner()).thenReturn(true);
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(owner);
        JdaService.guildPrimary = guild;
        WrapperCreationOperations operations = mock(WrapperCreationOperations.class);
        ActiveLeaseService lease = mock(ActiveLeaseService.class);
        try {
            var service = new WrapperGameCreationService(operations, lease);
            assertThatThrownBy(() -> service.create("123", "request-1", "Game", List.of("123")))
                    .hasMessageContaining("503");
            verify(lease).mayMutate();
            verifyNoInteractions(operations);
            verify(guild, never()).createRole();
        } finally {
            JdaService.guildPrimary = previous;
        }
    }

    @Test
    void ordinaryMemberCannotCreateAnything() {
        Guild previous = JdaService.guildPrimary;
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member member = mock(Member.class);
        when(member.getGuild()).thenReturn(guild);
        when(member.getRoles()).thenReturn(List.of());
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(member);
        JdaService.guildPrimary = guild;
        WrapperCreationOperations operations = mock(WrapperCreationOperations.class);
        ActiveLeaseService lease = mock(ActiveLeaseService.class);
        try {
            var service = new WrapperGameCreationService(operations, lease);
            assertThatThrownBy(() -> service.create("123", "request-1", "Game", List.of("123")))
                    .hasMessageContaining("403");
            verifyNoInteractions(operations, lease);
            verify(guild, never()).createRole();
        } finally {
            JdaService.guildPrimary = previous;
        }
    }
}

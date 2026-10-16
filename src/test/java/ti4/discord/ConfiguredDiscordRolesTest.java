package ti4.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.Test;

class ConfiguredDiscordRolesTest {

    @Test
    void rolesCanOnlyComeFromAnAllowedGuild() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn("123456");
        Role role = mock(Role.class);
        when(role.getGuild()).thenReturn(guild);
        when(jda.getRoleById("654321")).thenReturn(role);
        var roles = new HashSet<Role>();
        assertThrows(IllegalStateException.class, () -> ConfiguredDiscordRoles.addTo(jda, roles, "654321"));
        JdaService.guilds.add(guild);
        try {
            ConfiguredDiscordRoles.addTo(jda, roles, "654321, 654321");
            assertEquals(1, roles.size());
        } finally {
            JdaService.guilds.remove(guild);
        }
        assertThrows(IllegalArgumentException.class, () -> ConfiguredDiscordRoles.addTo(jda, roles, "Developer"));
    }
}

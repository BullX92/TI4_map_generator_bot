package ti4.discord;

import java.util.Arrays;
import java.util.Set;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Role;

final class ConfiguredDiscordRoles {

    private ConfiguredDiscordRoles() {}

    static void addTo(JDA jda, Set<Role> roles, String configuredIds) {
        if (configuredIds == null || configuredIds.isBlank()) return;
        Arrays.stream(configuredIds.trim().split("[,\\s]+"))
                .distinct()
                .map(id -> resolve(jda, id))
                .forEach(roles::add);
    }

    private static Role resolve(JDA jda, String id) {
        if (!id.matches("[0-9]{6,20}")) {
            throw new IllegalArgumentException("Configured Discord role IDs must be numeric snowflakes.");
        }
        Role role = jda.getRoleById(id);
        if (role == null || !JdaService.isValidGuild(role.getGuild().getId())) {
            throw new IllegalStateException("Configured Discord role is not available in an allowed guild: " + id);
        }
        return role;
    }
}

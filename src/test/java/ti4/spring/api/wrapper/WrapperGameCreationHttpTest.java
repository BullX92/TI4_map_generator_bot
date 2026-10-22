package ti4.spring.api.wrapper;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import ti4.discord.JdaService;
import ti4.spring.security.DiscordOpaqueTokenIntrospector;
import ti4.spring.security.OptionalAuthFilter;
import ti4.spring.security.SecurityConfiguration;
import ti4.spring.service.deploy.ActiveLeaseService;
import ti4.spring.service.wrapper.WrapperCreationOperations;

class WrapperGameCreationHttpTest {
    @Test
    void realSecurityChainRejectsAnonymousCreationBeforeController() throws Exception {
        try (var context = context()) {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                    .build();
            mvc.perform(post("/api/wrapper/games")
                            .contentType("application/json")
                            .content("{\"requestId\":\"request-1\",\"title\":\"Game\",\"memberIds\":[\"123\"]}"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(context.getBean(WrapperCreationOperations.class));
        }
    }

    @Test
    void ordinaryAuthenticatedMemberCannotForgeAnAdministrator() throws Exception {
        Guild previous = JdaService.guildPrimary;
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member member = mock(Member.class);
        when(member.getGuild()).thenReturn(guild);
        when(member.getRoles()).thenReturn(List.of());
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(member);
        JdaService.guildPrimary = guild;
        try (var context = context()) {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                    .build();
            mvc.perform(
                            post("/api/wrapper/games")
                                    .header("Authorization", "Bearer member-token")
                                    .contentType("application/json")
                                    .content(
                                            "{\"requestId\":\"request-1\",\"title\":\"Game\",\"memberIds\":[\"123\"],\"actorId\":\"999\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/wrapper/game-creation").header("Authorization", "Bearer member-token"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(context.getBean(WrapperCreationOperations.class));
            verify(guild, never()).createRole();
        } finally {
            JdaService.guildPrimary = previous;
        }
    }

    @Test
    void pathWithoutGameNameStillRejectsInactiveOwnerMutation() throws Exception {
        Guild previous = JdaService.guildPrimary;
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member owner = mock(Member.class);
        when(owner.getGuild()).thenReturn(guild);
        when(owner.isOwner()).thenReturn(true);
        when(guild.retrieveMemberById("123").useCache(false).complete()).thenReturn(owner);
        JdaService.guildPrimary = guild;
        try (var context = context()) {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                    .build();
            mvc.perform(post("/api/wrapper/games")
                            .header("Authorization", "Bearer member-token")
                            .contentType("application/json")
                            .content("{\"requestId\":\"request-1\",\"title\":\"Game\",\"memberIds\":[\"123\"]}"))
                    .andExpect(status().isServiceUnavailable());
            verifyNoInteractions(context.getBean(WrapperCreationOperations.class));
            verify(guild, never()).createRole();
        } finally {
            JdaService.guildPrimary = previous;
        }
    }

    private AnnotationConfigWebApplicationContext context() {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("wrapper", Map.of("ti4.wrapper.enabled", "true")));
        context.register(HttpConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, WrapperGameCreationController.class, OptionalAuthFilter.class})
    static class HttpConfig {
        @Bean
        WrapperCreationOperations operations() {
            return mock(WrapperCreationOperations.class);
        }

        @Bean
        ActiveLeaseService lease() {
            return mock(ActiveLeaseService.class);
        }

        @Bean
        WrapperGameCreationService creation(WrapperCreationOperations operations, ActiveLeaseService lease) {
            return new WrapperGameCreationService(operations, lease);
        }

        @Bean
        DiscordOpaqueTokenIntrospector introspector() {
            var introspector = mock(DiscordOpaqueTokenIntrospector.class);
            when(introspector.introspect("member-token"))
                    .thenReturn(new DefaultOAuth2AuthenticatedPrincipal("123", Map.of("id", "123"), List.of()));
            return introspector;
        }
    }
}

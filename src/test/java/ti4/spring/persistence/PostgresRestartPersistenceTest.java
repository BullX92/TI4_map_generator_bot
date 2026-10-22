package ti4.spring.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import ti4.spring.service.persistence.GameEntity;
import ti4.spring.service.persistence.GameEntityPersistenceService;
import ti4.spring.service.persistence.GameEntitySnapshot;
import ti4.spring.service.persistence.PersistedGameState;
import ti4.spring.service.persistence.PersistedGameStateService;
import ti4.spring.service.persistence.PlayerEntity;
import ti4.spring.service.persistence.TitleEntity;
import ti4.spring.service.persistence.UserEntity;
import ti4.spring.service.persistence.UserEntityRepository;
import ti4.spring.service.wrapper.WrapperCreationOperation;
import ti4.spring.service.wrapper.WrapperCreationOperations;
import ti4.spring.service.wrapper.WrapperMessageIndex;

@EnabledIfEnvironmentVariable(named = "TI4_TEST_POSTGRES_URL", matches = ".+")
class PostgresRestartPersistenceTest {

    @Test
    void creationReservationSurvivesRestartAndCannotBeOverwrittenByDuplicateInsert() {
        String actor = "creation-" + UUID.randomUUID();
        String request = "request-1";
        String name = "reserved-" + UUID.randomUUID();
        var initial = new WrapperCreationOperation(
                actor + ":" + request, actor, request, "payload-hash", name, "stopped-process", "CREATING");
        try {
            try (ConfigurableApplicationContext writer = context()) {
                var operations = writer.getBean(WrapperCreationOperations.class);
                assertThat(operations.save(initial).getVersion()).isNotNull();
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                .isActualTransactionActive())
                        .isFalse();
            }
            try (ConfigurableApplicationContext reader = context()) {
                var operations = reader.getBean(WrapperCreationOperations.class);
                var restored = operations.find(actor, request).orElseThrow();
                assertThat(restored.getGameName()).isEqualTo(name);
                assertThat(restored.getPayloadHash()).isEqualTo("payload-hash");
                assertThat(restored.getState()).isEqualTo("CREATING");
                assertThat(operations.isReserved(name)).isTrue();
                var duplicate = new WrapperCreationOperation(
                        actor + ":" + request,
                        actor,
                        request,
                        "changed-payload",
                        name + "-replacement",
                        "new-process",
                        "CREATING");
                assertThatThrownBy(() -> operations.save(duplicate))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                assertThat(operations.find(actor, request).orElseThrow().getPayloadHash())
                        .isEqualTo("payload-hash");
                assertThat(operations.save(restored.withState("NEEDS_REPAIR")).getState())
                        .isEqualTo("NEEDS_REPAIR");
            }
            try (ConfigurableApplicationContext reader = context()) {
                var operations = reader.getBean(WrapperCreationOperations.class);
                assertThat(operations.find(actor, request).orElseThrow().getState())
                        .isEqualTo("NEEDS_REPAIR");
                assertThat(operations.find("different-actor", request)).isEmpty();
            }
        } finally {
            try (ConfigurableApplicationContext cleanup = context()) {
                new JdbcTemplate(cleanup.getBean(javax.sql.DataSource.class))
                        .update("delete from wrapper_game_creation where actor_id = ?", actor);
            }
        }
    }

    @Test
    void offeredMessageIndexSurvivesRestartAndKeepsMoreThanOneHistoryPage() {
        String game = "wrapper-index-" + UUID.randomUUID();
        List<String> ids = java.util.stream.IntStream.range(0, 220)
                .mapToObj(index -> game + "-" + String.format("%03d", index))
                .toList();
        String privateMessage = game + "-private";
        String otherGameMessage = game + "-foreign";
        try {
            try (ConfigurableApplicationContext writer = context()) {
                var index = writer.getBean(WrapperMessageIndex.class);
                ids.forEach(id -> index.remember(game, "public", id));
                index.remember(game, "private", privateMessage);
                index.remember(game + "-other", "public", otherGameMessage);
                index.remember(game, "public", ids.getFirst());
            }
            try (ConfigurableApplicationContext reader = context()) {
                var index = reader.getBean(WrapperMessageIndex.class);
                assertThat(index.messageIds(game, "public")).containsExactlyInAnyOrderElementsOf(ids);
                assertThat(index.messageIds(game, "private")).containsExactly(privateMessage);
                index.remove(ids.getFirst());
                assertThat(index.messageIds(game, "public")).hasSize(219).doesNotContain(ids.getFirst());
            }
        } finally {
            try (ConfigurableApplicationContext cleanup = context()) {
                var index = cleanup.getBean(WrapperMessageIndex.class);
                ids.forEach(index::remove);
                index.remove(privateMessage);
                index.remove(otherGameMessage);
            }
        }
    }

    @Test
    void gamePlayerAndTitleSurviveClosingAndReopeningApplicationContext() {
        String gameName = "wrapper-persistence-" + UUID.randomUUID();
        GameEntitySnapshot snapshot = snapshot(gameName);
        PersistedGameState expected = PersistedGameState.of(snapshot);
        boolean written = false;

        try {
            try (ConfigurableApplicationContext writer = context()) {
                String version = new JdbcTemplate(writer.getBean(javax.sql.DataSource.class))
                        .queryForObject("SHOW server_version", String.class);
                assertThat(version).startsWith("16.");
                writer.getBean(GameEntityPersistenceService.class).replace(snapshot);
                written = true;
            }

            // A new connection pool and persistence context cannot satisfy this from Hibernate's cache.
            try (ConfigurableApplicationContext reader = context()) {
                PersistedGameState actual = reader.getBean(PersistedGameStateService.class)
                        .loadAll()
                        .get(gameName);
                assertThat(actual).isEqualTo(expected);
                assertThat(reader.getBean(UserEntityRepository.class)
                                .findById(gameName)
                                .orElseThrow()
                                .getName())
                        .isEqualTo("Wrapper integration seat");
            }
        } finally {
            if (written) {
                try (ConfigurableApplicationContext cleanup = context()) {
                    cleanup.getBean(GameEntityPersistenceService.class).delete(gameName);
                    cleanup.getBean(UserEntityRepository.class).deleteById(gameName);
                }
            }
        }
    }

    private ConfigurableApplicationContext context() {
        return new SpringApplicationBuilder(PersistenceConfig.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + System.getenv("TI4_TEST_POSTGRES_URL"),
                        "--spring.datasource.username=" + envOrDefault("TI4_TEST_POSTGRES_USER", "tibot"),
                        "--spring.datasource.password=" + envOrDefault("TI4_TEST_POSTGRES_PASSWORD", ""),
                        "--spring.jpa.hibernate.ddl-auto=update",
                        "--spring.datasource.hikari.maximum-pool-size=2");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private GameEntitySnapshot snapshot(String gameName) {
        var game = new GameEntity();
        game.setGameName(gameName);
        game.setRound(3);
        game.setVictoryPointGoal(10);
        game.setThundersEdge(true);
        game.setPlayerCount(1);
        game.setCreationEpochMilliseconds(1792015200000L);
        var user = new UserEntity(gameName, "Wrapper integration seat");
        var player = new PlayerEntity();
        player.setGame(game);
        player.setUser(user);
        player.setFactionName("sol");
        player.setScore(4);
        game.getPlayers().add(player);
        var title = new TitleEntity();
        title.setGame(game);
        title.setUser(user);
        title.setTitle("Integration survivor");
        return new GameEntitySnapshot(game, List.of(user), List.of(title));
    }

    @Configuration
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class
    })
    @EntityScan(basePackages = {"ti4.spring.service.persistence", "ti4.spring.service.wrapper"})
    @EnableJpaRepositories(basePackages = {"ti4.spring.service.persistence", "ti4.spring.service.wrapper"})
    @Import({
        GameEntityPersistenceService.class,
        PersistedGameStateService.class,
        WrapperMessageIndex.class,
        WrapperCreationOperations.class
    })
    static class PersistenceConfig {}
}

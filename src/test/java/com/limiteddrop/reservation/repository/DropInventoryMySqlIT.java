package com.limiteddrop.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.limiteddrop.reservation.domain.Drop;
import com.limiteddrop.reservation.domain.DropStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Engine-specific concurrency test against a real MySQL/InnoDB instance with the production Flyway
 * schema applied. Gated behind {@code @Tag("integration")} (run via {@code mvn verify -Pintegration})
 * and skipped automatically when Docker is unavailable, so the default offline build is unaffected.
 */
@Tag("integration")
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("dockerAvailable")
class DropInventoryMySqlIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // Run the REAL production migrations (V1/V2/V3) against InnoDB, then validate the mapping.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private DropRepository dropRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void reserveInventoryNeverOversellsOnInnoDb() throws Exception {
        int totalUnits = 5;
        Drop saved = dropRepository.saveAndFlush(newDrop(totalUnits));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                tasks.add(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> dropRepository.reserveInventory(saved.getId(), 1)));
            }

            List<Future<Integer>> futures = pool.invokeAll(tasks);
            int successfulReservations = 0;
            for (Future<Integer> future : futures) {
                successfulReservations += future.get();
            }

            Drop refreshed = dropRepository.findById(saved.getId()).orElseThrow();
            assertThat(successfulReservations).isEqualTo(totalUnits);
            assertThat(refreshed.getAvailableUnits()).isZero();
            assertThat(refreshed.getHeldUnits()).isEqualTo(totalUnits);
            assertThat(refreshed.getAvailableUnits() + refreshed.getHeldUnits() + refreshed.getConfirmedUnits())
                .isEqualTo(totalUnits);
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void lastUnitRaceProducesExactlyOneWinner() throws Exception {
        Drop saved = dropRepository.saveAndFlush(newDrop(1));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> dropRepository.reserveInventory(saved.getId(), 1)));
            }

            int winners = 0;
            for (Future<Integer> future : pool.invokeAll(tasks)) {
                winners += future.get();
            }

            Drop refreshed = dropRepository.findById(saved.getId()).orElseThrow();
            assertThat(winners).isEqualTo(1);
            assertThat(refreshed.getAvailableUnits()).isZero();
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Drop newDrop(int units) {
        Drop drop = new Drop();
        drop.setName("IT Drop");
        drop.setDescription("MySQL integration drop");
        drop.setStatus(DropStatus.OPEN);
        drop.setOpensAt(Instant.now().minusSeconds(60));
        drop.setClosesAt(Instant.now().plusSeconds(3600));
        drop.setUnitPrice(new BigDecimal("10.00"));
        drop.setCurrency("USD");
        drop.setTotalUnits(units);
        drop.setAvailableUnits(units);
        drop.setHeldUnits(0);
        drop.setConfirmedUnits(0);
        drop.setVersion(0L);
        return drop;
    }
}

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DropRepositoryConcurrencyTest {

    @Autowired
    private DropRepository dropRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void reserveInventoryLastUnitRaceSucceedsOnlyOnce() throws Exception {
        Drop drop = new Drop();
        drop.setName("Race Drop");
        drop.setDescription("Race condition test drop");
        drop.setStatus(DropStatus.OPEN);
        drop.setOpensAt(Instant.now().minusSeconds(60));
        drop.setClosesAt(Instant.now().plusSeconds(3600));
        drop.setUnitPrice(new BigDecimal("10.00"));
        drop.setCurrency("USD");
        drop.setTotalUnits(1);
        drop.setAvailableUnits(1);
        drop.setHeldUnits(0);
        drop.setConfirmedUnits(0);
        drop.setVersion(0L);

        Drop saved = dropRepository.saveAndFlush(drop);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> dropRepository.reserveInventory(saved.getId(), 1)));
            }

            List<Future<Integer>> futures = pool.invokeAll(tasks);
            int successfulReservations = 0;
            for (Future<Integer> future : futures) {
                successfulReservations += future.get();
            }

            Drop refreshed = dropRepository.findById(saved.getId()).orElseThrow();
            assertThat(successfulReservations).isEqualTo(1);
            assertThat(refreshed.getAvailableUnits()).isEqualTo(0);
            assertThat(refreshed.getHeldUnits()).isEqualTo(1);
        } finally {
            pool.shutdown();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void reserveInventoryNeverOversellsUnderContention() throws Exception {
        int totalUnits = 5;
        Drop drop = new Drop();
        drop.setName("Multi Unit Drop");
        drop.setDescription("Multi-unit oversell test");
        drop.setStatus(DropStatus.OPEN);
        drop.setOpensAt(Instant.now().minusSeconds(60));
        drop.setClosesAt(Instant.now().plusSeconds(3600));
        drop.setUnitPrice(new BigDecimal("10.00"));
        drop.setCurrency("USD");
        drop.setTotalUnits(totalUnits);
        drop.setAvailableUnits(totalUnits);
        drop.setHeldUnits(0);
        drop.setConfirmedUnits(0);
        drop.setVersion(0L);

        Drop saved = dropRepository.saveAndFlush(drop);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
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
            // Conservation invariant: nothing is ever created or destroyed.
            assertThat(refreshed.getAvailableUnits() + refreshed.getHeldUnits() + refreshed.getConfirmedUnits())
                .isEqualTo(totalUnits);
        } finally {
            pool.shutdown();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}

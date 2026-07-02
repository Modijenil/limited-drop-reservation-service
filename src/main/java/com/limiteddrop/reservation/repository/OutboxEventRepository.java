package com.limiteddrop.reservation.repository;

import com.limiteddrop.reservation.domain.OutboxEvent;
import com.limiteddrop.reservation.domain.OutboxStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("select e from OutboxEvent e "
        + "where e.status = com.limiteddrop.reservation.domain.OutboxStatus.PENDING "
        + "and (e.nextAttemptAt is null or e.nextAttemptAt <= :now) "
        + "order by e.createdAt asc")
    List<OutboxEvent> findPublishable(@Param("now") Instant now, Pageable pageable);

    List<OutboxEvent> findByStatus(OutboxStatus status);
}

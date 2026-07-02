package com.limiteddrop.reservation.repository;

import com.limiteddrop.reservation.domain.Hold;
import com.limiteddrop.reservation.domain.HoldStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldRepository extends JpaRepository<Hold, String> {

    Optional<Hold> findByIdempotencyKey(String idempotencyKey);

    List<Hold> findTop200ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(HoldStatus status, Instant expiresAt);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        update Hold h
        set h.status = com.limiteddrop.reservation.domain.HoldStatus.CONFIRMED
        where h.id = :id
          and h.status = com.limiteddrop.reservation.domain.HoldStatus.ACTIVE
    """)
    int markConfirmed(@org.springframework.data.repository.query.Param("id") String id);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        update Hold h
        set h.status = com.limiteddrop.reservation.domain.HoldStatus.CANCELLED
        where h.id = :id
          and h.status = com.limiteddrop.reservation.domain.HoldStatus.ACTIVE
    """)
    int markCancelled(@org.springframework.data.repository.query.Param("id") String id);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        update Hold h
        set h.status = com.limiteddrop.reservation.domain.HoldStatus.EXPIRED
        where h.id = :id
          and h.status = com.limiteddrop.reservation.domain.HoldStatus.ACTIVE
    """)
    int markExpired(@org.springframework.data.repository.query.Param("id") String id);
}

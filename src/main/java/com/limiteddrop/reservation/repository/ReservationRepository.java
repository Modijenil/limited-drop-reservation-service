package com.limiteddrop.reservation.repository;

import com.limiteddrop.reservation.domain.Reservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByHoldId(String holdId);
}

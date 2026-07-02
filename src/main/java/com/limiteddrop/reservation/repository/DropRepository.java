package com.limiteddrop.reservation.repository;

import com.limiteddrop.reservation.domain.Drop;
import com.limiteddrop.reservation.domain.DropStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DropRepository extends JpaRepository<Drop, Long> {

    List<Drop> findByStatusOrderByOpensAtAsc(DropStatus status);

    @Modifying
    @Query("""
        update Drop d
        set d.availableUnits = d.availableUnits - :quantity,
            d.heldUnits = d.heldUnits + :quantity
        where d.id = :dropId
          and d.status = com.limiteddrop.reservation.domain.DropStatus.OPEN
          and d.availableUnits >= :quantity
    """)
    int reserveInventory(@Param("dropId") Long dropId, @Param("quantity") int quantity);

    @Modifying
    @Query("""
        update Drop d
        set d.heldUnits = d.heldUnits - :quantity,
            d.confirmedUnits = d.confirmedUnits + :quantity
        where d.id = :dropId
          and d.heldUnits >= :quantity
    """)
    int confirmInventory(@Param("dropId") Long dropId, @Param("quantity") int quantity);

    @Modifying
    @Query("""
        update Drop d
        set d.availableUnits = d.availableUnits + :quantity,
            d.heldUnits = d.heldUnits - :quantity
        where d.id = :dropId
          and d.heldUnits >= :quantity
    """)
    int releaseInventory(@Param("dropId") Long dropId, @Param("quantity") int quantity);
}

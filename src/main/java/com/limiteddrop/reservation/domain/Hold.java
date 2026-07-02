package com.limiteddrop.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "holds")
public class Hold extends BaseEntity {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "drop_id", nullable = false)
    private Long dropId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Version
    @Column(nullable = false)
    private Long version;

    public static Hold create(Long dropId, String userId, Integer quantity, Instant expiresAt, String idempotencyKey) {
        Hold hold = new Hold();
        hold.id = UUID.randomUUID().toString();
        hold.dropId = dropId;
        hold.userId = userId;
        hold.quantity = quantity;
        hold.status = HoldStatus.ACTIVE;
        hold.expiresAt = expiresAt;
        hold.idempotencyKey = idempotencyKey;
        hold.version = 0L;
        return hold;
    }
}

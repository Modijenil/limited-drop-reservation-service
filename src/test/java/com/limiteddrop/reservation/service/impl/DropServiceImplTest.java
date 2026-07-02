package com.limiteddrop.reservation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.limiteddrop.reservation.domain.Drop;
import com.limiteddrop.reservation.domain.DropStatus;
import com.limiteddrop.reservation.repository.DropRepository;
import com.limiteddrop.reservation.service.exception.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DropServiceImplTest {

    @Mock
    private DropRepository dropRepository;

    @InjectMocks
    private DropServiceImpl dropService;

    @Test
    void getOpenDropsReturnsOnlyOpen() {
        Drop drop = new Drop();
        drop.setId(1L);
        drop.setStatus(DropStatus.OPEN);
        drop.setName("Drop A");
        drop.setOpensAt(Instant.now());

        when(dropRepository.findByStatusOrderByOpensAtAsc(DropStatus.OPEN)).thenReturn(List.of(drop));

        assertThat(dropService.getOpenDrops()).hasSize(1);
    }

    @Test
    void getDropThrowsWhenMissing() {
        when(dropRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dropService.getDrop(42L))
            .isInstanceOf(NotFoundException.class);
    }
}

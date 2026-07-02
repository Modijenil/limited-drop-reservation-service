package com.limiteddrop.reservation.service.impl;

import com.limiteddrop.reservation.domain.Drop;
import com.limiteddrop.reservation.domain.DropStatus;
import com.limiteddrop.reservation.repository.DropRepository;
import com.limiteddrop.reservation.service.DropService;
import com.limiteddrop.reservation.service.exception.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DropServiceImpl implements DropService {

    private final DropRepository dropRepository;

    @Override
    public List<Drop> getOpenDrops() {
        return dropRepository.findByStatusOrderByOpensAtAsc(DropStatus.OPEN);
    }

    @Override
    public Drop getDrop(Long dropId) {
        return dropRepository.findById(dropId)
            .orElseThrow(() -> new NotFoundException("Drop not found: " + dropId));
    }
}

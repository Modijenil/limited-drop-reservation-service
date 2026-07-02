package com.limiteddrop.reservation.service;

import com.limiteddrop.reservation.domain.Drop;
import java.util.List;

public interface DropService {

    List<Drop> getOpenDrops();

    Drop getDrop(Long dropId);
}

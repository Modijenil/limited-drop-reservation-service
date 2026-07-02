package com.limiteddrop.reservation.api;

import com.limiteddrop.reservation.api.dto.DropResponse;
import com.limiteddrop.reservation.service.DropService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/drops")
@RequiredArgsConstructor
public class DropController {

    private final DropService dropService;

    @GetMapping
    public List<DropResponse> getDrops() {
        return dropService.getOpenDrops().stream().map(DropResponse::from).toList();
    }

    @GetMapping("/{dropId}")
    public DropResponse getDrop(@PathVariable Long dropId) {
        return DropResponse.from(dropService.getDrop(dropId));
    }
}

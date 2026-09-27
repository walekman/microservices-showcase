package com.showcase.fraud.api;

import com.showcase.fraud.service.BlocklistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator API for the blocklist, gated by fraud-admin (see SecurityConfig). No Gateway route
 * reaches it: callers talk to Fraud Service directly.
 */
@RestController
@RequestMapping("/fraud/blocklist")
public class BlocklistController {

    private final BlocklistService blocklistService;

    public BlocklistController(BlocklistService blocklistService) {
        this.blocklistService = blocklistService;
    }

    @PutMapping("/{accountId}")
    public ResponseEntity<Void> block(@PathVariable UUID accountId) {
        blocklistService.block(accountId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> unblock(@PathVariable UUID accountId) {
        blocklistService.unblock(accountId);
        return ResponseEntity.noContent().build();
    }
}

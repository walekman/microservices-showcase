package com.showcase.fraud.api;

import com.showcase.fraud.service.FraudCheckService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class FraudCheckController {

    private final FraudCheckService fraudCheckService;

    public FraudCheckController(FraudCheckService fraudCheckService) {
        this.fraudCheckService = fraudCheckService;
    }

    @GetMapping("/fraud-check")
    public ResponseEntity<Void> check(@RequestParam UUID accountId) {
        fraudCheckService.check(accountId);
        return ResponseEntity.ok().build();
    }
}

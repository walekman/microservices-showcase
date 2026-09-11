package com.showcase.transfer.api;

import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferStatus;
import com.showcase.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(@Valid @RequestBody CreateTransferRequest request) {
        Transfer transfer = transferService.execute(
                request.fromAccountId(), request.toAccountId(), request.amount());

        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new TransferFailedException(transfer);
        }
        return ResponseEntity.created(URI.create("/transfers/" + transfer.getId()))
                .body(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    public TransferResponse getTransfer(@PathVariable UUID id) {
        return TransferResponse.from(transferService.getTransfer(id));
    }

    @GetMapping
    public List<TransferResponse> listTransfers(@RequestParam(required = false) TransferStatus status) {
        return transferService.listTransfers(status).stream()
                .map(TransferResponse::from)
                .toList();
    }
}

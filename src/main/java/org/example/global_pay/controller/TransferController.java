package org.example.global_pay.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.global_pay.service.TransferService;
import org.example.global_pay.dto.TransferRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfer")
@RequiredArgsConstructor
@Slf4j
public class TransferController {
    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<String> transfer(@Valid @RequestBody TransferRequest request) {
        log.info("Transfer request to transfer {} from account {} to account {}", request.getAmount(), request.getFromId(), request.getToId());
        transferService.transfer(request.getFromId(), request.getToId(), request.getAmount());

        return ResponseEntity.ok("Transfer successful");
    }

}

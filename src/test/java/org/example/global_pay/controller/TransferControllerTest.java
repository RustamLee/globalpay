package org.example.global_pay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.global_pay.domain.Account;
import org.example.global_pay.dto.TransferRequest;
import org.example.global_pay.exception.*;
import org.example.global_pay.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransferController.class)
public class TransferControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Should return 200 OK when transfer is successful")
    void shouldReturn200ForValidRequest() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("Transfer successful"));

        verify(transferService).transfer(request);
    }

    @Test
    @DisplayName("Should return 400 Bad Request when request is negative")
    void shouldReturn400ForNegativeAmount() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("-50.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("Should return 400 Bad Request when service throws InsufficientFundsException")
    void shouldReturn400ForInsufficientFunds() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("1000.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        doThrow(new InsufficientFundsException("Insufficient funds"))
                .when(transferService).transfer(any(TransferRequest.class));

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient funds"))
                .andExpect(jsonPath("$.error").value("Business Logic Error"));
        verify(transferService).transfer(request);
    }

    @Test
    @DisplayName("Should return 400 when fromId is missing")
    void shouldReturn400ForMissingFromId() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("fromId: From account ID is required")));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("Should return 400 when toId is missing")
    void shouldReturn400ForMissingToId() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("toId: To account ID is required")));

        verifyNoInteractions(transferService);
    }


    @Test
    @DisplayName("Should return 400 when service throws AccountNotFoundException")
    void shouldReturn400WhenAccountNotFound() throws Exception {

        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        doThrow(new AccountNotFoundException("Account not found"))
                .when(transferService).transfer(any(TransferRequest.class));

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Business Logic Error"))
                .andExpect(jsonPath("$.message").value("Account not found"));
        verify(transferService).transfer(any(TransferRequest.class));
    }

    @Test
    @DisplayName("Should return 400 when amount is Null")
    void shouldReturn400ForNullAmount() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("amount: Amount is required")));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("Should return 400 when Currency mismatch occurs in service")
    void shouldReturn400ForCurrencyMismatch() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        doThrow(new CurrencyMismatchException("Currency mismatch between accounts"))
                .when(transferService).transfer(any(TransferRequest.class));

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Business Logic Error"))
                .andExpect(jsonPath("$.message").value("Currency mismatch between accounts"));

        verify(transferService).transfer(request);

    }

    @Test
    @DisplayName("Should return 409 Conflict when Optimistic Locking failure occurs")
    void shouldReturn409WhenOptimisticLockingFailure() throws Exception {
        // GIVEN
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        doThrow(new ObjectOptimisticLockingFailureException(Account.class, request.getFromId()))
                .when(transferService).transfer(any(TransferRequest.class));

        // WHEN & THEN
        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict()) // Ждем 409
                .andExpect(jsonPath("$.error").value("Concurrency Conflict"))
                .andExpect(jsonPath("$.status").value(409));

        verify(transferService).transfer(any(TransferRequest.class));
    }

    @Test
    @DisplayName("Should return 500 Internal Server Error when unexpected exception occurs")
    void shouldReturn500WhenUnexpectedException() throws Exception {
        //GIVEN
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        doThrow(new RuntimeException("Database connection lost!"))
                .when(transferService).transfer(any(TransferRequest.class));

        //WHEN & THEN
        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError()) // Ждем 500
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("Something went wrong on our side"));
    }

    @Test
    @DisplayName("Should return 400 when JSON contains invalid UUID format")
    void shouldReturn400ForInvalidUuidFormat() throws Exception {
        // GIVEN:
        String invalidJson = """
                {
                    "fromId": "not-a-uuid",
                    "toId": "%s",
                    "amount": 100.00
                }
                """.formatted(UUID.randomUUID());

        // WHEN & THEN
        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.message").value(containsString("Invalid input format")));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("Should return 400 when amount is zero")
    void shouldReturn400ForZeroAmount() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("0.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("amount: Amount must be greater than zero")));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("Should return 400 with all validation messages when body is empty")
    void shouldReturn400ForEmptyBody() throws Exception {
        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message").value(containsString("fromId: From account ID is required")))
                .andExpect(jsonPath("$.message").value(containsString("toId: To account ID is required")))
                .andExpect(jsonPath("$.message").value(containsString("amount: Amount is required")));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("Should return 400 when fromId and toId are the same")
    void shouldReturn400ForSameFromAndToId() throws Exception {
        UUID accountId = UUID.randomUUID();
        TransferRequest request = TransferRequest.builder()
                .fromId(accountId)
                .toId(accountId)
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        doThrow(new SelfTransferException("Cannot transfer to the same account"))
                .when(transferService).transfer(any(TransferRequest.class));

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Business Logic Error"))
                .andExpect(jsonPath("$.message").value("Cannot transfer to the same account"));

        verify(transferService).transfer(request);
    }


    @Test
    @DisplayName("Should return 409 Conflict when idempotency key is already used")
    void shouldReturn409ForDuplicateRequest() throws Exception {
        TransferRequest request = TransferRequest.builder()
                .fromId(UUID.randomUUID())
                .toId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .idempotencyKey(UUID.randomUUID())
                .build();

        doThrow(new DuplicateRequestException("Duplicate request detected"))
                .when(transferService).transfer(any(TransferRequest.class));

        mockMvc.perform(post("/api/v1/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict()) // Проверяем 409
                .andExpect(jsonPath("$.message").value("Duplicate request detected"));
    }

    @Test
    @DisplayName("Should return 200 OK and paginated history")
    void shouldReturnHistory() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(transferService.getTransactions(eq(accountId), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/transfer/accounts/{accountId}/transactions", accountId))
                .andExpect(status().isOk());
    }

}

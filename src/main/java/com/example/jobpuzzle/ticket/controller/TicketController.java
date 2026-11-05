package com.example.jobpuzzle.ticket.controller;

import com.example.jobpuzzle.global.common.ApiResponse;
import com.example.jobpuzzle.ticket.dto.PaymentDtos;
import com.example.jobpuzzle.ticket.dto.TicketBalanceResponse;
import com.example.jobpuzzle.ticket.service.PaymentService;
import com.example.jobpuzzle.ticket.service.TicketService;
import com.example.jobpuzzle.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final PaymentService paymentService;

    // 잔액·무료 기본 면접 여부·모드별 차감 수·최근 이력
    @GetMapping("/tickets/me")
    public ResponseEntity<ApiResponse<TicketBalanceResponse>> getMyTickets(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(ticketService.getBalance(user.getUserId())));
    }

    @GetMapping("/tickets/products")
    public ResponseEntity<ApiResponse<PaymentDtos.ProductsResponse>> getProducts() {
        return ResponseEntity.ok(ApiResponse.success(paymentService.products()));
    }

    @PostMapping("/payments/orders")
    public ResponseEntity<ApiResponse<PaymentDtos.OrderResponse>> createOrder(
            @AuthenticationPrincipal(expression = "user") User user,
            @Valid @RequestBody PaymentDtos.OrderRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(paymentService.createOrder(user.getUserId(), request.productCode())));
    }

    // 토스 결제창 successUrl 화면에서 호출한다.
    @PostMapping("/payments/confirm")
    public ResponseEntity<ApiResponse<PaymentDtos.ConfirmResponse>> confirm(
            @AuthenticationPrincipal(expression = "user") User user,
            @Valid @RequestBody PaymentDtos.ConfirmRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(paymentService.confirm(user.getUserId(), request)));
    }
}

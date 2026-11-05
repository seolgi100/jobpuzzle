package com.example.jobpuzzle.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record Product(String code, String name, int tickets, int price) {
    }

    public record ProductsResponse(String clientKey, List<Product> products) {
    }

    public record OrderRequest(@NotBlank String productCode) {
    }

    public record OrderResponse(String orderId, String orderName, int amount, String clientKey) {
    }

    public record ConfirmRequest(@NotBlank String paymentKey, @NotBlank String orderId, @NotNull Integer amount) {
    }

    public record ConfirmResponse(int chargedTickets, int balance) {
    }
}

package com.example.jobpuzzle.ticket.dto;

import com.example.jobpuzzle.ticket.config.TicketProperties;
import com.example.jobpuzzle.ticket.entity.TicketTransaction;
import com.example.jobpuzzle.ticket.entity.TicketWallet;

import java.time.LocalDateTime;
import java.util.List;

public record TicketBalanceResponse(
        int balance,
        Costs costs,
        List<History> history
) {
    public record Costs(int basic, int companyFit) {
    }

    public record History(String type, String usage, int amount, int balanceAfter, LocalDateTime createdAt) {
    }

    public static TicketBalanceResponse of(TicketWallet wallet, TicketProperties properties, List<TicketTransaction> transactions) {
        return new TicketBalanceResponse(
                wallet.getBalance(),
                new Costs(properties.getBasicCost(), properties.getCompanyFitCost()),
                transactions.stream()
                        .map(t -> new History(t.getType().name(), t.getUsage() == null ? null : t.getUsage().name(),
                                t.getAmount(), t.getBalanceAfter(), t.getCreatedAt()))
                        .toList()
        );
    }
}

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
    // 평가 명세 v2 1장 티켓 표. 화면은 이 값만 보고 비용을 보여 준다 (하드코딩 금지).
    public record Costs(int analysis, int knowledge, int material, int personality,
                        int sessionRetry, int questionRegenerate) {
    }

    public record History(String type, String usage, int amount, int balanceAfter, LocalDateTime createdAt) {
    }

    public static TicketBalanceResponse of(TicketWallet wallet, TicketProperties properties, List<TicketTransaction> transactions) {
        return new TicketBalanceResponse(
                wallet.getBalance(),
                new Costs(properties.getAnalysisCost(), properties.getKnowledgeCost(), properties.getMaterialCost(),
                        properties.getPersonalityCost(), properties.getSessionRetryCost(),
                        properties.getQuestionRegenerateCost()),
                transactions.stream()
                        .map(t -> new History(t.getType().name(), t.getUsage() == null ? null : t.getUsage().name(),
                                t.getAmount(), t.getBalanceAfter(), t.getCreatedAt()))
                        .toList()
        );
    }
}

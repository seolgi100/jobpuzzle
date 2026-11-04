package com.example.jobpuzzle.ticket.entity;

import com.example.jobpuzzle.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 티켓 충전·사용·환불 이력. amount는 잔액 변화량(사용은 음수, 무료 사용은 0).
@Getter
@Entity
@NoArgsConstructor
@Table(name = "ticket_transaction")
public class TicketTransaction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long ticketTransactionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketTransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_usage", length = 30)
    private TicketUsage usage;

    @Column(nullable = false)
    private int amount;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "free_use", nullable = false)
    private boolean freeUse;

    // 충전은 결제 주문번호, 환불은 원래 사용 이력 id
    @Column(name = "reference_id", length = 64)
    private String referenceId;

    private TicketTransaction(Long userId, TicketTransactionType type, TicketUsage usage, int amount,
                              int balanceAfter, boolean freeUse, String referenceId) {
        this.userId = userId;
        this.type = type;
        this.usage = usage;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.freeUse = freeUse;
        this.referenceId = referenceId;
    }

    public static TicketTransaction charge(Long userId, int amount, int balanceAfter, String orderId) {
        return new TicketTransaction(userId, TicketTransactionType.CHARGE, null, amount, balanceAfter, false, orderId);
    }

    public static TicketTransaction use(Long userId, TicketUsage usage, int cost, int balanceAfter, boolean freeUse) {
        return new TicketTransaction(userId, TicketTransactionType.USE, usage, -cost, balanceAfter, freeUse, null);
    }

    public static TicketTransaction refund(TicketTransaction used, int balanceAfter) {
        return new TicketTransaction(used.userId, TicketTransactionType.REFUND, used.usage, -used.amount, balanceAfter,
                used.freeUse, String.valueOf(used.ticketTransactionId));
    }
}

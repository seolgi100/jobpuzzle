package com.example.jobpuzzle.ticket.entity;

import com.example.jobpuzzle.global.common.BaseEntity;
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

import java.time.LocalDateTime;
import java.util.UUID;

// 티켓 묶음 결제 주문. 결제 기록은 탈퇴 후에도 보관해야 하므로 user FK 없이 userId만 저장한다.
@Getter
@Entity
@NoArgsConstructor
@Table(name = "payment_order")
public class PaymentOrder extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long paymentOrderId;

    // 토스페이먼츠에 넘기는 주문번호(6~64자)
    @Column(name = "order_id", nullable = false, unique = true, length = 64)
    private String orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_code", nullable = false, length = 30)
    private String productCode;

    @Column(name = "order_name", nullable = false, length = 100)
    private String orderName;

    @Column(name = "ticket_count", nullable = false)
    private int ticketCount;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentOrderStatus status;

    @Column(name = "payment_key", length = 200)
    private String paymentKey;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    public PaymentOrder(Long userId, String productCode, String orderName, int ticketCount, int amount) {
        this.orderId = "jp-" + UUID.randomUUID();
        this.userId = userId;
        this.productCode = productCode;
        this.orderName = orderName;
        this.ticketCount = ticketCount;
        this.amount = amount;
        this.status = PaymentOrderStatus.READY;
    }

    public void paid(String paymentKey) {
        this.status = PaymentOrderStatus.PAID;
        this.paymentKey = paymentKey;
        this.approvedAt = LocalDateTime.now();
    }

    public void failed(String reason) {
        this.status = PaymentOrderStatus.FAILED;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }
}

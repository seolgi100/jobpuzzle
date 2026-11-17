package com.example.jobpuzzle.ticket.entity;

import com.example.jobpuzzle.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 회원별 티켓 잔액. 결제 기록 보존을 위해 user FK 없이 userId만 저장한다.
@Getter
@Entity
@NoArgsConstructor
@Table(name = "ticket_wallet")
public class TicketWallet extends BaseEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private int balance;

    // v1 "기본 면접 첫 1회 무료" 흔적. 무료는 없어졌지만 DB 칸(NOT NULL)이 남아 있어 필드만 둔다.
    @Column(name = "free_basic_used", nullable = false)
    private boolean freeBasicUsed;

    public TicketWallet(Long userId) {
        this.userId = userId;
    }

    public void add(int count) {
        balance += count;
    }

    public void subtract(int count) {
        balance -= count;
    }
}

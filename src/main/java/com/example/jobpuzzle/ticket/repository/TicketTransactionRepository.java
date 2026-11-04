package com.example.jobpuzzle.ticket.repository;

import com.example.jobpuzzle.ticket.entity.TicketTransaction;
import com.example.jobpuzzle.ticket.entity.TicketTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketTransactionRepository extends JpaRepository<TicketTransaction, Long> {

    boolean existsByTypeAndReferenceId(TicketTransactionType type, String referenceId);

    List<TicketTransaction> findTop20ByUserIdOrderByTicketTransactionIdDesc(Long userId);

    void deleteByUserId(Long userId);
}

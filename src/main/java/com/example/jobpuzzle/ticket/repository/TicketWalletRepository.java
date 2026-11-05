package com.example.jobpuzzle.ticket.repository;

import com.example.jobpuzzle.ticket.entity.TicketWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TicketWalletRepository extends JpaRepository<TicketWallet, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from TicketWallet w where w.userId = :userId")
    Optional<TicketWallet> findWithLockByUserId(Long userId);
}

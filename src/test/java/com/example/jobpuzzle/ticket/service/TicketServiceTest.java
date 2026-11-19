package com.example.jobpuzzle.ticket.service;

import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.ticket.config.TicketProperties;
import com.example.jobpuzzle.ticket.entity.TicketTransaction;
import com.example.jobpuzzle.ticket.entity.TicketUsage;
import com.example.jobpuzzle.ticket.entity.TicketWallet;
import com.example.jobpuzzle.ticket.repository.TicketTransactionRepository;
import com.example.jobpuzzle.ticket.repository.TicketWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketServiceTest {

    private final TicketWallet wallet = new TicketWallet(1L);
    private final List<TicketTransaction> saved = new ArrayList<>();
    private TicketService service;

    @BeforeEach
    void setUp() {
        TicketWalletRepository walletRepository = mock(TicketWalletRepository.class);
        TicketTransactionRepository transactionRepository = mock(TicketTransactionRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(walletRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(wallet));

        AtomicLong ids = new AtomicLong();
        when(transactionRepository.save(any())).thenAnswer(invocation -> {
            TicketTransaction transaction = invocation.getArgument(0);
            ReflectionTestUtils.setField(transaction, "ticketTransactionId", ids.incrementAndGet());
            saved.add(transaction);
            return transaction;
        });
        when(transactionRepository.findById(anyLong())).thenAnswer(invocation -> saved.stream()
                .filter(t -> t.getTicketTransactionId().equals(invocation.getArgument(0)))
                .findFirst());
        when(transactionRepository.existsByTypeAndReferenceId(any(), any())).thenAnswer(invocation -> saved.stream()
                .anyMatch(t -> t.getType() == invocation.getArgument(0)
                        && String.valueOf((Object) invocation.getArgument(1)).equals(t.getReferenceId())));

        service = new TicketService(walletRepository, transactionRepository, new TicketProperties(), transactionManager);
    }

    @Test
    void v2CostsFollowTheSpecTable() {
        assertThat(service.cost(TicketUsage.ANALYSIS)).isEqualTo(1);
        assertThat(service.cost(TicketUsage.KNOWLEDGE)).isEqualTo(1);
        assertThat(service.cost(TicketUsage.MATERIAL)).isEqualTo(3);
        assertThat(service.cost(TicketUsage.PERSONALITY)).isEqualTo(1);
        assertThat(service.cost(TicketUsage.SESSION_RETRY)).isEqualTo(1);
        assertThat(service.cost(TicketUsage.QUESTION_REGENERATE)).isEqualTo(2);
    }

    @Test
    void v2ModeIsChargedAndRefundedOnFinalFailure() {
        wallet.add(3);
        assertThatThrownBy(() -> service.spend(1L, TicketUsage.MATERIAL, service.cost(TicketUsage.MATERIAL), () -> {
            throw new IllegalStateException("AI 최종 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(wallet.getBalance()).isEqualTo(3);

        assertThat(service.spend(1L, TicketUsage.MATERIAL, service.cost(TicketUsage.MATERIAL), () -> "ok")).isEqualTo("ok");
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    void costsATicketFromTheFirstUse() {
        wallet.add(1);
        service.use(1L, TicketUsage.KNOWLEDGE, 1);
        assertThat(wallet.getBalance()).isZero();
        assertThat(saved.get(0).getAmount()).isEqualTo(-1);
        assertThat(saved.get(0).isFreeUse()).isFalse();
    }

    @Test
    void firstUseWithoutBalanceIsRejected() {
        assertThatThrownBy(() -> service.use(1L, TicketUsage.KNOWLEDGE, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_INSUFFICIENT);
    }

    @Test
    void insufficientBalanceIsRejected() {
        assertThatThrownBy(() -> service.use(1L, TicketUsage.MATERIAL, 3))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_INSUFFICIENT);
    }

    @Test
    void failedWorkRefundsOnceAndRethrows() {
        wallet.add(5);
        assertThatThrownBy(() -> service.spend(1L, TicketUsage.MATERIAL, 4, () -> {
            throw new IllegalStateException("AI 오류");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(wallet.getBalance()).isEqualTo(5);

        Long usedId = saved.get(0).getTicketTransactionId();
        service.refund(usedId);
        assertThat(wallet.getBalance()).isEqualTo(5);
    }
}

package com.example.jobpuzzle.ticket.service;

import com.example.jobpuzzle.document.entity.UserDocumentType;
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
    void companyFitCostGrowsWithCandidateMaterialKindsBetweenThreeAndFive() {
        assertThat(service.companyFitCost(List.of(UserDocumentType.JOB_POSTING, UserDocumentType.RESUME))).isEqualTo(3);
        assertThat(service.companyFitCost(List.of(UserDocumentType.RESUME, UserDocumentType.COVER_LETTER))).isEqualTo(4);
        assertThat(service.companyFitCost(List.of(UserDocumentType.RESUME, UserDocumentType.COVER_LETTER,
                UserDocumentType.PORTFOLIO, UserDocumentType.EXPERIENCE_NOTE))).isEqualTo(5);
    }

    @Test
    void firstBasicIsFreeThenCostsATicket() {
        wallet.add(1);
        service.use(1L, TicketUsage.BASIC, 1);
        assertThat(wallet.getBalance()).isEqualTo(1);
        assertThat(wallet.isFreeBasicUsed()).isTrue();

        service.use(1L, TicketUsage.BASIC, 1);
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    void insufficientBalanceIsRejected() {
        wallet.useFreeBasic();
        assertThatThrownBy(() -> service.use(1L, TicketUsage.COMPANY_FIT, 3))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_INSUFFICIENT);
    }

    @Test
    void failedWorkRefundsOnceAndRethrows() {
        wallet.add(5);
        assertThatThrownBy(() -> service.spend(1L, TicketUsage.COMPANY_FIT, 4, () -> {
            throw new IllegalStateException("AI 오류");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(wallet.getBalance()).isEqualTo(5);

        Long usedId = saved.get(0).getTicketTransactionId();
        service.refund(usedId);
        assertThat(wallet.getBalance()).isEqualTo(5);
    }

    @Test
    void failedFreeBasicRestoresTheFreeUse() {
        assertThatThrownBy(() -> service.spend(1L, TicketUsage.BASIC, 1, () -> {
            throw new IllegalStateException("AI 오류");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(wallet.isFreeBasicUsed()).isFalse();
    }
}

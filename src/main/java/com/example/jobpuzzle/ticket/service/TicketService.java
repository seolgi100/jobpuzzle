package com.example.jobpuzzle.ticket.service;

import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.ticket.config.TicketProperties;
import com.example.jobpuzzle.ticket.dto.TicketBalanceResponse;
import com.example.jobpuzzle.ticket.entity.TicketTransaction;
import com.example.jobpuzzle.ticket.entity.TicketTransactionType;
import com.example.jobpuzzle.ticket.entity.TicketUsage;
import com.example.jobpuzzle.ticket.entity.TicketWallet;
import com.example.jobpuzzle.ticket.repository.TicketTransactionRepository;
import com.example.jobpuzzle.ticket.repository.TicketWalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 티켓 차감·환불·충전. 차감과 환불은 AI 작업 트랜잭션과 따로 커밋해야 하므로(작업이 실패해 롤백돼도 환불은 남아야 함)
 * REQUIRES_NEW 트랜잭션 템플릿으로 실행한다.
 */
@Service
public class TicketService {

    private static final Set<UserDocumentType> CANDIDATE_TYPES = EnumSet.of(
            UserDocumentType.RESUME, UserDocumentType.COVER_LETTER,
            UserDocumentType.PORTFOLIO, UserDocumentType.EXPERIENCE_NOTE);

    private final TicketWalletRepository walletRepository;
    private final TicketTransactionRepository transactionRepository;
    private final TicketProperties properties;
    private final TransactionTemplate newTransaction;

    public TicketService(TicketWalletRepository walletRepository, TicketTransactionRepository transactionRepository,
                         TicketProperties properties, PlatformTransactionManager transactionManager) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.properties = properties;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int cost(TicketUsage usage) {
        return switch (usage) {
            case BASIC -> properties.getBasicCost();
            case WEAKNESS_REVIEW -> properties.getWeaknessCost();
            case COMPANY_FIT -> properties.getCompanyFitMinCost();
        };
    }

    // 공고 맞춤 분석: 지원자 자료 종류 수만큼 AI 분석이 늘어나므로 1종이면 최소값, 1종 늘 때마다 +1, 최대값까지.
    public int companyFitCost(Collection<UserDocumentType> sourceTypes) {
        long candidateKinds = sourceTypes.stream().filter(CANDIDATE_TYPES::contains).distinct().count();
        int cost = properties.getCompanyFitMinCost() + (int) Math.max(0, candidateKinds - 1);
        return Math.min(cost, properties.getCompanyFitMaxCost());
    }

    /** 티켓을 먼저 차감하고 작업을 실행한다. 작업이 실패하면(AI 오류 등) 차감한 티켓을 돌려주고 예외를 그대로 던진다. */
    public <T> T spend(Long userId, TicketUsage usage, int cost, Supplier<T> work) {
        Long usedId = use(userId, usage, cost);
        try {
            return work.get();
        } catch (RuntimeException exception) {
            refund(usedId);
            throw exception;
        }
    }

    // 실행 전에 잔액만 확인한다(차감은 하지 않음). 상태를 바꾸기 전에 부족 여부를 먼저 알려 주려는 용도.
    @Transactional(readOnly = true)
    public void requireBalance(Long userId, int cost) {
        int balance = walletRepository.findById(userId).map(TicketWallet::getBalance).orElse(0);
        if (balance < cost) {
            throw insufficient(cost, balance);
        }
    }

    public Long use(Long userId, TicketUsage usage, int cost) {
        return newTransaction.execute(status -> {
            TicketWallet wallet = lockWallet(userId);
            boolean free = usage == TicketUsage.BASIC && !wallet.isFreeBasicUsed();
            if (free) {
                wallet.useFreeBasic();
            } else {
                if (wallet.getBalance() < cost) {
                    throw insufficient(cost, wallet.getBalance());
                }
                wallet.subtract(cost);
            }
            return transactionRepository.save(TicketTransaction.use(
                    userId, usage, free ? 0 : cost, wallet.getBalance(), free)).getTicketTransactionId();
        });
    }

    // 같은 사용 건은 한 번만 환불한다.
    public void refund(Long usedTransactionId) {
        newTransaction.executeWithoutResult(status -> {
            TicketTransaction used = transactionRepository.findById(usedTransactionId).orElseThrow();
            if (used.getType() != TicketTransactionType.USE || transactionRepository
                    .existsByTypeAndReferenceId(TicketTransactionType.REFUND, String.valueOf(usedTransactionId))) {
                return;
            }
            TicketWallet wallet = lockWallet(used.getUserId());
            if (used.isFreeUse()) {
                wallet.restoreFreeBasic();
            } else {
                wallet.add(-used.getAmount());
            }
            transactionRepository.save(TicketTransaction.refund(used, wallet.getBalance()));
        });
    }

    // 결제 승인 트랜잭션에 참여해 주문 상태 변경과 함께 커밋된다.
    @Transactional
    public int charge(Long userId, int tickets, String orderId) {
        TicketWallet wallet = lockWallet(userId);
        wallet.add(tickets);
        transactionRepository.save(TicketTransaction.charge(userId, tickets, wallet.getBalance(), orderId));
        return wallet.getBalance();
    }

    @Transactional(readOnly = true)
    public TicketBalanceResponse getBalance(Long userId) {
        TicketWallet wallet = walletRepository.findById(userId).orElse(new TicketWallet(userId));
        return TicketBalanceResponse.of(wallet, properties,
                transactionRepository.findTop20ByUserIdOrderByTicketTransactionIdDesc(userId));
    }

    @Transactional
    public void deleteAllByUser(Long userId) {
        transactionRepository.deleteByUserId(userId);
        walletRepository.deleteById(userId);
    }

    // ponytail: 첫 사용 시 동시 요청 두 개가 지갑을 같이 만들면 한쪽이 PK 중복으로 실패한다. 드물어서 재시도로 충분.
    private TicketWallet lockWallet(Long userId) {
        return walletRepository.findWithLockByUserId(userId)
                .orElseGet(() -> walletRepository.saveAndFlush(new TicketWallet(userId)));
    }

    private CustomException insufficient(int cost, int balance) {
        return new CustomException(ErrorCode.TICKET_INSUFFICIENT,
                "티켓이 부족합니다. (필요 " + cost + "장 / 보유 " + balance + "장) 상단 메뉴의 티켓 충전에서 충전해 주세요.");
    }
}

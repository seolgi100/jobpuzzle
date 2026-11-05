package com.example.jobpuzzle.ticket.service;

import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.ticket.config.TicketProperties;
import com.example.jobpuzzle.ticket.dto.PaymentDtos;
import com.example.jobpuzzle.ticket.entity.PaymentOrder;
import com.example.jobpuzzle.ticket.entity.PaymentOrderStatus;
import com.example.jobpuzzle.ticket.repository.PaymentOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** 티켓 묶음 결제(토스페이먼츠). 주문 생성 → 결제창 → successUrl에서 승인 요청 → 금액 검증 후 승인 API 호출 → 티켓 충전. */
@Slf4j
@Service
public class PaymentService {

    private final PaymentOrderRepository orderRepository;
    private final TicketService ticketService;
    private final TicketProperties properties;
    private final RestClient tossRestClient;

    public PaymentService(PaymentOrderRepository orderRepository, TicketService ticketService,
                          TicketProperties properties, @Qualifier("tossRestClient") RestClient tossRestClient) {
        this.orderRepository = orderRepository;
        this.ticketService = ticketService;
        this.properties = properties;
        this.tossRestClient = tossRestClient;
    }

    public PaymentDtos.ProductsResponse products() {
        return new PaymentDtos.ProductsResponse(
                properties.getToss().getClientKey(),
                properties.getProducts().stream()
                        .map(p -> new PaymentDtos.Product(p.getCode(), p.getName(), p.getTickets(), p.getPrice()))
                        .toList());
    }

    @Transactional
    public PaymentDtos.OrderResponse createOrder(Long userId, String productCode) {
        requireConfigured();
        TicketProperties.Product product = properties.getProducts().stream()
                .filter(p -> p.getCode().equals(productCode))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_REQUEST, "판매하지 않는 상품입니다."));
        // 금액은 클라이언트가 아니라 서버 설정값으로 주문에 고정한다.
        PaymentOrder order = orderRepository.save(new PaymentOrder(
                userId, product.getCode(), product.getName(), product.getTickets(), product.getPrice()));
        return new PaymentDtos.OrderResponse(order.getOrderId(), order.getOrderName(), order.getAmount(),
                properties.getToss().getClientKey());
    }

    // 실패도 주문 상태로 남겨야 하므로 CustomException에는 롤백하지 않는다(충전은 승인 성공 뒤에만 일어남).
    @Transactional(noRollbackFor = CustomException.class)
    public PaymentDtos.ConfirmResponse confirm(Long userId, PaymentDtos.ConfirmRequest request) {
        requireConfigured();
        PaymentOrder order = orderRepository.findWithLockByOrderId(request.orderId())
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        // successUrl 새로고침 등으로 같은 승인이 다시 와도 한 번만 충전한다.
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            if (!order.getPaymentKey().equals(request.paymentKey())) {
                throw new CustomException(ErrorCode.PAYMENT_ORDER_NOT_FOUND);
            }
            return new PaymentDtos.ConfirmResponse(order.getTicketCount(), ticketService.getBalance(userId).balance());
        }
        if (order.getStatus() == PaymentOrderStatus.FAILED) {
            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED, "이미 실패한 주문입니다. 다시 결제해 주세요.");
        }
        // 결제창에서 넘어온 금액이 주문 금액과 다르면 승인하지 않는다(금액 위변조 방지).
        if (request.amount() != order.getAmount()) {
            order.failed("금액 불일치: 요청 " + request.amount() + " / 주문 " + order.getAmount());
            throw new CustomException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        callConfirmApi(order, request.paymentKey());
        order.paid(request.paymentKey());
        int balance = ticketService.charge(userId, order.getTicketCount(), order.getOrderId());
        return new PaymentDtos.ConfirmResponse(order.getTicketCount(), balance);
    }

    // ponytail: 토스 승인 성공 뒤 DB 커밋이 실패하면 결제만 되고 충전이 안 된다. 그런 주문은 토스 콘솔에서 취소하거나,
    // 필요해지면 승인 결과를 먼저 저장하는 별도 트랜잭션으로 나눈다.
    private void callConfirmApi(PaymentOrder order, String paymentKey) {
        String secret = properties.getToss().getSecretKey() + ":";
        String auth = "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        try {
            tossRestClient.post()
                    .uri("/v1/payments/confirm")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .header("Idempotency-Key", order.getOrderId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentKey", paymentKey, "orderId", order.getOrderId(), "amount", order.getAmount()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            String message = tossMessage(exception);
            log.warn("토스 결제 승인 실패 orderId={} status={} body={}", order.getOrderId(),
                    exception.getStatusCode(), exception.getResponseBodyAsString());
            order.failed(message);
            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED, message);
        } catch (RestClientException exception) {
            // 응답을 못 받은 경우 승인 여부를 알 수 없으므로 주문을 READY로 두고 다시 시도하게 한다(Idempotency-Key로 중복 승인 방지).
            log.warn("토스 결제 승인 응답 없음 orderId={}", order.getOrderId(), exception);
            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED, "결제 승인 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    @SuppressWarnings("unchecked")
    private String tossMessage(RestClientResponseException exception) {
        try {
            Map<String, Object> body = exception.getResponseBodyAs(Map.class);
            if (body != null && body.get("message") != null) {
                return String.valueOf(body.get("message"));
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아니면 기본 문구를 쓴다.
        }
        return ErrorCode.PAYMENT_CONFIRM_FAILED.getMessage();
    }

    private void requireConfigured() {
        TicketProperties.Toss toss = properties.getToss();
        if (toss.getClientKey() == null || toss.getClientKey().isBlank()
                || toss.getSecretKey() == null || toss.getSecretKey().isBlank()) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIGURED);
        }
    }
}

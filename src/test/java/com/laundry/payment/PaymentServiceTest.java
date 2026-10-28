package com.laundry.payment;

import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.payment.dto.request.CreatePaymentRequest;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.exception.PaymentException;
import com.laundry.payment.gateway.GatewayPaymentStatus;
import com.laundry.payment.gateway.PaymentGateway;
import com.laundry.payment.gateway.payos.PayOSProperties;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.payment.service.PaymentService;
import com.laundry.payment.service.WalletPaymentService;
import com.laundry.shared.money.Money;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.entity.TransactionStatus;
import com.laundry.transaction.service.TransactionQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {
    @Test
    void rejectsPaymentAmountThatDoesNotMatchTransaction() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentGateway paymentGateway = mock(PaymentGateway.class);
        TransactionQueryService transactionQueries = mock(TransactionQueryService.class);
        ScheduledBookingService bookingService = mock(ScheduledBookingService.class);
        PaymentService paymentService = new PaymentService(
                paymentRepository, paymentGateway, transactionQueries, noWallet(), bookingService, PayOSProperties.withChecksumKey(""));
        UUID transactionId = UUID.randomUUID();
        when(transactionQueries.getTransaction(transactionId)).thenReturn(TransactionResponse.builder()
                .transactionId(transactionId)
                .status(TransactionStatus.PENDING_PAYMENT)
                .totalAmount(new BigDecimal("25000"))
                .build());
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .transactionId(transactionId)
                .amount(new BigDecimal("24000"))
                .paymentMethod(PaymentMethod.ONLINE)
                .build();

        assertThatThrownBy(() -> paymentService.createPayment(request))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("does not match the amount still due");
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void simulatesOnlinePaymentForOwningCustomerAndConfirmsTransaction() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        TransactionQueryService transactionQueries = mock(TransactionQueryService.class);
        ScheduledBookingService bookingService = mock(ScheduledBookingService.class);
        PaymentService paymentService = new PaymentService(
                paymentRepository, mock(PaymentGateway.class), transactionQueries, noWallet(), bookingService, PayOSProperties.withChecksumKey(""));
        UUID paymentId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .transactionId(transactionId)
                .amount(new BigDecimal("25000"))
                .paymentMethod(PaymentMethod.ONLINE)
                .status(PaymentStatus.PENDING)
                .build();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(transactionQueries.getTransaction(transactionId)).thenReturn(TransactionResponse.builder()
                .transactionId(transactionId)
                .customerId(customerId)
                .build());
        var response = paymentService.simulatePaymentSuccessForCustomer(paymentId, customerId);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.getGatewayTransactionId()).startsWith("LOCAL_SIM_");
        verify(paymentRepository).save(payment);
        verify(bookingService).confirmPayment(transactionId);
    }

    @Test
    void rejectsSimulationByAnotherCustomer() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        TransactionQueryService transactionQueries = mock(TransactionQueryService.class);
        ScheduledBookingService bookingService = mock(ScheduledBookingService.class);
        PaymentService paymentService = new PaymentService(
                paymentRepository, mock(PaymentGateway.class), transactionQueries, noWallet(), bookingService, PayOSProperties.withChecksumKey(""));
        UUID paymentId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .transactionId(transactionId)
                .paymentMethod(PaymentMethod.ONLINE)
                .status(PaymentStatus.PENDING)
                .build();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(transactionQueries.getTransaction(transactionId)).thenReturn(TransactionResponse.builder()
                .transactionId(transactionId)
                .customerId(UUID.randomUUID())
                .build());

        assertThatThrownBy(() -> paymentService.simulatePaymentSuccessForCustomer(paymentId, UUID.randomUUID()))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("does not belong");
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(bookingService, never()).confirmPayment(transactionId);
    }

    @Test
    void reconcilesSuccessfulPayOSPaymentAndConfirmsScheduledBooking() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentGateway paymentGateway = mock(PaymentGateway.class);
        TransactionQueryService transactionQueries = mock(TransactionQueryService.class);
        ScheduledBookingService bookingService = mock(ScheduledBookingService.class);
        PaymentService paymentService = new PaymentService(
                paymentRepository, paymentGateway, transactionQueries, noWallet(), bookingService, PayOSProperties.withChecksumKey(""));
        UUID paymentId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .transactionId(transactionId)
                .amount(new BigDecimal("25000"))
                .paymentMethod(PaymentMethod.ONLINE)
                .status(PaymentStatus.PENDING)
                .gatewayTransactionId("123456789")
                .build();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(transactionQueries.getTransaction(transactionId)).thenReturn(TransactionResponse.builder()
                .transactionId(transactionId)
                .customerId(customerId)
                .build());
        when(paymentGateway.getPaymentStatus("123456789")).thenReturn(Optional.of(
                new GatewayPaymentStatus("123456789", "PAID", new BigDecimal("25000"))));
        when(bookingService.hasScheduledBooking(transactionId)).thenReturn(true);

        var response = paymentService.refreshPayOSStatusForCustomer(paymentId, customerId);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(paymentRepository).save(payment);
        verify(bookingService).confirmPayment(transactionId);
    }

    @Test
    void rejectsPayOSStatusWithMismatchedAmount() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentGateway paymentGateway = mock(PaymentGateway.class);
        TransactionQueryService transactionQueries = mock(TransactionQueryService.class);
        PaymentService paymentService = new PaymentService(
                paymentRepository, paymentGateway, transactionQueries, noWallet(), mock(ScheduledBookingService.class), PayOSProperties.withChecksumKey(""));
        UUID paymentId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .transactionId(transactionId)
                .amount(new BigDecimal("25000"))
                .status(PaymentStatus.PENDING)
                .gatewayTransactionId("123456789")
                .build();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(transactionQueries.getTransaction(transactionId)).thenReturn(TransactionResponse.builder()
                .transactionId(transactionId)
                .customerId(customerId)
                .build());
        when(paymentGateway.getPaymentStatus("123456789")).thenReturn(Optional.of(
                new GatewayPaymentStatus("123456789", "PAID", new BigDecimal("24000"))));

        assertThatThrownBy(() -> paymentService.refreshPayOSStatusForCustomer(paymentId, customerId))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("does not match");
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    /** No wallet balance: the whole amount is due through payOS. */
    private static WalletPaymentService noWallet() {
        WalletPaymentService walletPayments = mock(WalletPaymentService.class);
        when(walletPayments.activeWalletPart(org.mockito.ArgumentMatchers.any())).thenReturn(Money.ZERO);
        when(walletPayments.walletPortion(org.mockito.ArgumentMatchers.any())).thenReturn(Money.ZERO);
        return walletPayments;
    }
}

package com.seatly.payment;

import com.seatly.account.CurrentAccount;
import com.seatly.booking.Booking;
import com.seatly.booking.BookingRepository;
import com.seatly.booking.BookingStatus;
import com.seatly.booking.SeatUnavailableException;
import com.seatly.common.NotFoundException;
import com.seatly.payment.view.PaymentView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * Starts payments for held bookings.
 */
@Service
public class PaymentService {

	private final BookingRepository bookings;
	private final PaymentRepository payments;
	private final CurrentAccount currentAccount;
	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	public PaymentService(BookingRepository bookings, PaymentRepository payments,
			CurrentAccount currentAccount, Clock clock) {
		this.bookings = bookings;
		this.payments = payments;
		this.currentAccount = currentAccount;
		this.clock = clock;
	}

	/**
	 * Opens a payment for a booking that is still held.
	 * <p>
	 * The amount comes from the booking, never from the request: a caller who
	 * could name the amount could name a smaller one. Calling this twice for the
	 * same booking returns the attempt that is already open rather than a second
	 * one, which is what the partial unique index enforces underneath.
	 */
	@Transactional
	public PaymentView startPayment(String reference) {
		Booking booking = bookings.findByReference(reference)
				.orElseThrow(() -> NotFoundException.of("Booking", reference));

		if (!booking.belongsTo(currentAccount.id())) {
			throw NotFoundException.of("Booking", reference);
		}
		if (booking.getStatus() != BookingStatus.PENDING) {
			throw new SeatUnavailableException(
					"This booking is " + booking.getStatus().name().toLowerCase());
		}
		if (booking.hasLapsedBy(clock.instant())) {
			throw new SeatUnavailableException("This hold has expired");
		}

		Payment payment = payments
				.findFirstByBookingIdAndStatusOrderByIdDesc(booking.getId(), PaymentStatus.REQUIRES_PAYMENT)
				.orElseGet(() -> payments.save(new Payment(booking, newProviderReference())));

		return PaymentView.of(payment);
	}

	@Transactional(readOnly = true)
	public PaymentView byReference(String providerReference) {
		Payment payment = payments.findByProviderReference(providerReference)
				.orElseThrow(() -> NotFoundException.of("Payment", providerReference));

		if (!payment.getBooking().belongsTo(currentAccount.id())) {
			throw NotFoundException.of("Payment", providerReference);
		}
		return PaymentView.of(payment);
	}

	/** Shaped like a provider's own identifier, because that is what it stands in for. */
	private String newProviderReference() {
		byte[] bytes = new byte[15];
		random.nextBytes(bytes);
		return "pay_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}

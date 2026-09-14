package com.grandvista.bookingservice.service;

import com.grandvista.bookingservice.dto.BookingResponse;
import com.grandvista.bookingservice.dto.CreateBookingRequest;
import com.grandvista.bookingservice.dto.PayBookingRequest;
import com.grandvista.bookingservice.dto.PaymentOutcomeResponse;
import com.grandvista.bookingservice.entity.BookingStatus;
import com.grandvista.bookingservice.util.AuthenticatedUser;

import java.util.List;

public interface BookingService {

    /** Step 1: validates, reserves the room through Room Service and stores a PENDING booking. */
    BookingResponse createBooking(CreateBookingRequest request, AuthenticatedUser user);

    /** Step 2: Booking -> Payment -> Room confirmation (or release/refund on failure). */
    PaymentOutcomeResponse payForBooking(Long bookingId, PayBookingRequest request, AuthenticatedUser user);

    BookingResponse cancelBooking(Long bookingId, AuthenticatedUser user);

    BookingResponse getBooking(Long bookingId, AuthenticatedUser user);

    List<BookingResponse> myBookings(AuthenticatedUser user);

    List<BookingResponse> allBookings(BookingStatus status);

    int cancelExpiredPendingBookings();
}

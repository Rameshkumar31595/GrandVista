package com.grandvista.bookingservice.dto;

import com.grandvista.bookingservice.entity.Booking;
import com.grandvista.bookingservice.entity.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record BookingResponse(
        Long bookingId,
        Long userId,
        Long roomId,
        Long hotelId,
        String hotelName,
        String roomNumber,
        String roomType,
        LocalDate checkIn,
        LocalDate checkOut,
        long nights,
        int numberOfGuests,
        BigDecimal totalAmount,
        String currency,
        BookingStatus bookingStatus,
        String statusReason,
        Instant paymentDueBy,
        Long paymentId,
        String paymentReference,
        Instant createdAt,
        Instant updatedAt
) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getUserId(), booking.getRoomId(), booking.getHotelId(),
                booking.getHotelName(), booking.getRoomNumber(), booking.getRoomType(), booking.getCheckIn(),
                booking.getCheckOut(), ChronoUnit.DAYS.between(booking.getCheckIn(), booking.getCheckOut()),
                booking.getNumberOfGuests(), booking.getTotalAmount(), "INR", booking.getStatus(),
                booking.getStatusReason(), booking.getHoldExpiresAt(), booking.getPaymentId(),
                booking.getPaymentReference(), booking.getCreatedAt(), booking.getUpdatedAt());
    }
}

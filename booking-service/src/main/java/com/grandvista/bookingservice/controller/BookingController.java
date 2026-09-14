package com.grandvista.bookingservice.controller;

import com.grandvista.bookingservice.dto.BookingResponse;
import com.grandvista.bookingservice.dto.CreateBookingRequest;
import com.grandvista.bookingservice.dto.PayBookingRequest;
import com.grandvista.bookingservice.dto.PaymentOutcomeResponse;
import com.grandvista.bookingservice.entity.BookingStatus;
import com.grandvista.bookingservice.service.BookingService;
import com.grandvista.bookingservice.util.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/bookings")
@Tag(name = "Bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reserve a room (creates a PENDING booking that must be paid before paymentDueBy)")
    public BookingResponse createBooking(@Valid @RequestBody CreateBookingRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return bookingService.createBooking(request, AuthenticatedUser.from(jwt));
    }

    @PostMapping("/{bookingId}/pay")
    @Operation(summary = "Pay for a PENDING booking: Booking -> Payment -> Room confirmation")
    public PaymentOutcomeResponse payForBooking(@PathVariable Long bookingId,
                                                @Valid @RequestBody PayBookingRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return bookingService.payForBooking(bookingId, request, AuthenticatedUser.from(jwt));
    }

    @PostMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking (owner or ADMIN); confirmed bookings are refunded")
    public BookingResponse cancelBooking(@PathVariable Long bookingId, @AuthenticationPrincipal Jwt jwt) {
        return bookingService.cancelBooking(bookingId, AuthenticatedUser.from(jwt));
    }

    @GetMapping("/my")
    @Operation(summary = "Bookings of the logged-in user")
    public List<BookingResponse> myBookings(@AuthenticationPrincipal Jwt jwt) {
        return bookingService.myBookings(AuthenticatedUser.from(jwt));
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Booking details (owner or ADMIN)")
    public BookingResponse getBooking(@PathVariable Long bookingId, @AuthenticationPrincipal Jwt jwt) {
        return bookingService.getBooking(bookingId, AuthenticatedUser.from(jwt));
    }

    @GetMapping
    @Operation(summary = "All bookings, optionally filtered by status (ADMIN)")
    public List<BookingResponse> allBookings(@RequestParam(required = false) BookingStatus status) {
        return bookingService.allBookings(status);
    }
}

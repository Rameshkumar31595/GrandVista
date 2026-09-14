package com.grandvista.roomservice.controller;

import com.grandvista.roomservice.dto.HotelRequest;
import com.grandvista.roomservice.dto.HotelResponse;
import com.grandvista.roomservice.service.HotelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/hotels")
@Tag(name = "Hotels")
public class HotelController {

    private final HotelService hotelService;

    public HotelController(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    @GetMapping
    @Operation(summary = "List active hotels (public)")
    public List<HotelResponse> listHotels() {
        return hotelService.listActiveHotels();
    }

    @GetMapping("/{hotelId}")
    @Operation(summary = "Hotel details (public)")
    public HotelResponse getHotel(@PathVariable Long hotelId) {
        return hotelService.getHotel(hotelId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a hotel (ADMIN)")
    public HotelResponse createHotel(@Valid @RequestBody HotelRequest request) {
        return hotelService.createHotel(request);
    }

    @PutMapping("/{hotelId}")
    @Operation(summary = "Update a hotel (ADMIN)")
    public HotelResponse updateHotel(@PathVariable Long hotelId, @Valid @RequestBody HotelRequest request) {
        return hotelService.updateHotel(hotelId, request);
    }
}

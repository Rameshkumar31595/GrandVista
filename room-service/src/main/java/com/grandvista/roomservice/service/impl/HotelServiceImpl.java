package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.dto.HotelRequest;
import com.grandvista.roomservice.dto.HotelResponse;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.exception.BusinessRuleException;
import com.grandvista.roomservice.exception.ResourceNotFoundException;
import com.grandvista.roomservice.repository.HotelRepository;
import com.grandvista.roomservice.service.HotelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HotelServiceImpl implements HotelService {

    private static final Logger log = LoggerFactory.getLogger(HotelServiceImpl.class);

    private final HotelRepository hotelRepository;

    public HotelServiceImpl(HotelRepository hotelRepository) {
        this.hotelRepository = hotelRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HotelResponse> listActiveHotels() {
        return hotelRepository.findByActiveTrueOrderByCityAscNameAsc().stream().map(HotelResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public HotelResponse getHotel(Long hotelId) {
        return HotelResponse.from(findHotel(hotelId));
    }

    @Override
    @Transactional
    public HotelResponse createHotel(HotelRequest request) {
        if (hotelRepository.existsByNameIgnoreCaseAndCityIgnoreCase(request.name().trim(), request.city().trim())) {
            throw new BusinessRuleException("Hotel '" + request.name() + "' already exists in " + request.city());
        }
        Hotel hotel = hotelRepository.save(new Hotel(request.name().trim(), request.city().trim(), request.state().trim(),
                request.country().trim(), request.address().trim(), request.description(), request.starRating()));
        log.info("Hotel id={} '{}' created in {}", hotel.getId(), hotel.getName(), hotel.getCity());
        return HotelResponse.from(hotel);
    }

    @Override
    @Transactional
    public HotelResponse updateHotel(Long hotelId, HotelRequest request) {
        Hotel hotel = findHotel(hotelId);
        hotel.update(request.name().trim(), request.city().trim(), request.state().trim(), request.country().trim(),
                request.address().trim(), request.description(), request.starRating());
        log.info("Hotel id={} updated", hotelId);
        return HotelResponse.from(hotel);
    }

    private Hotel findHotel(Long hotelId) {
        return hotelRepository.findById(hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Hotel " + hotelId + " not found"));
    }
}

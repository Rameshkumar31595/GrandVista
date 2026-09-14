package com.grandvista.roomservice.repository;

import com.grandvista.roomservice.entity.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HotelRepository extends JpaRepository<Hotel, Long> {

    List<Hotel> findByActiveTrueOrderByCityAscNameAsc();

    boolean existsByNameIgnoreCaseAndCityIgnoreCase(String name, String city);
}

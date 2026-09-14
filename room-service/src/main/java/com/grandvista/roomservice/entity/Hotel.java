package com.grandvista.roomservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "hotels", uniqueConstraints = @UniqueConstraint(name = "uk_hotels_name_city", columnNames = {"name", "city"}))
public class Hotel extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 80)
    private String city;

    @Column(nullable = false, length = 80)
    private String state;

    @Column(nullable = false, length = 80)
    private String country;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(length = 1000)
    private String description;

    @Column(name = "star_rating", nullable = false)
    private int starRating;

    @Column(nullable = false)
    private boolean active = true;

    protected Hotel() {
        // required by JPA
    }

    public Hotel(String name, String city, String state, String country, String address,
                 String description, int starRating) {
        update(name, city, state, country, address, description, starRating);
    }

    public final void update(String name, String city, String state, String country, String address,
                             String description, int starRating) {
        this.name = name;
        this.city = city;
        this.state = state;
        this.country = country;
        this.address = address;
        this.description = description;
        this.starRating = starRating;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getCountry() {
        return country;
    }

    public String getAddress() {
        return address;
    }

    public String getDescription() {
        return description;
    }

    public int getStarRating() {
        return starRating;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}

package com.sabayride.rental.booking;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * THE SNAPSHOT. brand, model, pricePerDay and deliveryFee are COPIED here at
 * creation and never updated.
 *
 * When a shop raises its price next month, this booking's history stays true.
 * Without the copy, every past receipt silently rewrites itself and the revenue
 * report stops matching the bank.
 */
@Entity
@Table(name = "booking_item")
public class BookingItem {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "brand", nullable = false, length = 60)
    private String brand;

    @Column(name = "model", nullable = false, length = 120)
    private String model;

    @Column(name = "price_per_day", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerDay;

    @Column(name = "rental_days", nullable = false)
    private short rentalDays;

    @Column(name = "subtotal", nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "delivery_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    /** Shop and admin only. NEVER in a customer-facing response. */
    @Column(name = "assigned_plate_number", nullable = false, length = 20)
    private String assignedPlateNumber;

    @Column(name = "actual_motorbike_id")
    private UUID actualMotorbikeId;

    @Column(name = "actual_plate_number", length = 20)
    private String actualPlateNumber;

    protected BookingItem() { }

    public static BookingItem snapshotOf(UUID bookingId,
                                         com.sabayride.rental.fleet.Motorbike unit,
                                         int rentalDays, BigDecimal deliveryFee) {
        BookingItem i = new BookingItem();
        i.bookingId = bookingId;
        i.brand = unit.getBrand();
        i.model = unit.getModel();
        i.pricePerDay = unit.getPricePerDay();
        i.rentalDays = (short) rentalDays;
        i.subtotal = unit.getPricePerDay().multiply(BigDecimal.valueOf(rentalDays));
        i.deliveryFee = deliveryFee;
        i.assignedPlateNumber = unit.getPlateNumber();
        return i;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public String getBrand() { return brand; }
    public String getModel() { return model; }
    public BigDecimal getPricePerDay() { return pricePerDay; }
    public short getRentalDays() { return rentalDays; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public String getAssignedPlateNumber() { return assignedPlateNumber; }
    public UUID getActualMotorbikeId() { return actualMotorbikeId; }
    public void setActualMotorbikeId(UUID v) { this.actualMotorbikeId = v; }
    public String getActualPlateNumber() { return actualPlateNumber; }
    public void setActualPlateNumber(String v) { this.actualPlateNumber = v; }
}

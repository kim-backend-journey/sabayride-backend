package com.sabayride.rental.fleet;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * ONE ROW PER PHYSICAL BIKE. Five Honda Dreams are five rows with five plates.
 *
 * These are the rows the booking transaction locks with SELECT ... FOR UPDATE.
 * You cannot lock a model, only rows — which is why a concrete unit is assigned
 * at booking creation rather than at pickup.
 */
@Entity
@Table(name = "motorbike")
public class Motorbike {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "brand", nullable = false, length = 60)
    private String brand;

    /**
     * Units sharing this string group into ONE search result, so spelling
     * matters: "Honda Dream 125" and "honda dream125" would appear as two
     * separate products. Normalise on write.
     */
    @Column(name = "model", nullable = false, length = 120)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private MotorbikeType type;

    /** Unique within the shop. NEVER returned to a customer. */
    @Column(name = "plate_number", nullable = false, length = 20)
    private String plateNumber;

    @Column(name = "price_per_day", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerDay;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MotorbikeStatus status = MotorbikeStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Motorbike() { }

    public static Motorbike of(UUID shopId, String brand, String model,
                               MotorbikeType type, String plateNumber,
                               BigDecimal pricePerDay) {
        Motorbike m = new Motorbike();
        m.shopId = shopId;
        m.brand = brand;
        m.model = model.trim().replaceAll("\\s+", " ");
        m.type = type;
        m.plateNumber = plateNumber;
        m.pricePerDay = pricePerDay;
        return m;
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public String getBrand() { return brand; }
    public String getModel() { return model; }
    public MotorbikeType getType() { return type; }
    public String getPlateNumber() { return plateNumber; }
    public BigDecimal getPricePerDay() { return pricePerDay; }
    public void setPricePerDay(BigDecimal v) { this.pricePerDay = v; }
    public String getCurrency() { return currency; }
    public MotorbikeStatus getStatus() { return status; }
    public void setStatus(MotorbikeStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}

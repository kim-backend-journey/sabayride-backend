package com.sabayride.rental.shop;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shop")
public class Shop {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** identity.users.id — a plain UUID. No foreign key: different service, different schema. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ShopStatus status = ShopStatus.PENDING_VERIFICATION;

    @Column(name = "offers_delivery", nullable = false)
    private boolean offersDelivery = false;

    /** Money is BigDecimal. Never double — 0.1 + 0.2 != 0.3 in binary floating point. */
    @Column(name = "delivery_fee", precision = 10, scale = 2)
    private BigDecimal deliveryFee;

    @Column(name = "accepts_pay_at_shop", nullable = false)
    private boolean acceptsPayAtShop = true;

    @Column(name = "rating", precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Shop() { }   // JPA

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public ShopStatus getStatus() { return status; }
    public void setStatus(ShopStatus status) { this.status = status; }
    public boolean isOffersDelivery() { return offersDelivery; }
    public void setOffersDelivery(boolean v) { this.offersDelivery = v; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public void setDeliveryFee(BigDecimal v) { this.deliveryFee = v; }
    public boolean isAcceptsPayAtShop() { return acceptsPayAtShop; }
    public void setAcceptsPayAtShop(boolean v) { this.acceptsPayAtShop = v; }
    public BigDecimal getRating() { return rating; }
    public int getReviewCount() { return reviewCount; }
    public Instant getCreatedAt() { return createdAt; }
}

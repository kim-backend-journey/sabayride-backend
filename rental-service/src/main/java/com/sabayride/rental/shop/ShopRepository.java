package com.sabayride.rental.shop;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {
    List<Shop> findByStatus(ShopStatus status);
}

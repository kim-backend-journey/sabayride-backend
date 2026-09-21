package com.sabayride.rental.discovery;

import com.sabayride.rental.common.PageEnvelope;
import com.sabayride.rental.discovery.dto.ModelDetail;
import com.sabayride.rental.discovery.dto.ModelSearchResult;
import com.sabayride.rental.fleet.MotorbikeType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public. No authentication — browsing is what brings customers in. */
@RestController
@RequestMapping("/api/v1/motorbikes")
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    /**
     * startDate and endDate are REQUIRED, not optional.
     *
     * Availability is meaningless without a range, and a results screen that
     * shows bikes without saying when they are free is the problem this
     * platform exists to fix.
     */
    @GetMapping("/search")
    PageEnvelope<ModelSearchResult> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) List<MotorbikeType> type,
            @RequestParam(required = false) BigDecimal minPricePerDay,
            @RequestParam(required = false) BigDecimal maxPricePerDay,
            @RequestParam(required = false) Boolean offersDelivery,
            @RequestParam(required = false) UUID shopId,
            @RequestParam(required = false) SearchService.Sort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return service.search(startDate, endDate, type,
                minPricePerDay, maxPricePerDay, offersDelivery, shopId,
                sort, page, Math.min(size, 100));
    }

    @GetMapping("/models/{shopId}/{model}")
    ModelDetail modelDetail(
            @PathVariable UUID shopId,
            @PathVariable String model,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        // Model names contain spaces, so the client sends them percent-encoded.
        return service.modelDetail(shopId,
                URLDecoder.decode(model, StandardCharsets.UTF_8),
                startDate, endDate);
    }
}

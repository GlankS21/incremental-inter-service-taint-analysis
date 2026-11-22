package com.shop.cart.client;

import com.shop.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Stock of inventory-service in one warehouse.
 */
@FeignClient(name = "inventory-service", contextId = "warehouseClient", path = "/api/inventory")
public interface WarehouseClient {

    @GetMapping("/availability/by-warehouse")
    ApiResponse<List<StockAvailability>> availabilityIn(@RequestParam("productIds") List<Long> productIds,
                                                        @RequestParam("warehouse") String warehouse);
}

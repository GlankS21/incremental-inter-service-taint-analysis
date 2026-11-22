package com.shop.order.controller;

import com.shop.common.dto.ApiResponse;
import com.shop.order.dto.response.OrderStatsResponse;
import com.shop.order.service.OrderStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Статистика заказов", description = "Панель продаж (только для администраторов)")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/orders/admin/stats")
@RequiredArgsConstructor
public class AdminOrderStatsController {

    private final OrderStatsService orderStatsService;

    @Operation(summary = "Заказы, выручка и лидеры продаж за период",
            description = "Дни — календарные дни в часовом поясе магазина, обе границы включаются (не более "
                    + "366 дней). Количество заказов считается по заказам, созданным в периоде; выручка, "
                    + "оплаченные заказы и лидеры продаж — по заказам, оплаченным в периоде и с тех пор "
                    + "не отменённым.")
    @GetMapping
    public ApiResponse<OrderStatsResponse> statistics(
            @Parameter(description = "Первый день (дата ISO), по умолчанию за 29 дней до последнего",
                    example = "2026-01-01")
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Последний день (дата ISO), по умолчанию сегодня", example = "2026-01-31")
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Количество лидеров продаж, от 1 до 20")
            @RequestParam(name = "top", defaultValue = "5") @Min(1) @Max(20) int top) {
        return ApiResponse.success(orderStatsService.statistics(from, to, top));
    }
}

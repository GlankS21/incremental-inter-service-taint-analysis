package com.shop.product.controller;

import com.shop.common.dto.ApiResponse;
import com.shop.product.dto.response.PopularSearchesResponse;
import com.shop.product.dto.response.SearchSuggestionsResponse;
import com.shop.product.service.ProductSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Поиск", description = "Подсказки при вводе запроса и популярные запросы (доступны всем)")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class SearchController {

    private final ProductSearchService productSearchService;

    @Operation(summary = "Подсказки поиска",
            description = "До 5 товаров, 3 категорий, 3 брендов и 5 продолжений запроса за один вызов. "
                    + "Учитываются морфология, синонимы, раскладка клавиатуры и опечатки; результат кэшируется "
                    + "на короткое время. Пустой запрос возвращает пустые подсказки.")
    @GetMapping("/suggest")
    public ApiResponse<SearchSuggestionsResponse> suggest(
            @Parameter(description = "Введённый текст, не длиннее 100 символов")
            @RequestParam(name = "q", required = false) String query) {
        return ApiResponse.success(productSearchService.suggest(query));
    }

    @Operation(summary = "Популярные запросы и категории",
            description = "Частые запросы с результатами за последние 30 дней и самые продаваемые категории.")
    @GetMapping("/search/popular")
    public ApiResponse<PopularSearchesResponse> popular() {
        return ApiResponse.success(productSearchService.popular());
    }
}

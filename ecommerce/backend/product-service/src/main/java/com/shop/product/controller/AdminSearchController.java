package com.shop.product.controller;

import com.shop.common.autoconfigure.CommonMessagesAutoConfiguration;
import com.shop.common.dto.ApiResponse;
import com.shop.common.dto.PageResponse;
import com.shop.product.config.OpenApiConfig;
import com.shop.product.dto.request.SynonymGroupRequest;
import com.shop.product.dto.response.SearchStatsResponse;
import com.shop.product.dto.response.SynonymGroupResponse;
import com.shop.product.service.SearchHistoryService;
import com.shop.product.service.SynonymService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Управление поиском", description = "Статистика запросов и синонимы поиска (только для администраторов)")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/search/admin")
@RequiredArgsConstructor
public class AdminSearchController {

    private final SearchHistoryService searchHistoryService;
    private final SynonymService synonymService;
    private final MessageSource messageSource;

    @Operation(summary = "Статистика запросов",
            description = "Частые запросы и запросы без результатов за последние days дней (запросы "
                    + "покупателей, первая страница выдачи).",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @GetMapping("/stats")
    public ApiResponse<SearchStatsResponse> stats(
            @Parameter(description = "Период в днях, от 1 до 365")
            @RequestParam(name = "days", defaultValue = "30") int days,
            @Parameter(description = "Размер списков, от 1 до 100")
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.success(searchHistoryService.stats(days, limit));
    }

    @Operation(summary = "Группы синонимов, сначала изменённые недавно",
            description = "keyword — часть одного из слов группы. Сортировка по полям updatedAt, createdAt.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @GetMapping("/synonyms")
    public ApiResponse<PageResponse<SynonymGroupResponse>> listSynonyms(
            @RequestParam(name = "keyword", required = false) String keyword,
            @ParameterObject
            @PageableDefault(sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(synonymService.listGroups(keyword, pageable));
    }

    @Operation(summary = "Создать группу синонимов",
            description = "От 2 до 20 слов; слова приводятся к нижнему регистру, повторы удаляются. "
                    + "Слово может входить только в одну группу.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @PostMapping("/synonyms")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SynonymGroupResponse> createSynonyms(@Valid @RequestBody SynonymGroupRequest request) {
        return ApiResponse.success(message("search.synonym-created"), synonymService.createGroup(request));
    }

    @Operation(summary = "Заменить слова группы синонимов",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @PutMapping("/synonyms/{groupId}")
    public ApiResponse<SynonymGroupResponse> updateSynonyms(@PathVariable("groupId") Long groupId,
                                                            @Valid @RequestBody SynonymGroupRequest request) {
        return ApiResponse.success(message("search.synonym-updated"), synonymService.updateGroup(groupId, request));
    }

    @Operation(summary = "Удалить группу синонимов",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @DeleteMapping("/synonyms/{groupId}")
    public ApiResponse<Void> deleteSynonyms(@PathVariable("groupId") Long groupId) {
        synonymService.deleteGroup(groupId);
        return ApiResponse.message(message("search.synonym-deleted"));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, CommonMessagesAutoConfiguration.DEFAULT_LOCALE);
    }
}

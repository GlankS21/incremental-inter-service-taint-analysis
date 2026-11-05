package com.shop.product.service;

import com.shop.common.dto.PageResponse;
import com.shop.product.dto.request.SynonymGroupRequest;
import com.shop.product.dto.response.SynonymGroupResponse;
import org.springframework.data.domain.Pageable;

/**
 * Synonym groups of the search, managed by administrators.
 */
public interface SynonymService {

    /**
     * @param keyword part of a term, {@code null} for every group
     */
    PageResponse<SynonymGroupResponse> listGroups(String keyword, Pageable pageable);

    SynonymGroupResponse createGroup(SynonymGroupRequest request);

    SynonymGroupResponse updateGroup(Long groupId, SynonymGroupRequest request);

    void deleteGroup(Long groupId);
}

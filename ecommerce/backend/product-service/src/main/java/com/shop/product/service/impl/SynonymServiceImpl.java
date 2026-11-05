package com.shop.product.service.impl;

import com.shop.common.dto.PageResponse;
import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.ConflictException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.product.dto.request.SynonymGroupRequest;
import com.shop.product.dto.response.SynonymGroupResponse;
import com.shop.product.entity.SynonymGroup;
import com.shop.product.mapper.SynonymGroupMapper;
import com.shop.product.repository.SynonymGroupRepository;
import com.shop.product.service.SynonymService;
import com.shop.product.util.Paging;
import com.shop.product.util.SearchText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SynonymServiceImpl implements SynonymService {

    private static final long NO_GROUP = -1L;
    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "updatedAt");

    private final SynonymGroupRepository synonymGroupRepository;
    private final SynonymGroupMapper synonymGroupMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SynonymGroupResponse> listGroups(String keyword, Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("search.error.unsupported-sort",
                        String.join(", ", SORTABLE_PROPERTIES.stream().sorted().toList()));
            }
        });
        String term = SearchText.normalize(keyword);
        Pageable page = Paging.withIdTieBreaker(pageable);
        return PageResponse.from(term == null
                        ? synonymGroupRepository.findAll(page)
                        : synonymGroupRepository.findByTermLike("%" + SearchText.escapeLike(term) + "%", page),
                synonymGroupMapper::toResponse);
    }

    @Override
    @Transactional
    public SynonymGroupResponse createGroup(SynonymGroupRequest request) {
        Set<String> terms = normalizedTerms(request);
        requireFree(terms, NO_GROUP);
        SynonymGroup group = new SynonymGroup();
        group.replaceTerms(terms);
        SynonymGroup saved = synonymGroupRepository.saveAndFlush(group);
        log.info("Created synonym group {}: {}", saved.getId(), terms);
        return synonymGroupMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public SynonymGroupResponse updateGroup(Long groupId, SynonymGroupRequest request) {
        SynonymGroup group = findGroup(groupId);
        Set<String> terms = normalizedTerms(request);
        requireFree(terms, groupId);
        group.replaceTerms(terms);
        SynonymGroup saved = synonymGroupRepository.saveAndFlush(group);
        log.info("Updated synonym group {}: {}", groupId, terms);
        return synonymGroupMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteGroup(Long groupId) {
        synonymGroupRepository.delete(findGroup(groupId));
        log.info("Deleted synonym group {}", groupId);
    }

    /**
     * Trimmed, lower case, single spaces, "ё" as "е"; duplicates removed. At least two must remain.
     */
    private static Set<String> normalizedTerms(SynonymGroupRequest request) {
        Set<String> terms = new LinkedHashSet<>();
        request.terms().stream()
                .map(SearchText::normalize)
                .filter(Objects::nonNull)
                .forEach(terms::add);
        if (terms.size() < 2) {
            throw new BadRequestException("search.error.synonym-too-few");
        }
        return terms;
    }

    private void requireFree(Set<String> terms, Long ownGroupId) {
        synonymGroupRepository.findOwnersOfTerms(terms, ownGroupId).stream()
                .flatMap(owner -> owner.getTerms().stream())
                .filter(terms::contains)
                .sorted()
                .findFirst()
                .ifPresent(term -> {
                    throw new ConflictException("search.error.synonym-term-taken", term);
                });
    }

    private SynonymGroup findGroup(Long groupId) {
        return synonymGroupRepository.findById(groupId)
                .orElseThrow(() -> ResourceNotFoundException.of("search.error.synonym-not-found"));
    }
}

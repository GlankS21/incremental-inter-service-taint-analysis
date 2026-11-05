package com.shop.product.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.ConflictException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.product.dto.request.SynonymGroupRequest;
import com.shop.product.entity.SynonymGroup;
import com.shop.product.mapper.SynonymGroupMapper;
import com.shop.product.repository.SynonymGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SynonymServiceImplTest {

    @Mock
    private SynonymGroupRepository synonymGroupRepository;

    private SynonymServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SynonymServiceImpl(synonymGroupRepository, Mappers.getMapper(SynonymGroupMapper.class));
    }

    @Test
    void storesTermsNormalizedAndDeduplicated() {
        when(synonymGroupRepository.findOwnersOfTerms(any(), eq(-1L))).thenReturn(List.of());
        when(synonymGroupRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createGroup(new SynonymGroupRequest(List.of(" Ноутбук ", "LAPTOP", "ноутбук", "Лэптоп")));

        ArgumentCaptor<SynonymGroup> saved = ArgumentCaptor.forClass(SynonymGroup.class);
        verify(synonymGroupRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTerms()).containsExactlyInAnyOrder("ноутбук", "laptop", "лэптоп");
    }

    @Test
    void needsTwoDistinctTerms() {
        assertThatThrownBy(() -> service.createGroup(new SynonymGroupRequest(List.of("Мышь", "мышь "))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("search.error.synonym-too-few");
        verify(synonymGroupRepository, never()).saveAndFlush(any());
    }

    @Test
    void refusesATermOfAnotherGroup() {
        SynonymGroup other = new SynonymGroup();
        other.replaceTerms(Set.of("мышь", "mouse"));
        when(synonymGroupRepository.findOwnersOfTerms(Set.of("mouse", "грызун"), -1L)).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.createGroup(new SynonymGroupRequest(List.of("mouse", "грызун"))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("search.error.synonym-term-taken");
    }

    @Test
    void updateMayKeepItsOwnTerms() {
        SynonymGroup group = new SynonymGroup();
        group.replaceTerms(Set.of("мышь", "mouse"));
        when(synonymGroupRepository.findById(5L)).thenReturn(Optional.of(group));
        when(synonymGroupRepository.findOwnersOfTerms(any(), eq(5L))).thenReturn(List.of());
        when(synonymGroupRepository.saveAndFlush(group)).thenReturn(group);

        assertThat(service.updateGroup(5L, new SynonymGroupRequest(List.of("мышь", "mouse", "мышка"))).terms())
                .containsExactly("mouse", "мышка", "мышь");
    }

    @Test
    void unknownGroupIsNotFound() {
        when(synonymGroupRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteGroup(9L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listsOnlyBySupportedSortProperties() {
        assertThatThrownBy(() -> service.listGroups(null, PageRequest.of(0, 20, Sort.by("terms"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("search.error.unsupported-sort");
    }

    @Test
    void listingBreaksTiesById() {
        when(synonymGroupRepository.findAll(any(Pageable.class))).thenAnswer(inv -> Page.empty(inv.getArgument(0)));

        service.listGroups(null, PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "updatedAt")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(synonymGroupRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
    }
}

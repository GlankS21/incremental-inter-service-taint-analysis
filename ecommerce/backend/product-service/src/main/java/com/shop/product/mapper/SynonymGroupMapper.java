package com.shop.product.mapper;

import com.shop.product.dto.response.SynonymGroupResponse;
import com.shop.product.entity.SynonymGroup;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface SynonymGroupMapper {

    @Mapping(target = "terms", expression = "java(sortedTerms(group))")
    SynonymGroupResponse toResponse(SynonymGroup group);

    default List<String> sortedTerms(SynonymGroup group) {
        return group.getTerms().stream().sorted().toList();
    }
}

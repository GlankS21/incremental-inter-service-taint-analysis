package com.shop.product.repository;

import com.shop.product.entity.SearchQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    @Modifying
    @Query("delete from SearchQuery q where q.searchedAt < :before")
    int deleteSearchedBefore(@Param("before") Instant before);
}

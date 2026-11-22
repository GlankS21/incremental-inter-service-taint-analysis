package com.shop.product.repository;

import com.shop.product.entity.SynonymGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SynonymGroupRepository extends JpaRepository<SynonymGroup, Long> {

    /**
     * Groups having a term that matches the LIKE pattern (escape character {@code \}).
     */
    @Query(value = """
            select g from SynonymGroup g
            where exists (select 1 from SynonymGroup other join other.terms term
                          where other = g and term like :pattern escape '\\')
            """,
            countQuery = """
                    select count(g) from SynonymGroup g
                    where exists (select 1 from SynonymGroup other join other.terms term
                                  where other = g and term like :pattern escape '\\')
                    """)
    Page<SynonymGroup> findByTermLike(@Param("pattern") String pattern, Pageable pageable);

    /**
     * Groups other than {@code excludedId} ({@code -1} for none) owning one of the terms.
     */
    @Query("select distinct g from SynonymGroup g join g.terms term where term in :terms and g.id <> :excludedId")
    List<SynonymGroup> findOwnersOfTerms(@Param("terms") Collection<String> terms,
                                         @Param("excludedId") Long excludedId);
}

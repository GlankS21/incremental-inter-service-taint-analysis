package com.shop.product.entity;

import com.shop.product.service.SearchIndexMaintenance;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JPA listener of the entities the search derives data from (products, categories, synonym groups); a
 * Spring bean, instantiated through Hibernate's bean container.
 */
@Component
@RequiredArgsConstructor
public class CatalogChangeListener {

    private final SearchIndexMaintenance searchIndexMaintenance;

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(Object entity) {
        searchIndexMaintenance.catalogChanged();
    }
}

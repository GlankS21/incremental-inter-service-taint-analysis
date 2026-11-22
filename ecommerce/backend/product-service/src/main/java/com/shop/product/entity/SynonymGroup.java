package com.shop.product.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Words or phrases the search treats as interchangeable ("ноутбук", "лэптоп", "laptop"). Terms are stored
 * normalised (trimmed, lower case); a term belongs to at most one group (unique in the database).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(CatalogChangeListener.class)
@Table(name = "search_synonyms")
public class SynonymGroup extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "search_synonym_terms", joinColumns = @JoinColumn(name = "synonym_id"))
    @Column(name = "term", nullable = false, length = 60)
    private Set<String> terms = new LinkedHashSet<>();

    @Version
    private Long version;

    public void replaceTerms(Collection<String> newTerms) {
        terms.clear();
        terms.addAll(newTerms);
    }
}

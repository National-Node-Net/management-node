/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Product;

/**
 * Verifies {@link ProductRepository#findDiscoveryCandidates}'s default method builds
 * LIKE-escaped patterns before delegating to {@link ProductRepository#findDiscoveryCandidatesByPattern},
 * so a search value containing the LIKE metacharacters {@code %}/{@code _} is matched
 * literally rather than as a wildcard. Mocked with {@code CALLS_REAL_METHODS} so the default
 * method itself executes, with only the underlying {@code @Query} method stubbed.
 */
class ProductRepositoryTest {

    private final ProductRepository productRepository =
            mock(ProductRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

    @Test
    void findDiscoveryCandidates_escapesPercentAndUnderscoreInNameAndTopic() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findDiscoveryCandidatesByPattern(any(), any(), any(), eq(pageable)))
                .thenReturn(List.of());

        productRepository.findDiscoveryCandidates("Data_Feed", "topic%1", "TypeA", pageable);

        verify(productRepository).findDiscoveryCandidatesByPattern("%Data\\_Feed%", "%topic\\%1%", "TypeA", pageable);
    }

    @Test
    void findDiscoveryCandidates_escapesLiteralBackslash() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findDiscoveryCandidatesByPattern(any(), any(), any(), eq(pageable)))
                .thenReturn(List.of());

        productRepository.findDiscoveryCandidates("a\\b", null, null, pageable);

        verify(productRepository).findDiscoveryCandidatesByPattern(eq("%a\\\\b%"), isNull(), isNull(), eq(pageable));
    }

    @Test
    void findDiscoveryCandidates_nullFilters_passedAsNullPatterns() {
        Pageable pageable = PageRequest.of(0, 10);
        List<Product> expected = List.of();
        when(productRepository.findDiscoveryCandidatesByPattern(isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(expected);

        List<Product> result = productRepository.findDiscoveryCandidates(null, null, null, pageable);

        assertThat(result).isEqualTo(expected);
        verify(productRepository).findDiscoveryCandidatesByPattern(isNull(), isNull(), isNull(), eq(pageable));
    }
}

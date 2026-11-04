/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FilterAttributeDefinition;

/**
 * Which filters a caller is offered: every product field the contract permits (never {@code id}),
 * and every attribute definition the contract permits, with the sensitive ones dropped exactly
 * when the contract withholds them. This decides the filter panel's shape; it never looks at a
 * value or a count.
 *
 * <p>Fields come first, in {@link ProductField} declaration order, then product attributes by
 * label, then organisation attributes by label - both case-insensitive. {@link
 * uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteriaFactory#permits} is the
 * single source of what "permitted" means, so a filter the contract would refuse at search time is
 * never offered here either.
 */
public final class FilterOptionTargets {

    private static final String TEXT = "TEXT";
    private static final String NUMBER = "NUMBER";
    private static final String BOOLEAN = "BOOLEAN";

    private static final EnumSet<ComparisonOperator> TEXT_OPERATORS = EnumSet.of(
            ComparisonOperator.EQ,
            ComparisonOperator.NEQ,
            ComparisonOperator.IN,
            ComparisonOperator.NOT_IN,
            ComparisonOperator.ANY_OF,
            ComparisonOperator.NONE_OF,
            ComparisonOperator.CONTAINS,
            ComparisonOperator.EXISTS,
            ComparisonOperator.NOT_EXISTS);

    private static final EnumSet<ComparisonOperator> BOOLEAN_OPERATORS = EnumSet.of(
            ComparisonOperator.EQ,
            ComparisonOperator.NEQ,
            ComparisonOperator.IN,
            ComparisonOperator.NOT_IN,
            ComparisonOperator.ANY_OF,
            ComparisonOperator.NONE_OF,
            ComparisonOperator.EXISTS,
            ComparisonOperator.NOT_EXISTS);

    private FilterOptionTargets() {}

    /**
     * @param contract what the caller may filter on
     * @param definitions every policy attribute definition filtering could offer, before the
     *     contract narrows them
     * @param sensitiveAttributeNames the attribute names discovery withholds as sensitive for this
     *     contract - exactly {@code ProductQueryPlanner#sensitiveAttributeNames}, so the panel never
     *     offers an attribute discovery would refuse, nor withholds one discovery would accept. A
     *     definition's own {@code sensitive} flag is deliberately not consulted: it is scope-limited
     *     and de-duplicated per name, whereas discovery's set spans every namespace and scope
     * @param callerOrganisation the caller's organisation key, or null/blank/{@link
     *     UnknownIdentifiers#UNKNOWN_ORG} when it is not known
     */
    public static List<FilterOptionTarget> permitted(
            ProductSearchContract contract,
            List<FilterAttributeDefinition> definitions,
            Set<String> sensitiveAttributeNames,
            String callerOrganisation) {
        boolean callerKnown = UnknownIdentifiers.knownOrganisationOrNull(callerOrganisation) != null;

        List<FilterOptionTarget> options = new ArrayList<>(fieldOptions(contract, callerKnown));

        List<FilterOptionTarget> productAttributes = new ArrayList<>();
        List<FilterOptionTarget> organisationAttributes = new ArrayList<>();
        for (FilterAttributeDefinition definition : definitions) {
            FilterTarget target = FilterTarget.parse(definition.scope(), false, definition.name());
            if (!ProductSearchCriteriaFactory.permits(target, contract, sensitiveAttributeNames)) {
                continue;
            }
            FilterOptionTarget option = attributeOption(definition, target);
            (definition.scope() == FilterScope.PRODUCT ? productAttributes : organisationAttributes).add(option);
        }
        Comparator<FilterOptionTarget> byLabel =
                Comparator.comparing(FilterOptionTarget::label, String.CASE_INSENSITIVE_ORDER);
        productAttributes.sort(byLabel);
        organisationAttributes.sort(byLabel);

        options.addAll(productAttributes);
        options.addAll(organisationAttributes);
        return List.copyOf(options);
    }

    private static List<FilterOptionTarget> fieldOptions(ProductSearchContract contract, boolean callerKnown) {
        List<FilterOptionTarget> options = new ArrayList<>();
        for (ProductField field : ProductField.values()) {
            if (!isOfferedTo(field, callerKnown)) {
                continue;
            }
            // Built the same way ProductSearchCriteriaFactory reads an unscoped filter -
            // FilterTarget.parse(null, true, name) - rather than FilterTarget.ofField, which would
            // leave a dotted name such as "organisation.key" as a single PRODUCT-scoped name
            // instead of the ORGANISATION-scoped "key" the criteria side builds. The two targets
            // must be equal, or a caller's own filter on such a name can never be recognised as
            // this target's own: selectedValues would never select it, and
            // ProductFacetQueryBuilder's narrowedCondition would never exclude it from its own
            // counts.
            FilterTarget target = FilterTarget.parse(null, true, field.apiName());
            if (ProductSearchCriteriaFactory.permits(target, contract, Set.of())) {
                options.add(fieldOption(field, target));
            }
        }
        return options;
    }

    /**
     * Whether the panel offers this field to this caller: offered in the panel at all, filterable,
     * and, for {@code isOwnProduct}, only when the caller's organisation is known.
     */
    private static boolean isOfferedTo(ProductField field, boolean callerKnown) {
        if (!isOfferedInPanel(field) || !field.isFilterable()) {
            return false;
        }
        return field != ProductField.IS_OWN_PRODUCT || callerKnown;
    }

    private static FilterOptionTarget fieldOption(ProductField field, FilterTarget target) {
        boolean isBoolean = field.kind() == ProductField.Kind.BOOLEAN;
        boolean multiValued = field == ProductField.SUBSCRIBED_BY;
        boolean listsValues = fieldListsValues(field);
        List<ComparisonOperator> operators = fieldOperators(field, isBoolean);
        ComparisonOperator defaultOperator = defaultOperator(listsValues, isBoolean, multiValued);
        return new FilterOptionTarget(
                "field:" + field.apiName(),
                target,
                field,
                fieldLabel(field),
                field.kind().name(),
                multiValued,
                operators,
                defaultOperator,
                field.isSortable(),
                listsValues);
    }

    private static FilterOptionTarget attributeOption(FilterAttributeDefinition definition, FilterTarget target) {
        String label = StringUtils.hasText(definition.displayName()) ? definition.displayName() : definition.name();
        String dataType = mapDataType(definition.dataType());
        boolean multiValued = definition.multiValued();
        List<ComparisonOperator> operators = attributeOperators(dataType, multiValued);
        ComparisonOperator defaultOperator = defaultOperator(true, BOOLEAN.equals(dataType), multiValued);
        String key = "attribute:" + target.scope().wireName() + "." + definition.name();
        return new FilterOptionTarget(
                key, target, null, label, dataType, multiValued, operators, defaultOperator, true, true);
    }

    /**
     * Whether the filter panel offers a field at all. The panel is for narrowing by a value picked
     * from a list, so it leaves out:
     *
     * <ul>
     *   <li>{@code name}, {@code displayName}, {@code description} and {@code longDescription}:
     *       prose, already searched by the free-text box ({@code textSearch});
     *   <li>{@code topic} and {@code source}: identifiers nobody browses by;
     *   <li>{@code id} and the producer's members, which are not filterable here at all.
     * </ul>
     *
     * <p>This only narrows the panel. Discover itself still accepts a filter on any field the
     * contract permits, so a caller that names one directly is answered exactly as before.
     */
    private static boolean isOfferedInPanel(ProductField field) {
        return switch (field) {
            case TYPE, FORMAT, ORGANISATION_KEY, ORGANISATION_NAME, SUBSCRIBED_BY, IS_OWN_PRODUCT -> true;
            case NAME, DISPLAY_NAME, DESCRIPTION, LONG_DESCRIPTION, TOPIC, SOURCE -> false;
            case ID, PRODUCER_NAME, PRODUCER_DESCRIPTION, PRODUCER_ACTIVE -> false;
        };
    }

    private static String fieldLabel(ProductField field) {
        return switch (field) {
            case TYPE -> "Type";
            case FORMAT -> "Format";
            case ORGANISATION_KEY -> "Organisation";
            case ORGANISATION_NAME -> "Organisation name";
            case SUBSCRIBED_BY -> "Subscribed by";
            case IS_OWN_PRODUCT -> "My organisation's products";
            case NAME,
                    DISPLAY_NAME,
                    DESCRIPTION,
                    LONG_DESCRIPTION,
                    TOPIC,
                    SOURCE,
                    ID,
                    PRODUCER_NAME,
                    PRODUCER_DESCRIPTION,
                    PRODUCER_ACTIVE -> throw new IllegalStateException("No filter option label defined for " + field);
        };
    }

    private static boolean fieldListsValues(ProductField field) {
        return switch (field) {
            case TYPE, FORMAT, ORGANISATION_KEY, ORGANISATION_NAME, SUBSCRIBED_BY, IS_OWN_PRODUCT -> true;
            case NAME,
                    DISPLAY_NAME,
                    DESCRIPTION,
                    LONG_DESCRIPTION,
                    TOPIC,
                    SOURCE,
                    ID,
                    PRODUCER_NAME,
                    PRODUCER_DESCRIPTION,
                    PRODUCER_ACTIVE -> throw new IllegalStateException("No filter option defined for " + field);
        };
    }

    private static List<ComparisonOperator> fieldOperators(ProductField field, boolean isBoolean) {
        if (isBoolean) {
            return List.copyOf(BOOLEAN_OPERATORS);
        }
        EnumSet<ComparisonOperator> operators = EnumSet.copyOf(TEXT_OPERATORS);
        if (field == ProductField.SUBSCRIBED_BY) {
            operators.remove(ComparisonOperator.CONTAINS);
        }
        return List.copyOf(operators);
    }

    private static List<ComparisonOperator> attributeOperators(String dataType, boolean multiValued) {
        if (BOOLEAN.equals(dataType)) {
            return List.copyOf(BOOLEAN_OPERATORS);
        }
        EnumSet<ComparisonOperator> operators = EnumSet.copyOf(TEXT_OPERATORS);
        if (multiValued) {
            operators.add(ComparisonOperator.ALL_OF);
        }
        if (NUMBER.equals(dataType)) {
            operators.add(ComparisonOperator.LT);
            operators.add(ComparisonOperator.LTE);
            operators.add(ComparisonOperator.GT);
            operators.add(ComparisonOperator.GTE);
        }
        return List.copyOf(operators);
    }

    private static ComparisonOperator defaultOperator(boolean listsValues, boolean isBoolean, boolean multiValued) {
        if (!listsValues) {
            return ComparisonOperator.CONTAINS;
        }
        if (isBoolean) {
            return ComparisonOperator.EQ;
        }
        if (multiValued) {
            return ComparisonOperator.ANY_OF;
        }
        return ComparisonOperator.IN;
    }

    private static String mapDataType(String dataType) {
        if (dataType == null) {
            return TEXT;
        }
        return switch (dataType.toUpperCase(Locale.ROOT)) {
            case "STRING", TEXT -> TEXT;
            case NUMBER, "INTEGER", "DECIMAL", "NUMERIC" -> NUMBER;
            case BOOLEAN -> BOOLEAN;
            default -> TEXT;
        };
    }
}

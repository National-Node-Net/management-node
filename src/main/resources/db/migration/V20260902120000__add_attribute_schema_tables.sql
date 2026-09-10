/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Which core entity types may carry dynamic attributes, and the table entity_id resolves against.
CREATE TABLE attribute_scope (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    table_name VARCHAR(150) NOT NULL,
    description VARCHAR(500),
    CONSTRAINT uq_attribute_scope__code UNIQUE (code)
);

-- Attribute vocabulary: name/type/validation metadata, independent of which scope(s) it applies to.
CREATE TABLE attribute_definition (
    id BIGSERIAL PRIMARY KEY,
    namespace VARCHAR(150) NOT NULL,
    name VARCHAR(150) NOT NULL,
    display_name VARCHAR(255),
    description TEXT NOT NULL,
    data_type VARCHAR(50) NOT NULL,
    multi_valued BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_values JSONB,
    validation_pattern VARCHAR(500),
    classification JSONB,
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    created_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP,
    updated_by VARCHAR(255),
    CONSTRAINT uq_attribute_definition__namespace_name UNIQUE (namespace, name)
);

-- Which scopes a definition is valid on, whether required there, and its default.
CREATE TABLE attribute_definition_scope (
    id BIGSERIAL PRIMARY KEY,
    attribute_definition_id BIGINT NOT NULL,
    attribute_scope_id BIGINT NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    default_value JSONB,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    created_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP,
    updated_by VARCHAR(255),
    CONSTRAINT fk_attribute_definition_scope__attribute_definition_id
        FOREIGN KEY (attribute_definition_id) REFERENCES attribute_definition (id),
    CONSTRAINT fk_attribute_definition_scope__attribute_scope_id
        FOREIGN KEY (attribute_scope_id) REFERENCES attribute_scope (id),
    CONSTRAINT uq_attribute_definition_scope__definition_scope
        UNIQUE (attribute_definition_id, attribute_scope_id)
);
CREATE INDEX idx_attribute_definition_scope__attribute_definition_id
    ON attribute_definition_scope (attribute_definition_id);
CREATE INDEX idx_attribute_definition_scope__attribute_scope_id
    ON attribute_definition_scope (attribute_scope_id);

-- Actual values. entity_id is polymorphic: PK of the row in attribute_scope.table_name for that pairing's
-- scope, not a declared FK — enforced by the soft-delete trigger below, not by the database schema.
CREATE TABLE attribute_value (
    id BIGSERIAL PRIMARY KEY,
    attribute_definition_scope_id BIGINT NOT NULL,
    entity_id BIGINT NOT NULL,
    value JSONB NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    created_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP,
    updated_by VARCHAR(255),
    CONSTRAINT fk_attribute_value__attribute_definition_scope_id
        FOREIGN KEY (attribute_definition_scope_id) REFERENCES attribute_definition_scope (id)
);
CREATE INDEX idx_attribute_value__entity_id ON attribute_value (entity_id);
-- Backs the PEP's EXISTS sub-queries per constraint (attribute_definition_scope_id, entity_id, value).
CREATE UNIQUE INDEX uq_attr_value_live
    ON attribute_value (attribute_definition_scope_id, entity_id, value)
    WHERE is_deleted = FALSE;

INSERT INTO attribute_scope (code, table_name, description) VALUES
    ('ORGANISATION', 'organisation', 'Attributes carried by an organisation'),
    ('CONSUMER', 'consumer', 'Attributes carried by a consumer'),
    ('PRODUCER', 'producer', 'Attributes carried by a producer'),
    ('PRODUCT', 'product', 'Attributes carried by a product'),
    ('SUBSCRIPTION', 'product_consumer', 'Attributes carried by a product/consumer subscription');

-- Soft-delete any live attribute_value rows for the entity being removed, scoped to the deleted table.
CREATE FUNCTION fn_attribute_value_soft_delete_on_entity_delete() RETURNS TRIGGER AS $$
BEGIN
    UPDATE attribute_value av
    SET is_deleted = TRUE,
        updated_at = now(),
        updated_by = 'trigger:' || TG_TABLE_NAME
    FROM attribute_definition_scope ads
    JOIN attribute_scope asc_ ON asc_.id = ads.attribute_scope_id
    WHERE av.attribute_definition_scope_id = ads.id
      AND asc_.table_name = TG_TABLE_NAME
      AND av.entity_id = OLD.id
      AND av.is_deleted = FALSE;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_organisation_attribute_value_soft_delete
    AFTER DELETE ON organisation
    FOR EACH ROW EXECUTE FUNCTION fn_attribute_value_soft_delete_on_entity_delete();

CREATE TRIGGER trg_consumer_attribute_value_soft_delete
    AFTER DELETE ON consumer
    FOR EACH ROW EXECUTE FUNCTION fn_attribute_value_soft_delete_on_entity_delete();

CREATE TRIGGER trg_producer_attribute_value_soft_delete
    AFTER DELETE ON producer
    FOR EACH ROW EXECUTE FUNCTION fn_attribute_value_soft_delete_on_entity_delete();

CREATE TRIGGER trg_product_attribute_value_soft_delete
    AFTER DELETE ON product
    FOR EACH ROW EXECUTE FUNCTION fn_attribute_value_soft_delete_on_entity_delete();

CREATE TRIGGER trg_product_consumer_attribute_value_soft_delete
    AFTER DELETE ON product_consumer
    FOR EACH ROW EXECUTE FUNCTION fn_attribute_value_soft_delete_on_entity_delete();

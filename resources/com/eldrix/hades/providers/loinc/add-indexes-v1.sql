CREATE INDEX IF NOT EXISTS loinc_status                  ON loinc(status);
CREATE INDEX IF NOT EXISTS loinc_class                   ON loinc(class);
CREATE INDEX IF NOT EXISTS loinc_order_obs               ON loinc(order_obs);
CREATE INDEX IF NOT EXISTS loinc_external_copyright_link ON loinc(external_copyright_link);

CREATE INDEX IF NOT EXISTS loinc_group_parent_group      ON loinc_group(parent_group_id);
CREATE INDEX IF NOT EXISTS group_attribute_group         ON group_attribute(group_id);
CREATE INDEX IF NOT EXISTS group_loinc_term_loinc        ON group_loinc_term(loinc_number);
CREATE INDEX IF NOT EXISTS parent_group_attribute_parent ON parent_group_attribute(parent_group_id);

CREATE INDEX IF NOT EXISTS part_type                     ON part(part_type_name);
CREATE INDEX IF NOT EXISTS part_link_primary_part        ON part_link_primary(part_number);
CREATE INDEX IF NOT EXISTS part_link_supplementary_part  ON part_link_supplementary(part_number);
CREATE INDEX IF NOT EXISTS part_related_ext_code         ON part_related_code_mapping(ext_code_system, ext_code_id);

CREATE INDEX IF NOT EXISTS component_hierarchy_parent    ON component_hierarchy_by_system(immediate_parent);
CREATE INDEX IF NOT EXISTS component_hierarchy_code      ON component_hierarchy_by_system(code);

CREATE INDEX IF NOT EXISTS answer_list_id                ON answer_list(answer_list_id);
CREATE INDEX IF NOT EXISTS answer_list_answer_string     ON answer_list(answer_string_id);
CREATE INDEX IF NOT EXISTS answer_list_link_list         ON answer_list_link(answer_list_id);
CREATE INDEX IF NOT EXISTS answer_list_link_loinc        ON answer_list_link(loinc_number);

CREATE INDEX IF NOT EXISTS panels_parent_loinc           ON panels_and_forms(parent_loinc);
CREATE INDEX IF NOT EXISTS panels_member_loinc           ON panels_and_forms(loinc);
CREATE INDEX IF NOT EXISTS panels_answer_list_id         ON panels_answer_list(answer_list_id);
CREATE INDEX IF NOT EXISTS panels_answer_string          ON panels_answer_list(answer_string_id);
CREATE INDEX IF NOT EXISTS panels_answer_link_loinc      ON panels_answer_list_link(loinc_number);

CREATE INDEX IF NOT EXISTS document_ontology_loinc       ON document_ontology(loinc_number);
CREATE INDEX IF NOT EXISTS rsna_playbook_loinc           ON loinc_rsna_radiology_playbook(loinc_number);
CREATE INDEX IF NOT EXISTS rsna_playbook_part            ON loinc_rsna_radiology_playbook(part_number);
CREATE INDEX IF NOT EXISTS ieee_mapping_loinc            ON loinc_ieee_medical_device_mapping(loinc_num);

CREATE INDEX IF NOT EXISTS linguistic_variant_loinc      ON linguistic_variant_row(loinc_num);
CREATE INDEX IF NOT EXISTS linguistic_variant_id         ON linguistic_variant_row(variant_id);

CREATE INDEX IF NOT EXISTS loinc_change_snapshot_code    ON loinc_change_snapshot(loinc_num);
CREATE INDEX IF NOT EXISTS part_change_snapshot_code     ON part_change_snapshot(part_number);
CREATE INDEX IF NOT EXISTS updates_code                  ON updates(loinc_num);
CREATE INDEX IF NOT EXISTS updates_additional_code       ON updates_additional_fields(loinc_num);

CREATE VIRTUAL TABLE IF NOT EXISTS loinc_fts USING fts5(
  long_common_name,
  component,
  system,
  related_names_2,
  shortname,
  display_name,
  content='loinc',
  content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2'
);

CREATE VIRTUAL TABLE IF NOT EXISTS loinc_variant_fts USING fts5(
  loinc_num UNINDEXED,
  variant_id UNINDEXED,
  iso_language UNINDEXED,
  iso_country UNINDEXED,
  long_common_name,
  linguistic_variant_display_name,
  shortname,
  component,
  related_names_2,
  tokenize='unicode61 remove_diacritics 2'
);

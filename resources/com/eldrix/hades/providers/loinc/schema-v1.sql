-- LOINC SQLite store, schema v1.
--
-- Source tables mirror the known LOINC CSV families streamed by
-- com.eldrix.hades.providers.loinc.loader. LoincTableCore/* is not
-- stored because it is duplicate packaging of LoincTable/* and is used
-- only as a version fallback when present.

CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);

-- LoincTable/Loinc.csv
CREATE TABLE IF NOT EXISTS loinc (
  loinc_num                    TEXT NOT NULL PRIMARY KEY,
  component                    TEXT,
  property                     TEXT,
  time_aspct                   TEXT,
  system                       TEXT,
  scale_typ                    TEXT,
  method_typ                   TEXT,
  class                        TEXT,
  version_last_changed         TEXT,
  chng_type                    TEXT,
  definition_description       TEXT,
  status                       TEXT,
  consumer_name                TEXT,
  classtype                    TEXT,
  formula                      TEXT,
  exmpl_answers                TEXT,
  survey_quest_text            TEXT,
  survey_quest_src             TEXT,
  units_required               TEXT,
  related_names_2              TEXT,
  shortname                    TEXT,
  order_obs                    TEXT,
  hl7_field_subfield_id        TEXT,
  external_copyright_notice    TEXT,
  example_units                TEXT,
  long_common_name             TEXT,
  example_ucum_units           TEXT,
  status_reason                TEXT,
  status_text                  TEXT,
  change_reason_public         TEXT,
  common_test_rank             INTEGER,
  common_order_rank            INTEGER,
  hl7_attachment_structure     TEXT,
  external_copyright_link      TEXT,
  panel_type                   TEXT,
  ask_at_order_entry           TEXT,
  associated_observations      TEXT,
  version_first_released       TEXT,
  valid_hl7_attachment_request TEXT,
  display_name                 TEXT
);

-- LoincTable/MapTo.csv
CREATE TABLE IF NOT EXISTS map_to (
  loinc   TEXT NOT NULL,
  map_to  TEXT NOT NULL,
  comment TEXT,
  PRIMARY KEY (loinc, map_to)
) WITHOUT ROWID;

-- LoincTable/SourceOrganization.csv
CREATE TABLE IF NOT EXISTS source_organization (
  id           TEXT NOT NULL PRIMARY KEY,
  copyright_id TEXT,
  name         TEXT,
  copyright    TEXT,
  terms_of_use TEXT,
  url          TEXT
) WITHOUT ROWID;

-- AccessoryFiles/ConsumerName/ConsumerName.csv
CREATE TABLE IF NOT EXISTS consumer_name (
  loinc_number  TEXT NOT NULL PRIMARY KEY,
  consumer_name TEXT
) WITHOUT ROWID;

-- AccessoryFiles/GroupFile/Group.csv
CREATE TABLE IF NOT EXISTS loinc_group (
  parent_group_id        TEXT,
  group_id               TEXT NOT NULL PRIMARY KEY,
  group_name             TEXT,
  archetype              TEXT,
  status                 TEXT,
  version_first_released TEXT
) WITHOUT ROWID;

-- AccessoryFiles/GroupFile/GroupAttributes.csv
CREATE TABLE IF NOT EXISTS group_attribute (
  parent_group_id TEXT,
  group_id        TEXT,
  type            TEXT,
  value           TEXT,
  PRIMARY KEY (parent_group_id, group_id, type, value)
) WITHOUT ROWID;

-- AccessoryFiles/GroupFile/GroupLoincTerms.csv
CREATE TABLE IF NOT EXISTS group_loinc_term (
  category         TEXT,
  group_id         TEXT NOT NULL,
  archetype        TEXT,
  loinc_number     TEXT NOT NULL,
  long_common_name TEXT,
  PRIMARY KEY (group_id, loinc_number)
) WITHOUT ROWID;

-- AccessoryFiles/GroupFile/ParentGroup.csv
CREATE TABLE IF NOT EXISTS parent_group (
  parent_group_id   TEXT NOT NULL PRIMARY KEY,
  parent_group_name TEXT,
  status            TEXT
) WITHOUT ROWID;

-- AccessoryFiles/GroupFile/ParentGroupAttributes.csv
CREATE TABLE IF NOT EXISTS parent_group_attribute (
  parent_group_id TEXT,
  type            TEXT,
  value           TEXT,
  PRIMARY KEY (parent_group_id, type, value)
) WITHOUT ROWID;

-- AccessoryFiles/PartFile/Part.csv
CREATE TABLE IF NOT EXISTS part (
  part_number       TEXT NOT NULL PRIMARY KEY,
  part_type_name    TEXT,
  part_name         TEXT,
  part_display_name TEXT,
  status            TEXT
);

-- AccessoryFiles/PartFile/LoincPartLink_Primary.csv
CREATE TABLE IF NOT EXISTS part_link_primary (
  loinc_number     TEXT NOT NULL,
  long_common_name TEXT,
  part_number      TEXT NOT NULL,
  part_name        TEXT,
  part_code_system TEXT,
  part_type_name   TEXT NOT NULL,
  link_type_name   TEXT NOT NULL,
  property         TEXT,
  PRIMARY KEY (loinc_number, part_number, part_type_name, link_type_name)
) WITHOUT ROWID;

-- AccessoryFiles/PartFile/LoincPartLink_Supplementary.csv
CREATE TABLE IF NOT EXISTS part_link_supplementary (
  loinc_number     TEXT NOT NULL,
  long_common_name TEXT,
  part_number      TEXT NOT NULL,
  part_name        TEXT,
  part_code_system TEXT,
  part_type_name   TEXT NOT NULL,
  link_type_name   TEXT NOT NULL,
  property         TEXT,
  PRIMARY KEY (loinc_number, part_number, part_type_name, link_type_name)
) WITHOUT ROWID;

-- AccessoryFiles/PartFile/PartRelatedCodeMapping.csv
CREATE TABLE IF NOT EXISTS part_related_code_mapping (
  part_number                      TEXT NOT NULL,
  part_name                        TEXT,
  part_type_name                   TEXT,
  ext_code_id                      TEXT NOT NULL,
  ext_code_display_name            TEXT,
  ext_code_system                  TEXT NOT NULL,
  equivalence                      TEXT,
  content_origin                   TEXT,
  ext_code_system_version          TEXT,
  ext_code_system_copyright_notice TEXT,
  PRIMARY KEY (part_number, ext_code_system, ext_code_id)
) WITHOUT ROWID;

-- AccessoryFiles/ComponentHierarchyBySystem/ComponentHierarchyBySystem.csv
CREATE TABLE IF NOT EXISTS component_hierarchy_by_system (
  path_to_root     TEXT NOT NULL DEFAULT '',
  sequence         INTEGER,
  immediate_parent TEXT NOT NULL,
  code             TEXT NOT NULL,
  code_text        TEXT,
  PRIMARY KEY (code, immediate_parent, path_to_root)
) WITHOUT ROWID;

-- AccessoryFiles/AnswerFile/AnswerList.csv
CREATE TABLE IF NOT EXISTS answer_list (
  answer_list_id                        TEXT,
  answer_list_name                      TEXT,
  answer_list_oid                       TEXT,
  ext_defined_yn                        TEXT,
  ext_defined_answer_list_code_system   TEXT,
  ext_defined_answer_list_link          TEXT,
  answer_string_id                      TEXT,
  local_answer_code                     TEXT,
  local_answer_code_system              TEXT,
  sequence_number                       INTEGER,
  display_text                          TEXT,
  ext_code_id                           TEXT,
  ext_code_display_name                 TEXT,
  ext_code_system                       TEXT,
  ext_code_system_version               TEXT,
  ext_code_system_copyright_notice      TEXT,
  subsequent_text_prompt                TEXT,
  description                           TEXT,
  score                                 TEXT,
  PRIMARY KEY (answer_list_id, answer_string_id)
) WITHOUT ROWID;

-- AccessoryFiles/AnswerFile/LoincAnswerListLink.csv
CREATE TABLE IF NOT EXISTS answer_list_link (
  loinc_number          TEXT,
  long_common_name      TEXT,
  answer_list_id        TEXT,
  answer_list_name      TEXT,
  answer_list_link_type TEXT,
  applicable_context    TEXT,
  PRIMARY KEY (loinc_number, answer_list_id, answer_list_link_type, applicable_context)
) WITHOUT ROWID;

-- AccessoryFiles/PanelsAndForms/PanelsAndForms.csv
CREATE TABLE IF NOT EXISTS panels_and_forms (
  parent_id                     TEXT,
  parent_loinc                  TEXT,
  parent_name                   TEXT,
  id                            TEXT,
  sequence                      INTEGER,
  loinc                         TEXT,
  loinc_name                    TEXT,
  display_name_for_form         TEXT,
  observation_required_in_panel TEXT,
  observation_id_in_form        TEXT,
  skip_logic_help_text          TEXT,
  default_value                 TEXT,
  entry_type                    TEXT,
  data_type_in_form             TEXT,
  data_type_source              TEXT,
  answer_sequence_override      INTEGER,
  condition_for_inclusion       TEXT,
  allowable_alternative         TEXT,
  observation_category          TEXT,
  context                       TEXT,
  consistency_checks            TEXT,
  relevance_equation            TEXT,
  coding_instructions           TEXT,
  question_cardinality          TEXT,
  answer_cardinality            TEXT,
  answer_list_id_override       TEXT,
  answer_list_type_override     TEXT,
  external_copyright_notice     TEXT,
  additional_copyright          TEXT,
  PRIMARY KEY (parent_id, id)
) WITHOUT ROWID;

-- AccessoryFiles/PanelsAndForms/Loinc.csv
CREATE TABLE IF NOT EXISTS panels_loinc (
  loinc_num                     TEXT,
  component                     TEXT,
  property                      TEXT,
  time_aspct                    TEXT,
  system                        TEXT,
  scale_typ                     TEXT,
  method_typ                    TEXT,
  class                         TEXT,
  version_last_changed          TEXT,
  chng_type                     TEXT,
  definition_description        TEXT,
  status                        TEXT,
  consumer_name                 TEXT,
  classtype                     TEXT,
  formula                       TEXT,
  exmpl_answers                 TEXT,
  survey_quest_text             TEXT,
  survey_quest_src              TEXT,
  units_required                TEXT,
  related_names_2               TEXT,
  shortname                     TEXT,
  order_obs                     TEXT,
  hl7_field_subfield_id         TEXT,
  external_copyright_notice     TEXT,
  example_units                 TEXT,
  long_common_name              TEXT,
  example_ucum_units            TEXT,
  status_reason                 TEXT,
  status_text                   TEXT,
  change_reason_public          TEXT,
  common_test_rank              INTEGER,
  common_order_rank             INTEGER,
  hl7_attachment_structure      TEXT,
  external_copyright_link       TEXT,
  panel_type                    TEXT,
  ask_at_order_entry            TEXT,
  associated_observations       TEXT,
  version_first_released        TEXT,
  valid_hl7_attachment_request  TEXT,
  display_name                  TEXT,
  PRIMARY KEY (loinc_num)
) WITHOUT ROWID;

-- AccessoryFiles/PanelsAndForms/AnswerList.csv
CREATE TABLE IF NOT EXISTS panels_answer_list (
  answer_list_id                        TEXT,
  answer_list_name                      TEXT,
  answer_list_oid                       TEXT,
  ext_defined_yn                        TEXT,
  ext_defined_answer_list_code_system   TEXT,
  ext_defined_answer_list_link          TEXT,
  answer_string_id                      TEXT,
  local_answer_code                     TEXT,
  local_answer_code_system              TEXT,
  sequence_number                       INTEGER,
  display_text                          TEXT,
  ext_code_id                           TEXT,
  ext_code_display_name                 TEXT,
  ext_code_system                       TEXT,
  ext_code_system_version               TEXT,
  ext_code_system_copyright_notice      TEXT,
  subsequent_text_prompt                TEXT,
  description                           TEXT,
  score                                 TEXT,
  PRIMARY KEY (answer_list_id, answer_string_id)
) WITHOUT ROWID;

-- AccessoryFiles/PanelsAndForms/LoincAnswerListLink.csv
CREATE TABLE IF NOT EXISTS panels_answer_list_link (
  loinc_number          TEXT,
  long_common_name      TEXT,
  answer_list_id        TEXT,
  answer_list_name      TEXT,
  answer_list_link_type TEXT,
  applicable_context    TEXT,
  PRIMARY KEY (loinc_number, answer_list_id, answer_list_link_type, applicable_context)
) WITHOUT ROWID;

-- AccessoryFiles/DocumentOntology/DocumentOntology.csv
CREATE TABLE IF NOT EXISTS document_ontology (
  loinc_number        TEXT,
  part_number         TEXT,
  part_type_name      TEXT,
  part_sequence_order INTEGER,
  part_name           TEXT,
  PRIMARY KEY (loinc_number, part_number, part_type_name)
) WITHOUT ROWID;

-- AccessoryFiles/ImagingDocuments/ImagingDocumentCodes.csv
CREATE TABLE IF NOT EXISTS imaging_document_code (
  loinc_num        TEXT NOT NULL PRIMARY KEY,
  long_common_name TEXT
) WITHOUT ROWID;

-- AccessoryFiles/LoincRsnaRadiologyPlaybook/LoincRsnaRadiologyPlaybook.csv
CREATE TABLE IF NOT EXISTS loinc_rsna_radiology_playbook (
  loinc_number        TEXT,
  long_common_name    TEXT,
  part_number         TEXT,
  part_type_name      TEXT,
  part_name           TEXT,
  part_sequence_order INTEGER,
  rid                 TEXT,
  preferred_name      TEXT,
  rpid                TEXT,
  long_name           TEXT,
  PRIMARY KEY (loinc_number, part_number, part_type_name)
) WITHOUT ROWID;

-- AccessoryFiles/LoincIeeeMedicalDeviceCodeMappingTable/LoincIeeeMedicalDeviceCodeMappingTable.csv
CREATE TABLE IF NOT EXISTS loinc_ieee_medical_device_mapping (
  loinc_num              TEXT,
  loinc_long_common_name TEXT,
  ieee_cf_code10         TEXT,
  ieee_refid             TEXT,
  equivalence            TEXT,
  PRIMARY KEY (loinc_num, ieee_refid)
) WITHOUT ROWID;

-- AccessoryFiles/LoincUniversalLabOrdersValueSet/LoincUniversalLabOrdersValueSet.csv
CREATE TABLE IF NOT EXISTS loinc_universal_lab_orders_value_set (
  loinc_num        TEXT NOT NULL PRIMARY KEY,
  long_common_name TEXT,
  order_obs        TEXT
) WITHOUT ROWID;

-- AccessoryFiles/LinguisticVariants/LinguisticVariants.csv
CREATE TABLE IF NOT EXISTS linguistic_variant_catalog (
  id            TEXT NOT NULL PRIMARY KEY,
  iso_language  TEXT,
  iso_country   TEXT,
  language_name TEXT,
  producer      TEXT
) WITHOUT ROWID;

-- AccessoryFiles/LinguisticVariants/<lang><id>LinguisticVariant.csv
CREATE TABLE IF NOT EXISTS linguistic_variant_row (
  variant_id                       TEXT,
  loinc_num                        TEXT,
  component                        TEXT,
  property                         TEXT,
  time_aspct                       TEXT,
  system                           TEXT,
  scale_typ                        TEXT,
  method_typ                       TEXT,
  class                            TEXT,
  shortname                        TEXT,
  long_common_name                 TEXT,
  related_names_2                  TEXT,
  linguistic_variant_display_name  TEXT,
  PRIMARY KEY (variant_id, loinc_num)
) WITHOUT ROWID;

-- AccessoryFiles/ChangeSnapshot/LoincChangeSnapshot.csv
CREATE TABLE IF NOT EXISTS loinc_change_snapshot (
  version_effective TEXT,
  loinc_num         TEXT,
  property          TEXT,
  value_prior       TEXT,
  value_current     TEXT,
  change_reason     TEXT,
  PRIMARY KEY (version_effective, loinc_num, property)
) WITHOUT ROWID;

-- AccessoryFiles/ChangeSnapshot/PartChangeSnapshot.csv
CREATE TABLE IF NOT EXISTS part_change_snapshot (
  version_effective TEXT,
  part_number       TEXT,
  property          TEXT,
  value_prior       TEXT,
  value_current     TEXT,
  change_reason     TEXT,
  PRIMARY KEY (version_effective, part_number, property)
) WITHOUT ROWID;

-- AccessoryFiles/Updates/Updates.csv
CREATE TABLE IF NOT EXISTS updates (
  rec_type   TEXT,
  loinc_num  TEXT,
  component  TEXT,
  property   TEXT,
  time_aspct TEXT,
  system     TEXT,
  scale_typ  TEXT,
  method_typ TEXT,
  class      TEXT,
  PRIMARY KEY (rec_type, loinc_num)
) WITHOUT ROWID;

-- AccessoryFiles/Updates/UpdatesAdditionalFields.csv
CREATE TABLE IF NOT EXISTS updates_additional_fields (
  rec_type                      TEXT,
  loinc_num                     TEXT,
  component                     TEXT,
  property                      TEXT,
  time_aspct                    TEXT,
  system                        TEXT,
  scale_typ                     TEXT,
  method_typ                    TEXT,
  class                         TEXT,
  version_last_changed          TEXT,
  chng_type                     TEXT,
  definition_description        TEXT,
  status                        TEXT,
  consumer_name                 TEXT,
  classtype                     TEXT,
  formula                       TEXT,
  exmpl_answers                 TEXT,
  survey_quest_text             TEXT,
  survey_quest_src              TEXT,
  units_required                TEXT,
  related_names_2               TEXT,
  shortname                     TEXT,
  order_obs                     TEXT,
  hl7_field_subfield_id         TEXT,
  external_copyright_notice     TEXT,
  example_units                 TEXT,
  long_common_name              TEXT,
  example_ucum_units            TEXT,
  status_reason                 TEXT,
  status_text                   TEXT,
  change_reason_public          TEXT,
  common_test_rank              INTEGER,
  common_order_rank             INTEGER,
  hl7_attachment_structure      TEXT,
  external_copyright_link       TEXT,
  panel_type                    TEXT,
  ask_at_order_entry            TEXT,
  associated_observations       TEXT,
  version_first_released        TEXT,
  valid_hl7_attachment_request  TEXT,
  display_name                  TEXT,
  PRIMARY KEY (rec_type, loinc_num)
) WITHOUT ROWID;

-- Full-text search over LOINC terms. External-content FTS5 references
-- `loinc.rowid`; the native LOINC index step rebuilds it after source
-- rows have been imported.
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

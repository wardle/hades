(ns com.eldrix.hades.providers.loinc.model
  "Shared model of raw LOINC release files and their SQLite source tables."
  (:require [clojure.string :as str])
  (:import (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)))

(defn- loader-type [name]
  (keyword "com.eldrix.hades.providers.loinc.loader" name))

(def loinc-system "http://loinc.org")

(defn- url-encode [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- url-decode [s]
  (URLDecoder/decode (str s) StandardCharsets/UTF_8))

(def conceptmaps
  "Provider-owned canonical URLs for ConceptMaps derived from LOINC CSVs
  that do not themselves carry FHIR ConceptMap.url values. Target system
  URLs come from the source CSV where present; RSNA and IEEE mappings do
  not include FHIR canonical systems, so Hades assigns explicit systems
  here rather than scattering them through provider code."
  {:map-to
   {:url "http://loinc.org/cm/map-to"
    :system loinc-system
    :target loinc-system
    :title "LOINC MapTo replacements"}

   :part-related
   {:url-prefix "http://loinc.org/cm/part-related-code-mapping/"
    :system loinc-system
    :title-prefix "LOINC part related code mappings"}

   :ieee-medical-device
   {:url "http://loinc.org/cm/ieee-medical-device"
    :system loinc-system
    :target "urn:iso:std:iso:11073:10101"
    :title "LOINC IEEE 11073-10101 medical device mappings"}

   :rsna-rid
   {:url "http://loinc.org/cm/rsna-playbook/rid"
    :system loinc-system
    :target "http://www.radlex.org"
    :title "LOINC RSNA Playbook RadLex RID mappings"}

   :rsna-rpid
   {:url "http://loinc.org/cm/rsna-playbook/rpid"
    :system loinc-system
    :target "http://www.rsna.org/RadLex_Playbook"
    :title "LOINC RSNA Playbook RPID mappings"}})

(defn conceptmap
  [id]
  (get conceptmaps id))

(defn part-related-conceptmap-url
  [ext-code-system]
  (str (get-in conceptmaps [:part-related :url-prefix])
       (url-encode ext-code-system)))

(defn part-related-conceptmap-target
  [url]
  (let [prefix (get-in conceptmaps [:part-related :url-prefix])]
    (when (and (string? url) (str/starts-with? url prefix))
      (url-decode (subs url (count prefix))))))

(def loinc-cols
  ["LOINC_NUM" "COMPONENT" "PROPERTY" "TIME_ASPCT" "SYSTEM" "SCALE_TYP"
   "METHOD_TYP" "CLASS" "VersionLastChanged" "CHNG_TYPE"
   "DefinitionDescription" "STATUS" "CONSUMER_NAME" "CLASSTYPE"
   "FORMULA" "EXMPL_ANSWERS" "SURVEY_QUEST_TEXT" "SURVEY_QUEST_SRC"
   "UNITSREQUIRED" "RELATEDNAMES2" "SHORTNAME" "ORDER_OBS"
   "HL7_FIELD_SUBFIELD_ID" "EXTERNAL_COPYRIGHT_NOTICE" "EXAMPLE_UNITS"
   "LONG_COMMON_NAME" "EXAMPLE_UCUM_UNITS" "STATUS_REASON" "STATUS_TEXT"
   "CHANGE_REASON_PUBLIC" "COMMON_TEST_RANK" "COMMON_ORDER_RANK"
   "HL7_ATTACHMENT_STRUCTURE" "EXTERNAL_COPYRIGHT_LINK" "PanelType"
   "AskAtOrderEntry" "AssociatedObservations" "VersionFirstReleased"
   "ValidHL7AttachmentRequest" "DisplayName"])

(def answer-list-cols
  ["AnswerListId" "AnswerListName" "AnswerListOID" "ExtDefinedYN"
   "ExtDefinedAnswerListCodeSystem" "ExtDefinedAnswerListLink"
   "AnswerStringId" "LocalAnswerCode" "LocalAnswerCodeSystem"
   "SequenceNumber" "DisplayText" "ExtCodeId" "ExtCodeDisplayName"
   "ExtCodeSystem" "ExtCodeSystemVersion" "ExtCodeSystemCopyrightNotice"
   "SubsequentTextPrompt" "Description" "Score"])

(def answer-list-link-cols
  ["LoincNumber" "LongCommonName" "AnswerListId" "AnswerListName"
   "AnswerListLinkType" "ApplicableContext"])

(defn linguistic-variant-meta
  [path]
  (or (some->> path
               (re-matches #"AccessoryFiles/LinguisticVariants/([a-z]{2})([A-Z]{2})(\d+)LinguisticVariant\.csv")
               last
               (hash-map :variant-id))
      (throw (ex-info (str "Cannot derive linguistic variant identity from " path)
                      {:path path}))))

(defn linguistic-variant-table
  [{:keys [variant-id]} columns rows]
  {:columns (into ["variant_id"] columns)
   :rows    (mapv #(into [variant-id] %) rows)})

(def column-overrides
  {"Group"         "group_name"
   "ParentGroup"   "parent_group_name"
   "PROPERTY"      "property"
   "UNITSREQUIRED" "units_required"
   "RELATEDNAMES2" "related_names_2"})

(defn column-name
  [heading]
  (or (column-overrides heading)
      (-> heading
          (str/replace #"([A-Z]+)([A-Z][a-z])" "$1_$2")
          (str/replace #"([a-z0-9])([A-Z])" "$1_$2")
          (str/replace #"[ -]+" "_")
          str/lower-case)))

(def release-files
  "Known LOINC release files. `:type` is the streamed block type,
  `:table-name` is the SQLite source table, optional
  `:column-transforms` rewrite individual column values, and optional
  `:table-transform` handles files whose row identity includes
  per-file metadata. Store writers keep `:columns` and `:rows` as
  ordered vectors."
  [{:path "LoincTable/Loinc.csv"
    :required? true
    :type (loader-type "loinc")
    :table-name "loinc"
    :cols loinc-cols}
   {:path "LoincTable/MapTo.csv"
    :type (loader-type "map-to")
    :table-name "map_to"
    :cols ["LOINC" "MAP_TO" "COMMENT"]}
   {:path "LoincTable/SourceOrganization.csv"
    :type (loader-type "source-organization")
    :table-name "source_organization"
    :cols ["ID" "COPYRIGHT_ID" "NAME" "COPYRIGHT" "TERMS_OF_USE" "URL"]}
   {:path "AccessoryFiles/ConsumerName/ConsumerName.csv"
    :type (loader-type "consumer-name")
    :table-name "consumer_name"
    :cols ["LoincNumber" "ConsumerName"]}
   {:path "AccessoryFiles/GroupFile/Group.csv"
    :type (loader-type "group")
    :table-name "loinc_group"
    :cols ["ParentGroupId" "GroupId" "Group" "Archetype" "Status" "VersionFirstReleased"]}
   {:path "AccessoryFiles/GroupFile/GroupAttributes.csv"
    :type (loader-type "group-attribute")
    :table-name "group_attribute"
    :cols ["ParentGroupId" "GroupId" "Type" "Value"]}
   {:path "AccessoryFiles/GroupFile/GroupLoincTerms.csv"
    :type (loader-type "group-loinc-term")
    :table-name "group_loinc_term"
    :cols ["Category" "GroupId" "Archetype" "LoincNumber" "LongCommonName"]}
   {:path "AccessoryFiles/GroupFile/ParentGroup.csv"
    :type (loader-type "parent-group")
    :table-name "parent_group"
    :cols ["ParentGroupId" "ParentGroup" "Status"]}
   {:path "AccessoryFiles/GroupFile/ParentGroupAttributes.csv"
    :type (loader-type "parent-group-attribute")
    :table-name "parent_group_attribute"
    :cols ["ParentGroupId" "Type" "Value"]}
   {:path "AccessoryFiles/PartFile/Part.csv"
    :type (loader-type "part")
    :table-name "part"
    :cols ["PartNumber" "PartTypeName" "PartName" "PartDisplayName" "Status"]}
   {:path "AccessoryFiles/PartFile/LoincPartLink_Primary.csv"
    :type (loader-type "part-link-primary")
    :table-name "part_link_primary"
    :cols ["LoincNumber" "LongCommonName" "PartNumber" "PartName"
           "PartCodeSystem" "PartTypeName" "LinkTypeName" "Property"]}
   {:path "AccessoryFiles/PartFile/LoincPartLink_Supplementary.csv"
    :type (loader-type "part-link-supplementary")
    :table-name "part_link_supplementary"
    :cols ["LoincNumber" "LongCommonName" "PartNumber" "PartName"
           "PartCodeSystem" "PartTypeName" "LinkTypeName" "Property"]}
   {:path "AccessoryFiles/PartFile/PartRelatedCodeMapping.csv"
    :type (loader-type "part-related-code-mapping")
    :table-name "part_related_code_mapping"
    :cols ["PartNumber" "PartName" "PartTypeName" "ExtCodeId"
           "ExtCodeDisplayName" "ExtCodeSystem" "Equivalence" "ContentOrigin"
           "ExtCodeSystemVersion" "ExtCodeSystemCopyrightNotice"]}
   {:path "AccessoryFiles/ComponentHierarchyBySystem/ComponentHierarchyBySystem.csv"
    :type (loader-type "hierarchy")
    :table-name "component_hierarchy_by_system"
    :cols ["PATH_TO_ROOT" "SEQUENCE" "IMMEDIATE_PARENT" "CODE" "CODE_TEXT"]}
   {:path "AccessoryFiles/AnswerFile/AnswerList.csv"
    :type (loader-type "answer-list")
    :table-name "answer_list"
    :cols answer-list-cols}
   {:path "AccessoryFiles/AnswerFile/LoincAnswerListLink.csv"
    :type (loader-type "answer-list-link")
    :table-name "answer_list_link"
    :cols answer-list-link-cols}
   {:path "AccessoryFiles/PanelsAndForms/PanelsAndForms.csv"
    :type (loader-type "panel")
    :table-name "panels_and_forms"
    :cols ["ParentId" "ParentLoinc" "ParentName" "ID" "SEQUENCE" "Loinc"
           "LoincName" "DisplayNameForForm" "ObservationRequiredInPanel"
           "ObservationIdInForm" "SkipLogicHelpText" "DefaultValue" "EntryType"
           "DataTypeInForm" "DataTypeSource" "AnswerSequenceOverride"
           "ConditionForInclusion" "AllowableAlternative" "ObservationCategory"
           "Context" "ConsistencyChecks" "RelevanceEquation" "CodingInstructions"
           "QuestionCardinality" "AnswerCardinality" "AnswerListIdOverride"
           "AnswerListTypeOverride" "EXTERNAL_COPYRIGHT_NOTICE" "AdditionalCopyright"]}
   {:path "AccessoryFiles/PanelsAndForms/Loinc.csv"
    :type (loader-type "panels-loinc")
    :table-name "panels_loinc"
    :cols loinc-cols}
   {:path "AccessoryFiles/PanelsAndForms/AnswerList.csv"
    :type (loader-type "panels-answer-list")
    :table-name "panels_answer_list"
    :cols answer-list-cols}
   {:path "AccessoryFiles/PanelsAndForms/LoincAnswerListLink.csv"
    :type (loader-type "panels-answer-list-link")
    :table-name "panels_answer_list_link"
    :cols answer-list-link-cols}
   {:path "AccessoryFiles/DocumentOntology/DocumentOntology.csv"
    :type (loader-type "document-ontology")
    :table-name "document_ontology"
    :cols ["LoincNumber" "PartNumber" "PartTypeName" "PartSequenceOrder" "PartName"]}
   {:path "AccessoryFiles/ImagingDocuments/ImagingDocumentCodes.csv"
    :type (loader-type "imaging-document")
    :table-name "imaging_document_code"
    :cols ["LOINC_NUM" "LONG_COMMON_NAME"]}
   {:path "AccessoryFiles/LoincRsnaRadiologyPlaybook/LoincRsnaRadiologyPlaybook.csv"
    :type (loader-type "rsna-playbook")
    :table-name "loinc_rsna_radiology_playbook"
    :cols ["LoincNumber" "LongCommonName" "PartNumber" "PartTypeName" "PartName"
           "PartSequenceOrder" "RID" "PreferredName" "RPID" "LongName"]}
   {:path "AccessoryFiles/LoincIeeeMedicalDeviceCodeMappingTable/LoincIeeeMedicalDeviceCodeMappingTable.csv"
    :type (loader-type "ieee-medical-device-mapping")
    :table-name "loinc_ieee_medical_device_mapping"
    :cols ["LOINC_NUM" "LOINC_LONG_COMMON_NAME" "IEEE_CF_CODE10" "IEEE_REFID" "EQUIVALENCE"]}
   {:path "AccessoryFiles/LoincUniversalLabOrdersValueSet/LoincUniversalLabOrdersValueSet.csv"
    :type (loader-type "universal-lab-orders")
    :table-name "loinc_universal_lab_orders_value_set"
    :cols ["LOINC_NUM" "LONG_COMMON_NAME" "ORDER_OBS"]}
   {:path "AccessoryFiles/LinguisticVariants/LinguisticVariants.csv"
    :type (loader-type "linguistic-variant-catalog")
    :table-name "linguistic_variant_catalog"
    :cols ["ID" "ISO_LANGUAGE" "ISO_COUNTRY" "LANGUAGE_NAME" "PRODUCER"]}
   {:path #"AccessoryFiles/LinguisticVariants/.+LinguisticVariant\.csv"
    :type (loader-type "linguistic-variant")
    :table-name "linguistic_variant_row"
    :block-meta linguistic-variant-meta
    :table-transform linguistic-variant-table
    :cols ["LOINC_NUM" "COMPONENT" "PROPERTY" "TIME_ASPCT" "SYSTEM" "SCALE_TYP"
           "METHOD_TYP" "CLASS" "SHORTNAME" "LONG_COMMON_NAME" "RELATEDNAMES2"
           "LinguisticVariantDisplayName"]}
   {:path "AccessoryFiles/ChangeSnapshot/LoincChangeSnapshot.csv"
    :type (loader-type "loinc-change-snapshot")
    :table-name "loinc_change_snapshot"
    :cols ["VersionEffective" "LOINC_NUM" "Property" "ValuePrior"
           "ValueCurrent" "ChangeReason"]}
   {:path "AccessoryFiles/ChangeSnapshot/PartChangeSnapshot.csv"
    :type (loader-type "part-change-snapshot")
    :table-name "part_change_snapshot"
    :cols ["VersionEffective" "PartNumber" "PROPERTY" "ValuePrior"
           "ValueCurrent" "ChangeReason"]}
   {:path "AccessoryFiles/Updates/Updates.csv"
    :type (loader-type "updates")
    :table-name "updates"
    :cols ["RecType" "LOINC_NUM" "COMPONENT" "PROPERTY" "TIME_ASPCT"
           "SYSTEM" "SCALE_TYP" "METHOD_TYP" "CLASS"]}
   {:path "AccessoryFiles/Updates/UpdatesAdditionalFields.csv"
    :type (loader-type "updates-additional-fields")
    :table-name "updates_additional_fields"
    :cols (into ["RecType"] loinc-cols)}])

(def release-files-by-type
  (into {} (map (juxt :type identity)) release-files))

(defn release-file
  [type]
  (or (release-files-by-type type)
      (throw (ex-info (str "Unknown LOINC release file type: " type)
                      {:type type}))))

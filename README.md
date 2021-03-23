# hades

HL7 FHIR terminology server.

A HL7 FHIR facade over [hermes](https://github.com/wardle/hermes), a generic SNOMED CT terminology server. 
The HL7 FHIR specification includes support for a terminology API, including looking up codes and translation. 

Here are some examples of using the FHIR terminology API:

#### Lookup a SNOMED code

http://localhost/fhir/CodeSystem/$lookup?system=http://snomed.info/sct&code=263495000&_format=json
#### Design notes
see https://confluence.ihtsdotools.org/display/FHIR/Implementing+Terminology+Services+with+SNOMED+CT

The operations that will need to be supported are
$lookup (on CodeSystem resource)
$expand (on ValueSet resource) - e.g. ECL, filters
$subsumes (on CodeSystem resource)
$closure (on ConceptMap resource)
$translate (on ConceptMap resource)
$validate-code (on ValueSet resource)
$validate-code (on CodeSystem resource)

All of this functionality is obviously available in `hermes` but we need to expose using these
FHIR operations.

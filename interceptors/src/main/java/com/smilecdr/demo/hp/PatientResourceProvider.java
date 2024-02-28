package com.smilecdr.demo.hp;

import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Patient;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class PatientResourceProvider implements IResourceProvider {

	@Search(allowUnknownParams = true)
	public List<Patient> search(
		@OptionalParam(name = Patient.SP_IDENTIFIER) TokenParam theIdentifier) {

		if (theIdentifier != null) {
			if ("http://example.com".equals(theIdentifier.getSystem())) {
				if ("12345".equals(theIdentifier.getValue())) {
                    return List.of(createExamplePatient("http://example.com", "12345"));
				}
			}
			if ("https://github.com/synthetichealth/synthea".equals(theIdentifier.getSystem())) {
				if ("1218f05f-d919-c02d-d836-01a7caa569fb".equals(theIdentifier.getValue())) {
					return List.of(createExamplePatient("https://github.com/synthetichealth/synthea", "1218f05f-d919-c02d-d836-01a7caa569fb"));
				}
			}
		} else {
			return List.of(createExamplePatient("http://example.com", "12345"));
		}

		// Otherwise, return an empty list of patents
		return List.of();
	}

	@NotNull
	private static Patient createExamplePatient(String theIdentifierSystem, String theIdentifierValue) {
		Patient patient = new Patient();
		patient.setId("Patient/123");
		patient.addIdentifier()
			.setSystem(theIdentifierSystem)
			.setValue(theIdentifierValue);
		patient.addName()
			.setFamily("Simpson")
			.addGiven("Homer")
			.addGiven("J");
		patient.addAddress()
			.addLine("342 Evergreen Terrace")
			.setCity("Springfield")
			.setCountry("USA");
		return patient;
	}

	@Override
	public Class<Patient> getResourceType() {
		return Patient.class;
	}
}

package com.smilecdr.demo.hp;

import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Patient;

import java.util.Collections;
import java.util.List;

public class PatientResourceProvider implements IResourceProvider {

	@Search(allowUnknownParams = true)
	public List<Patient> search(
		@OptionalParam(name = Patient.SP_IDENTIFIER) TokenParam theIdentifier) {

		if (theIdentifier != null) {
			if ("http://example.com".equals(theIdentifier.getSystem())) {
				if ("12345".equals(theIdentifier.getValue())) {
					Patient patient = new Patient();
					patient.addIdentifier()
						.setSystem("http://example.com")
						.setValue("12345");
					patient.addName()
						.setFamily("Simpson")
						.addGiven("Homer")
						.addGiven("J");
					patient.addAddress()
						.addLine("342 Evergreen Terrace")
						.setCity("Springfield")
						.setCountry("USA");
					return List.of(patient);
				}
			}
		}

		// Otherwise, return an empty list of patents
		return List.of();
	}

	@Override
	public Class<Patient> getResourceType() {
		return Patient.class;
	}
}

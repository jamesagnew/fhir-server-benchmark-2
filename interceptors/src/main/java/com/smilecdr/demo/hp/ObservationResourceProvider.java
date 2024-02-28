package com.smilecdr.demo.hp;

import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;

import java.util.List;

public class ObservationResourceProvider implements IResourceProvider {

	@Search(allowUnknownParams = true)
	public List<Observation> search() {
		return List.of();
	}

	@Override
	public Class<Observation> getResourceType() {
		return Observation.class;
	}
}

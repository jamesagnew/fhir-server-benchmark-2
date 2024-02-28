package com.smilecdr.demo.hp;

import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Encounter;

import java.util.List;

public class EncounterResourceProvider implements IResourceProvider {

	@Search(allowUnknownParams = true)
	public List<Encounter> search() {
		return List.of();
	}

	@Override
	public Class<Encounter> getResourceType() {
		return Encounter.class;
	}
}

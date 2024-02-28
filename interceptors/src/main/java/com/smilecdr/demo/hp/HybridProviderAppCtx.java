package com.smilecdr.demo.hp;

import ca.uhn.fhir.rest.server.IResourceProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class HybridProviderAppCtx {

	/**
	 * This bean is a list of Resource Provider classes, each one
	 * of which implements FHIR operations for a specific resource
	 * type.
	 */
	@Bean(name = "resourceProviders")
	public List<IResourceProvider> resourceProviders() {
		List<IResourceProvider> retVal = new ArrayList<>();

		retVal.add(new PatientResourceProvider());
		retVal.add(new CommunicationResourceProvider());
		retVal.add(new ObservationResourceProvider());
		retVal.add(new EncounterResourceProvider());

		return retVal;
	}

	/**
	 * This bean is a list of Plain Provider classes, each one
	 * of which implements FHIR operations for system-level
	 * FHIR operations.
	 */
	@Bean(name = "plainProviders")
	public List<Object> plainProviders() {
		return List.of();
	}

}

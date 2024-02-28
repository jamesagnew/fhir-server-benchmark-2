package com.smilecdr.demo.hp;

import ca.uhn.fhir.rest.annotation.RequiredParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CommunicationResourceProvider implements IResourceProvider {
	private static final Logger ourLog = LoggerFactory.getLogger(CommunicationResourceProvider.class);

	@Search(allowUnknownParams = true)
	public List<Communication> search(
		@RequiredParam(name=Communication.SP_PATIENT) ReferenceParam thePatient) {

		ourLog.info("Fetching Communication resources for {}", thePatient.getValue());

		Communication communication0 = new Communication();
		communication0.setId("Communication/A0");
		communication0.getSubject().setReference(thePatient.getValue());
		communication0.addPayload()
			.setContent(new StringType("This is an example communication"));

		Communication communication1 = new Communication();
		communication1.setId("Communication/A1");
		communication1.getSubject().setReference(thePatient.getValue());
		communication1.addPayload()
			.setContent(new StringType("Another message is here"));

		return List.of(communication1, communication1);
	}

	@Override
	public Class<Communication> getResourceType() {
		return Communication.class;
	}

}

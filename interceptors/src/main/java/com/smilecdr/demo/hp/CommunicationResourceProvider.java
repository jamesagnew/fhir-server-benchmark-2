package com.smilecdr.demo.hp;

import ca.uhn.fhir.rest.annotation.RequiredParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.StringType;

import java.util.List;

public class CommunicationResourceProvider implements IResourceProvider {

	@Search(allowUnknownParams = true)
	public List<Communication> search(
		@RequiredParam(name=Communication.SP_SUBJECT) ReferenceParam theSubject) {

		Communication communication0 = new Communication();
		communication0.getSubject().setReference(theSubject.getValue());
		communication0.addPayload()
			.setContent(new StringType("This is an example communication"));

		Communication communication1 = new Communication();
		communication1.getSubject().setReference(theSubject.getValue());
		communication1.addPayload()
			.setContent(new StringType("Another message is here"));

		return List.of(communication1, communication1);
	}

	@Override
	public Class<Communication> getResourceType() {
		return Communication.class;
	}

}

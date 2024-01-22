package com.smilecdr.demo.benchmark2;

import ca.cdr.api.fhir.interceptor.CdrHook;
import ca.cdr.api.fhir.interceptor.CdrPointcut;
import ca.cdr.api.fhirgw.json.GatewayTargetJson;
import ca.cdr.api.fhirgw.model.SearchRequest;
import ca.cdr.api.fhirgw.model.TransactionRequest;
import ca.cdr.api.fhirgw.model.UpdateRequest;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.apicatalog.jsonld.StringUtils;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayInterceptor {
	private static final Logger ourLog = LoggerFactory.getLogger(GatewayInterceptor.class);

	@CdrHook(CdrPointcut.FHIRGW_SEARCH_TARGET_PREINVOKE)
	public void searchSelectRoute(SearchRequest theRequest, GatewayTargetJson theTarget) {
		if (theRequest.getResourceType().equals("SearchParameter")) {
			boolean skip = !theTarget.getId().equals("Read-def");
			theRequest.setSkip(skip);
			return;
		}

		String patientIdRaw = theRequest.getParameter("patient");
		if (patientIdRaw == null) {
			return;
		}
		if (!patientIdRaw.startsWith("ms") && !patientIdRaw.contains("-")) {
			throw new InvalidRequestException("Invalid 'patient' parameter provided: " + patientIdRaw);
		}

		String partitionId = patientIdRaw.substring(2, patientIdRaw.indexOf('-'));
		int partition = Integer.parseInt(partitionId);
		boolean skip = !theTarget.getId().equals("Read-ms" + partition);
		theRequest.setSkip(skip);
	}

	@CdrHook(CdrPointcut.FHIRGW_TRANSACTION_TARGET_PREINVOKE)
	public void transactionSelectRoute(TransactionRequest theRequest, GatewayTargetJson theTarget) {
		Bundle requestBundle = (Bundle) theRequest.getRequestBundle();

		Resource patient = requestBundle
			.getEntry()
			.stream()
			.map(t -> t.getResource())
			.filter(t -> t instanceof Patient)
			.findFirst()
			.orElseThrow(() -> new InternalErrorException("No Patient resource found in bundle"));
		String patientId = patient.getIdElement().getIdPart();
		int partition = patientIdToPartitionId(patientId);
		boolean skip = !theTarget.getId().equals("Write-ms" + partition + "-noprefix");
		theRequest.setSkip(skip);
	}


	private static int patientIdToPartitionId(String patientIdRaw) {
		String patientId = new IdType(patientIdRaw).getIdPart();
		Validate.notBlank(patientId, "No patient ID provided");
		int partition = Math.abs(patientId.hashCode() % BenchmarkConstants.MEGASCALE_COUNT) + 1;
		return partition;
	}


}

package com.smilecdr.demo.benchmark2;

import ca.cdr.api.fhir.interceptor.CdrHook;
import ca.cdr.api.fhir.interceptor.CdrPointcut;
import ca.cdr.api.fhirgw.json.GatewayTargetJson;
import ca.cdr.api.fhirgw.model.CreateRequest;
import ca.cdr.api.fhirgw.model.SearchRequest;
import ca.cdr.api.fhirgw.model.TransactionRequest;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.smilecdr.demo.benchmark2.BenchmarkMegaScaleConnectionProvidingInterceptor.getPropertyNotNull;

public class GatewayInterceptor {
	private static final Logger ourLog = LoggerFactory.getLogger(GatewayInterceptor.class);
	private final int myMegaScaleCount;

	public GatewayInterceptor() {
		myMegaScaleCount = Integer.parseInt(getPropertyNotNull("MEGASCALE_COUNT"));
	}

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
		if (!patientIdRaw.startsWith("Patient/ms") || !patientIdRaw.contains("-")) {
			throw new InvalidRequestException("Invalid 'patient' parameter provided: " + patientIdRaw);
		}

		String partitionId = patientIdRaw.substring("Patient/ms".length(), patientIdRaw.indexOf('-'));
		int partition = Integer.parseInt(partitionId);
		boolean skip = !theTarget.getId().equals("Read-ms" + partition);
		theRequest.setSkip(skip);
	}

	@CdrHook(CdrPointcut.FHIRGW_CREATE_TARGET_PREINVOKE)
	public void createSelectRoute(CreateRequest theRequest, GatewayTargetJson theTarget) {

		Observation obs = (Observation) theRequest.getResource();
		String patientIdRaw = obs.getSubject().getReference();
		if (!patientIdRaw.startsWith("Patient/ms") || !patientIdRaw.contains("-")) {
			throw new InvalidRequestException("Invalid subject specified provided: " + patientIdRaw);
		}

		String partitionId = patientIdRaw.substring("Patient/ms".length(), patientIdRaw.indexOf('-'));
		int partition = Integer.parseInt(partitionId);
		boolean skip = !theTarget.getId().equals("Write-ms" + partition);
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


	private int patientIdToPartitionId(String patientIdRaw) {
		String patientId = new IdType(patientIdRaw).getIdPart();
		Validate.notBlank(patientId, "No patient ID provided");
		int partition = Math.abs(patientId.hashCode() % myMegaScaleCount) + 1;
		return partition;
	}


}

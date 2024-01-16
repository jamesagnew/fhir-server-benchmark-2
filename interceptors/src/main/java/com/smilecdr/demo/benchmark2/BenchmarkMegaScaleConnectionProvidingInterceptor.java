/*-
 * #%L
 * Smile CDR - CDR
 * %%
 * Copyright (C) 2016 - 2023 Smile CDR, Inc.
 * %%
 * All rights reserved.
 * #L%
 */
package com.smilecdr.demo.benchmark2;

import ca.cdr.api.fhir.interceptor.CdrHook;
import ca.cdr.api.fhir.interceptor.CdrPointcut;
import ca.cdr.api.persistence.megascale.MegaScaleCredentialRequestJson;
import ca.cdr.api.persistence.megascale.MegaScaleCredentialResponseJson;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import jakarta.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import static com.smilecdr.demo.benchmark2.BenchmarkConstants.MEGASCALE_COUNT;

/**
 * This interceptor is invoked when initiating a request for a MegaScale
 * connection for a specific partition. It returns the associated JDBC
 * URL, username and password to use for that partition.
 *
 * Responses are cached, so this pointcut will not be repeatedly invoked
 * every time a partition is accessed and this interceptor is therefore
 * not considered performance critical if latency is incurred.
 */
@Interceptor
public class BenchmarkMegaScaleConnectionProvidingInterceptor {

	@CdrHook(CdrPointcut.STORAGE_MEGASCALE_PROVIDE_DB_INFO)
	public MegaScaleCredentialResponseJson provideMegaScaleCredentials(MegaScaleCredentialRequestJson theRequest) {

		MegaScaleCredentialResponseJson response;
		if (theRequest.getPartitionId() != 0) {
			response = createCredentialResponse(theRequest.getPartitionId());
		} else if (!StringUtils.isBlank(theRequest.getPartitionName())){
			String partitionName = theRequest.getPartitionName();
			Validate.isTrue(partitionName.startsWith("MS"));
			int partitionNumber = Integer.parseInt(partitionName.substring(2));
			response = createCredentialResponse(partitionNumber);
		} else {
			throw new InvalidRequestException("No way to identify this partition!");
		}

		return response;
	}

	@Nonnull
	private static MegaScaleCredentialResponseJson createCredentialResponse(int partitionNumber) {
		Validate.isTrue(partitionNumber >= 1);
		Validate.isTrue(partitionNumber <= MEGASCALE_COUNT);

		MegaScaleCredentialResponseJson retVal = new MegaScaleCredentialResponseJson();
		retVal.setDatabaseUrl("jdbc:postgresql://localhost:5432/cdr_ms" + partitionNumber);
		retVal.setDatabaseUsername("cdr_ms" + partitionNumber);
		retVal.setDatabasePassword("cdr");
		return retVal;
	}


}

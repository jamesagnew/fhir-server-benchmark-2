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
import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import jakarta.annotation.Nonnull;
import org.apache.commons.lang3.Validate;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * This interceptor is invoked when initiating a request for a MegaScale
 * connection for a specific partition. It returns the associated JDBC
 * URL, username and password to use for that partition.
 * <p>
 * Responses are cached, so this pointcut will not be repeatedly invoked
 * every time a partition is accessed and this interceptor is therefore
 * not considered performance critical if latency is incurred.
 */
@Interceptor
public class BenchmarkMegaScaleConnectionProvidingInterceptor {

	private final int myMegaScaleCount;
	private final String[] myUrls;
	private final String[] myUsers;
	private final String[] myPasswords;

	/**
	 * Constructor``
	 */
	public BenchmarkMegaScaleConnectionProvidingInterceptor() {
		myMegaScaleCount = Integer.parseInt(getPropertyNotNull("MEGASCALE_COUNT"));

		myUrls = new String[myMegaScaleCount];
		myUsers = new String[myMegaScaleCount];
		myPasswords = new String[myMegaScaleCount];

		for (int i = 1; i <= myMegaScaleCount; i++) {
			myUrls[i - 1] = getPropertyNotNull("MEGASCALE_URL_" + i);
			myUsers[i - 1] = getPropertyNotNull("MEGASCALE_USER_" + i);
			myPasswords[i - 1] = getPropertyNotNull("MEGASCALE_PASS_" + i);
		}
	}

	@CdrHook(CdrPointcut.STORAGE_MEGASCALE_PROVIDE_DB_INFO)
	public MegaScaleCredentialResponseJson provideMegaScaleCredentials(MegaScaleCredentialRequestJson theRequest) {

		MegaScaleCredentialResponseJson response;
		if (theRequest.getPartitionId() != 0) {
			response = createCredentialResponse(theRequest.getPartitionId());
		} else if (!isBlank(theRequest.getPartitionName())) {
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
	private MegaScaleCredentialResponseJson createCredentialResponse(int partitionNumber) {
		Validate.isTrue(partitionNumber >= 1);
		Validate.isTrue(partitionNumber <= myMegaScaleCount);

		MegaScaleCredentialResponseJson retVal = new MegaScaleCredentialResponseJson();
		retVal.setDatabaseUrl(myUrls[partitionNumber - 1]);
		retVal.setDatabaseUsername(myUsers[partitionNumber - 1]);
		retVal.setDatabasePassword(myPasswords[partitionNumber - 1]);
		return retVal;
	}

	static String getPropertyNotNull(String thePropertyName) {
		String retVal = System.getProperty(thePropertyName);
		if (isBlank(retVal)) {
			retVal = System.getenv(thePropertyName);
			if (isBlank(retVal)) {
				throw new ConfigurationException("No value for env variable: " + thePropertyName);
			}
		}
		return retVal;
	}


}

package com.smilecdr.demo.benchmark2;

import ca.cdr.api.fhirgw.json.GatewayConfigurationJson;
import ca.cdr.api.fhirgw.json.GatewayCreateRouteJson;
import ca.cdr.api.fhirgw.json.GatewayReadRouteJson;
import ca.cdr.api.fhirgw.json.GatewayRouteTargetJson;
import ca.cdr.api.fhirgw.json.GatewaySearchRouteJson;
import ca.cdr.api.fhirgw.json.GatewayTargetJson;
import ca.cdr.api.fhirgw.json.GatewayTransactionRouteJson;
import ca.cdr.api.fhirgw.json.GatewayUpdateRouteJson;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.util.JsonUtil;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Set;

public class GatewayConfigGenerator {

	public static void main(String[] args) throws IOException {

		GatewayConfigurationJson config = new GatewayConfigurationJson();

		// Add targets
        addTarget(config, "Write", "def", "8000", "DEFAULT", true);
        addTarget(config, "Read", "def", "8001", "DEFAULT", true);
		for (int i = 1; i <= BenchmarkConstants.MEGASCALE_COUNT; i++) {
			addTarget(config, "Write", "ms" + i, "8000", "MS" + i, true);
			addTarget(config, "Read", "ms" + i, "8001", "MS" + i, true);
			addTarget(config, "Write", "ms" + i, "8000", "MS" + i, false);
			addTarget(config, "Read", "ms" + i, "8001", "MS" + i, false);
		}

		// Search Route
		GatewaySearchRouteJson searchRoute = config.addSearchRoute();
		searchRoute.setId("search");
		searchRoute.setParallel(false);
		searchRoute.setResourceTypes(Set.of("Patient", "Observation", "Encounter"));
		for (int i = 1; i <= BenchmarkConstants.MEGASCALE_COUNT; i++) {
			searchRoute.addTarget(new GatewayRouteTargetJson().setTargetId("Read-ms" + i));
		}

		// Read Route
		GatewayReadRouteJson readRoute = config.addReadRoute();
		readRoute.setId("read");
		readRoute.setParallel(false);
		readRoute.setResourceTypes(Set.of("Patient", "Observation", "Encounter"));
		for (int i = 1; i <= BenchmarkConstants.MEGASCALE_COUNT; i++) {
			readRoute.addTarget(new GatewayRouteTargetJson().setTargetId("Read-ms" + i));
		}

		// Update Route
		GatewayUpdateRouteJson updateRoute = config.addUpdateRoute();
		updateRoute.setId("update");
		updateRoute.setParallel(false);
		updateRoute.setResourceTypes(Set.of("SearchParameter", "Patient", "Observation", "Encounter"));
		updateRoute.addTarget(new GatewayRouteTargetJson().setTargetId("Write-def"));
		for (int i = 1; i <= BenchmarkConstants.MEGASCALE_COUNT; i++) {
			updateRoute.addTarget(new GatewayRouteTargetJson().setTargetId("Write-ms" + i));
		}

		// Create Route
		GatewayCreateRouteJson create = config.addCreateRoute();
		create.setId("create");
		create.addResourceType("Observation");
		for (int i = 1; i <= BenchmarkConstants.MEGASCALE_COUNT; i++) {
			create.addTarget(new GatewayRouteTargetJson().setTargetId("Write-ms" + i));
		}

		// Transaction Route
		GatewayTransactionRouteJson transaction = config.addTransactionRoute();
		transaction.setId("transaction");
		for (int i = 1; i <= BenchmarkConstants.MEGASCALE_COUNT; i++) {
			transaction.addTarget(new GatewayRouteTargetJson().setTargetId("Write-ms" + i + "-noprefix"));
		}

		String output = JsonUtil.serialize(config);
		try (FileWriter w = new FileWriter("target/gateway_config.json", false)) {
			w.append(output);
		}
	}

	private static void addTarget(GatewayConfigurationJson config, String nodeId, String partitionId, String endpointPort, String partitionName, boolean thePrefixed) {
		String id = nodeId + "-" + partitionId;
		if (!thePrefixed) {
			id += "-noprefix";
		}

		GatewayTargetJson target = config.addTarget();
		target.setId(id);
		target.setBaseUrl("http://localhost:" + endpointPort + "/" + partitionName);
		target.setHeadersToForward(List.of(Constants.HEADER_AUTHORIZATION));
		target.setServerCapabilityStatementValidationEnabled(false);
		if (thePrefixed) {
			target.setResourceIdPrefix(partitionId + "-");
		}
	}

}

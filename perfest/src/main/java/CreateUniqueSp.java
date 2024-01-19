import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.util.HapiExtensions;
import ca.uhn.fhir.util.StringUtil;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.SearchParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateUniqueSp {
	private static final Logger ourLog = LoggerFactory.getLogger(CreateUniqueSp.class);

	private String myBaseUrl;
	private final FhirContext ourCtx = FhirContext.forR4Cached();

	private void run(String[] theArgs) {
		String syntaxMsg = "Syntax: " + CreateUniqueSp.class.getName() + " [baseUrl]";
		Validate.isTrue(theArgs.length == 1, syntaxMsg);
		myBaseUrl = StringUtil.chompCharacter(theArgs[0], '/');
		Validate.isTrue(myBaseUrl.startsWith("http"), syntaxMsg);

		ourLog.info("Ensuring unique SP is present");
		createUniqueSp();
	}

	/**
	 * Synthea uses inline match URLs which causes Organizations to be
	 * created. Because of our multithreaded processing, there's a chance
	 * for duplicates to be created, so we put a unique SP in to defend
	 * against that.
	 */
	private void createUniqueSp() {
		IGenericClient client = ourCtx.newRestfulGenericClient(myBaseUrl);
		client.registerInterceptor(new BasicAuthInterceptor("admin", "password"));

		SearchParameter uniqueSp = new SearchParameter();
		uniqueSp.setId("def-sp-org-identifier-uniq");
		uniqueSp.setType(Enumerations.SearchParamType.COMPOSITE);
		uniqueSp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		uniqueSp.addBase("Organization");
		uniqueSp.addComponent()
			.setExpression("Organization.identifier")
			.setDefinition("SearchParameter/Organization-identifier");
		uniqueSp.addExtension()
			.setUrl(HapiExtensions.EXT_SP_UNIQUE)
			.setValue(new BooleanType(true));

		MethodOutcome outcome = client
			.update()
			.resource(uniqueSp)
			.execute();
		IBaseResource resource = outcome.getResource();
		Boolean created = outcome.getCreated();

		ourLog.info("Created: {} - Outcome:\n{}", created, ourCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource));
	}

	public static void main(String[] theArgs) {
		new CreateUniqueSp().run(theArgs);
	}

}

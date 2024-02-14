import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.util.HapiExtensions;
import ca.uhn.fhir.util.StringUtil;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.SearchParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class CreateUniqueSp {
	private static final Logger ourLog = LoggerFactory.getLogger(CreateUniqueSp.class);

	private final FhirContext ourCtx = FhirContext.forR4Cached();

	private void run(String[] theArgs) {
		String syntaxMsg = "Syntax: " + CreateUniqueSp.class.getName() + " [Gateway Base Url]";

		List<String> myBaseUrls = new ArrayList<>();
		for (var next : theArgs) {
			Validate.isTrue(next.startsWith("http"), syntaxMsg);
			myBaseUrls.add(StringUtil.chompCharacter(next, '/'));
		}

		for (var baseUrl : myBaseUrls) {
			createUniqueSp(baseUrl);
		}

		ourLog.info("Complete - Please wait 60+ seconds for search parameter caches to reload!");
	}

	/**
	 * Synthea uses inline match URLs which causes Organizations to be
	 * created. Because of our multithreaded processing, there's a chance
	 * for duplicates to be created, so we put a unique SP in to defend
	 * against that.
	 */
	private void createUniqueSp(String theBaseUrl) {
		IGenericClient client = ourCtx.newRestfulGenericClient(theBaseUrl);
		client.registerInterceptor(new BasicAuthInterceptor("admin", "password"));

        SearchParameter orgUniqueSp = createUniqueIdentifierSearchParameter("Organization");
		uploadUniqueIdentifierSearchParameter("Organization", client, orgUniqueSp);

        SearchParameter practitionerUniqueSp = createUniqueIdentifierSearchParameter("Practitioner");
		uploadUniqueIdentifierSearchParameter("Practitioner", client, practitionerUniqueSp);
	}

	private static void uploadUniqueIdentifierSearchParameter(String base, IGenericClient client, SearchParameter uniqueSp) {
		ourLog.info("Creating " + base + ".identifier uniqueness guard...");
		MethodOutcome outcome = client
			.update()
			.resource(uniqueSp)
			.execute();
		Boolean created = outcome.getCreated();
		ourLog.info("Created: {} - Received ID: {}", created, outcome.getId());
	}

	private static SearchParameter createUniqueIdentifierSearchParameter(String base) {
		SearchParameter uniqueSp = new SearchParameter();
		uniqueSp.setId("def-sp-" + base + "-identifier-uniq");
		uniqueSp.setType(Enumerations.SearchParamType.COMPOSITE);
		uniqueSp.setStatus(Enumerations.PublicationStatus.ACTIVE);
		uniqueSp.setCode("identifier-unique");
		uniqueSp.setName("identifier-unique");
		uniqueSp.addBase(base);
		uniqueSp.addComponent()
			.setExpression(base + ".identifier")
			.setDefinition("SearchParameter/" + base + "-identifier");
		uniqueSp.addExtension()
			.setUrl(HapiExtensions.EXT_SP_UNIQUE)
			.setValue(new BooleanType(true));
		return uniqueSp;
	}

	public static void main(String[] theArgs) {
		new CreateUniqueSp().run(theArgs);
	}

}

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.client.interceptor.UrlTenantSelectionInterceptor;
import ca.uhn.fhir.util.ResourceReferenceInfo;
import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.StringUtil;
import ca.uhn.fhir.util.ThreadPoolUtil;
import com.codahale.metrics.Meter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER;
import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER_RETURN;
import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER_RETURN_MINIMAL;

public class Benchmarker {
	public static final ContentType CONTENT_TYPE_FHIR_JSON = ContentType.create(Constants.CT_FHIR_JSON_NEW, StandardCharsets.UTF_8);
	public static final Encounter.EncounterStatus[] ENCOUNTER_STATUSES = {
		Encounter.EncounterStatus.ARRIVED,
		Encounter.EncounterStatus.CANCELLED,
		Encounter.EncounterStatus.ENTEREDINERROR,
		Encounter.EncounterStatus.FINISHED,
		Encounter.EncounterStatus.INPROGRESS,
		Encounter.EncounterStatus.ONLEAVE,
		Encounter.EncounterStatus.PLANNED,
		Encounter.EncounterStatus.TRIAGED,
		Encounter.EncounterStatus.UNKNOWN
	};
	private static final Logger ourLog = LoggerFactory.getLogger(Benchmarker.class);
	private static final FhirContext ourCtx = FhirContext.forR4Cached();
	private static final int PATIENT_COUNT = 1000;
	private IGenericClient myGatewayFhirClient;
	private List<IIdType> myPatientIds = new ArrayList<>();
	private List<IIdType> myEncounterIds = new ArrayList<>();
	private Map<String, Encounter> myEncounters = new HashMap<>();
	private ThreadPoolTaskExecutor myReadThreadPool;
	private ThreadPoolTaskExecutor mySearchThreadPool;
	private ThreadPoolTaskExecutor myUpdateThreadPool;
	private ThreadPoolTaskExecutor myCreateThreadPool;
	private CloseableHttpClient myHttpClient;
	private ReadTask myReadTask;
	private String myGatewayBaseUrl;
	private AtomicLong myFailureCount = new AtomicLong(0);
	private AtomicLong myReadCount = new AtomicLong(0);
	private AtomicLong mySearchCount = new AtomicLong(0);
	private AtomicLong myUpdateCount = new AtomicLong(0);
	private AtomicLong myCreateCount = new AtomicLong(0);
	private Meter myReadMeter;
	private Meter mySearchMeter;
	private Meter myUpdateMeter;
	private Meter myCreateMeter;
	private Meter myFailureMeter;
	private Timer myLoggerTimer;
	private StopWatch mySw;
	private SearchTask mySearchTask;
	private UpdateTask myUpdateTask;
	private String myReadNodeBaseUrl;
	private CreateTask myCreateTask;
	private FileWriter myCsvWriter;

	private void run(String[] theArgs) throws IOException {
//		if (true) {
//			test();
//		}

		String syntaxMsg = "Syntax: " + Benchmarker.class.getName() + " [gateway base URL] [read node base URL] [megascale DB count] [thread count]";
		Validate.isTrue(theArgs.length == 4, syntaxMsg);
		myGatewayBaseUrl = StringUtil.chompCharacter(theArgs[0], '/');
		myReadNodeBaseUrl = StringUtil.chompCharacter(theArgs[1], '/');
		int megascaleDbCount = Integer.parseInt(theArgs[2]);
		int threadCount = Integer.parseInt(theArgs[3]);

		myGatewayFhirClient = ourCtx.newRestfulGenericClient(myGatewayBaseUrl);
		myGatewayFhirClient.registerInterceptor(new BasicAuthInterceptor("admin", "password"));

		myHttpClient = Uploader.createHttpClient();
		ourLog.info("Benchmarker starting with {} thread count", threadCount);
		loadData(megascaleDbCount);

		myReadThreadPool = ThreadPoolUtil.newThreadPool(threadCount, threadCount, "read-", 100);
		mySearchThreadPool = ThreadPoolUtil.newThreadPool(threadCount, threadCount, "search-", 100);
		myUpdateThreadPool = ThreadPoolUtil.newThreadPool(threadCount, threadCount, "update-", 100);
		myCreateThreadPool = ThreadPoolUtil.newThreadPool(threadCount, threadCount, "create-", 100);

		myReadMeter = Uploader.newMeter();
		mySearchMeter = Uploader.newMeter();
		myUpdateMeter = Uploader.newMeter();
		myCreateMeter = Uploader.newMeter();
		myFailureMeter = Uploader.newMeter();

		mySw = new StopWatch();

		myReadTask = new ReadTask(myReadThreadPool);
		myReadTask.start();
		mySearchTask = new SearchTask(mySearchThreadPool);
		mySearchTask.start();
		myUpdateTask = new UpdateTask(myUpdateThreadPool);
		myUpdateTask.start();
		myCreateTask = new CreateTask(myCreateThreadPool);
		myCreateTask.start();

		myCsvWriter = new FileWriter("benchmark.csv");
		myCsvWriter.append("\n\n# Written: " + InstantType.now().asStringValue());
		myCsvWriter.append("\n# MillisSinceStart, " +
			"TotalRead, AllTimeReadPerSec, MovingAvgReadPerSec, " +
			"TotalSearch, AllTimeSearchPerSec, MovingAvgSearchPerSec, " +
			"TotalUpdate, AllTimeUpdatePerSec, MovingAvgUpdatePerSec, " +
			"TotalCreate, AllTimeCreatePerSec, MovingAvgCreatePerSec, " +
			"TotalFailures, MovingAvgFailuresPerSec" +
			"\n");

		myLoggerTimer = new Timer();
		myLoggerTimer.scheduleAtFixedRate(new ProgressLogger(), 0L, 1L * DateUtils.MILLIS_PER_SECOND);
	}

	private void test() throws IOException {
		CloseableHttpClient httpClient = Uploader.createHttpClient();

		HttpGet request = new HttpGet("http://localhost:8002");
		try (var resp = httpClient.execute(request)) {
			ourLog.info("Resp: {}", IOUtils.toString(resp.getEntity().getContent(), StandardCharsets.UTF_8));
		}
		try (var resp = httpClient.execute(request)) {
			ourLog.info("Resp: {}", IOUtils.toString(resp.getEntity().getContent(), StandardCharsets.UTF_8));
		}

		System.exit(0);
	}

	private void loadData(int theMegascaleDbCount) {
		int idsPerMegaScaleDb = 1000 / theMegascaleDbCount;

		for (int i = 1; i <= theMegascaleDbCount; i++) {
			IGenericClient client = ourCtx.newRestfulGenericClient(myReadNodeBaseUrl);
			client.registerInterceptor(new BasicAuthInterceptor("admin", "password"));
			client.registerInterceptor(new UrlTenantSelectionInterceptor("MS" + i));

			loadDataPatients(idsPerMegaScaleDb, i, client);
		}

	}

	private void loadDataPatients(int theIdsToLoad, int theMegascaleDb, IGenericClient theClient) {
		List<IIdType> gatewayFriendlyPatientIds = new ArrayList<>();
		List<IIdType> originalPatientIds = new ArrayList<>();
		ourLog.info("Loading Patient List Page 0 for MegaScale DB {}...", theMegascaleDb);
		Bundle outcome = theClient
			.search()
			.forResource(Patient.class)
			.count(100)
			.elementsSubset("id")
			.returnBundle(Bundle.class)
			.execute();
		int pageIndex = 0;
		while (true) {
			List<IIdType> ids = outcome
				.getEntry()
				.stream()
				.map(Bundle.BundleEntryComponent::getResource)
				.filter(Objects::nonNull)
				.map(Resource::getIdElement)
				.collect(Collectors.toList());
			for (var next : ids) {
				if (gatewayFriendlyPatientIds.size() <= theIdsToLoad) {
					gatewayFriendlyPatientIds.add(new IdType(myGatewayBaseUrl, next.getResourceType(), "ms" + theMegascaleDb + "-" + next.getIdPart(), null));
				}
				if (originalPatientIds.size() < 100) {
					originalPatientIds.add(next.toUnqualifiedVersionless());
				}
			}
			if (outcome.getLink("next") == null || gatewayFriendlyPatientIds.size() >= theIdsToLoad) {
				ourLog.info("Done loading Patient list for MegaScale DB {}, have {} IDs...", theMegascaleDb, gatewayFriendlyPatientIds.size());
				break;
			}

			ourLog.info("Loading Patient List Page {} for MegaScale DB {}, have {} IDs...", ++pageIndex, theMegascaleDb, gatewayFriendlyPatientIds.size());
			outcome = myGatewayFhirClient.loadPage().next(outcome).execute();

		}

		myPatientIds.addAll(gatewayFriendlyPatientIds);

		loadDataEncounters(theIdsToLoad, theMegascaleDb, originalPatientIds, theClient);
	}

	private void loadDataEncounters(int theIdsPerMegaScaleDb, int theMegaScaleDbIndex, List<IIdType> thePatientIds, IGenericClient theClient) {
		List<Encounter> encounters = new ArrayList<>();
		ourLog.info("Loading Encounter List Page 0 for MegaScale DB {}...", theMegaScaleDbIndex);
		List<String> ids = thePatientIds.stream()
			.map(t -> t.toUnqualifiedVersionless().getValue())
			.collect(Collectors.toList());
		Bundle outcome = theClient
			.search()
			.forResource(Encounter.class)
			.where(Encounter.PATIENT.hasAnyOfIds(ids))
			.returnBundle(Bundle.class)
			.execute();
		int pageIndex = 0;
		while (true) {
			List<Encounter> pageEncounters = outcome
				.getEntry()
				.stream()
				.map(Bundle.BundleEntryComponent::getResource)
				.map(t -> (Encounter) t)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
			for (var next : pageEncounters) {
				if (encounters.size() <= theIdsPerMegaScaleDb) {
					IdType nextId = next.getIdElement();
					nextId = new IdType(myGatewayBaseUrl, nextId.getResourceType(), "ms" + theMegaScaleDbIndex + "-" + nextId.getIdPart(), null);
					next.getMeta().setVersionId(null);
					next.setId(nextId);

					for (ResourceReferenceInfo nextRefInfo : ourCtx.newTerser().getAllResourceReferences(next)) {
						IIdType nextRef = nextRefInfo.getResourceReference().getReferenceElement();
						nextRefInfo.getResourceReference().setReference(nextRef.getResourceType() + "/ms" + theMegaScaleDbIndex + "-" + nextRef.getIdPart());
					}

					encounters.add(next);
				}
			}
			if (outcome.getLink("next") == null || encounters.size() >= theIdsPerMegaScaleDb) {
				ourLog.info("Done loading Encounter list for MegaScale DB {}, have {} IDs...", theMegaScaleDbIndex, encounters.size());
				break;
			}

			ourLog.info("Loading Encounter List Page {} for MegaScale DB {}, have {} IDs...", ++pageIndex, theMegaScaleDbIndex, encounters.size());
			outcome = myGatewayFhirClient.loadPage().next(outcome).execute();

		}

		for (var next : encounters) {
			IIdType nextId = next.getIdElement().toUnqualifiedVersionless();
			myEncounterIds.add(nextId);
			myEncounters.put(nextId.getValue(), next);
		}
	}


	public static void main(String[] theArgs) throws IOException {
		new Benchmarker().run(theArgs);
	}

	private class ReadTask extends BaseTaskCreator {


		public ReadTask(ThreadPoolTaskExecutor theThreadPool) {
			super(theThreadPool, myPatientIds);
		}

		@Override
		protected void run(int thePatientIndex, IIdType thePatientId) {
			String url = thePatientId.getValue();
			HttpGet get = new HttpGet(url);
			try (var response = myHttpClient.execute(get)) {
				if (response.getStatusLine().getStatusCode() == 200) {
					myReadMeter.mark();
					myReadCount.incrementAndGet();
				} else {
					ourLog.warn("Failure executing URL[{}]: {}", url, response.getStatusLine());
					myFailureMeter.mark();
					myFailureCount.incrementAndGet();
				}
			} catch (Exception e) {
				ourLog.warn("Failure executing URL[{}]", url, e);
				myFailureMeter.mark();
				myFailureCount.incrementAndGet();
			}
		}
	}

	private class SearchTask extends BaseTaskCreator {


		public SearchTask(ThreadPoolTaskExecutor theThreadPool) {
			super(theThreadPool, myPatientIds);
		}

		@Override
		protected void run(int thePatientIndex, IIdType thePatientId) {
			String url = myGatewayBaseUrl + "/Observation?patient=Patient/" + thePatientId.getIdPart() + "&_count=1";
			HttpGet get = new HttpGet(url);
			try (var response = myHttpClient.execute(get)) {
				if (response.getStatusLine().getStatusCode() == 200) {
					mySearchMeter.mark();
					mySearchCount.incrementAndGet();
				} else {
					ourLog.warn("Failure executing URL[{}]: {}", url, response.getStatusLine());
					myFailureMeter.mark();
					myFailureCount.incrementAndGet();
				}
			} catch (Exception e) {
				ourLog.warn("Failure executing URL[{}]", url, e);
				myFailureMeter.mark();
				myFailureCount.incrementAndGet();
			}
		}
	}

	private class UpdateTask extends BaseTaskCreator {


		public UpdateTask(ThreadPoolTaskExecutor theThreadPool) {
			super(theThreadPool, myEncounterIds);
		}

		@Override
		protected void run(int theEncounterIndex, IIdType theEncounterId) {

			Encounter encounter = myEncounters.get(theEncounterId.getValue());
			int newIdx = (int) (Math.random() * ENCOUNTER_STATUSES.length);
			encounter.setStatus(ENCOUNTER_STATUSES[newIdx]);
			String newPayload = ourCtx.newJsonParser().encodeToString(encounter);


			String url = myGatewayBaseUrl + "/Encounter/" + theEncounterId.getIdPart();
			HttpPut put = new HttpPut(url);
			put.addHeader(HEADER_PREFER, HEADER_PREFER_RETURN + "=" + HEADER_PREFER_RETURN_MINIMAL);
			put.setEntity(new StringEntity(newPayload, CONTENT_TYPE_FHIR_JSON));

			try (var response = myHttpClient.execute(put)) {
				if (response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 201) {
					myUpdateMeter.mark();
					myUpdateCount.incrementAndGet();
				} else {
					if (response.getStatusLine().getStatusCode() == 409) {
						// This means two threads tried to update the same resource, and this
						// is expected in a high-stress benchmark, so we consider this a success
						// since the server behaved appropriately
						myUpdateMeter.mark();
						myUpdateCount.incrementAndGet();
					} else {
						ourLog.warn("Failure executing URL[{}]: {}\n{}", url, response.getStatusLine(), response);
						myFailureMeter.mark();
						myFailureCount.incrementAndGet();
					}
				}
			} catch (Exception e) {
				ourLog.warn("Failure executing URL[{}]", url, e);
				myFailureMeter.mark();
				myFailureCount.incrementAndGet();
			}
		}
	}

	private class CreateTask extends BaseTaskCreator {


		public CreateTask(ThreadPoolTaskExecutor theThreadPool) {
			super(theThreadPool, myPatientIds);
		}

		@Override
		protected void run(int thePatientIndex, IIdType thePatientId) {

			Observation obs = new Observation();
			obs.setStatus(Observation.ObservationStatus.FINAL);
			obs.getCategoryFirstRep().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode("vital-signs");
			obs.setSubject(new Reference(thePatientId.toUnqualifiedVersionless()));
			obs.setEffective(DateTimeType.now());
			obs.setStatus(Observation.ObservationStatus.FINAL);
			obs.setValue(new StringType("This is the value"));
			String newPayload = ourCtx.newJsonParser().encodeToString(obs);

			String url = myGatewayBaseUrl + "/Observation";
			HttpPost post = new HttpPost(url);
			post.addHeader(HEADER_PREFER, HEADER_PREFER_RETURN + "=" + HEADER_PREFER_RETURN_MINIMAL);
			post.setEntity(new StringEntity(newPayload, CONTENT_TYPE_FHIR_JSON));

			try (var response = myHttpClient.execute(post)) {
				if (response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 201) {
					myCreateMeter.mark();
					myCreateCount.incrementAndGet();
				} else {
					String results = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
					ourLog.warn("Failure executing URL[{}]: {}\n{}", url, response.getStatusLine(), results);
					myFailureMeter.mark();
					myFailureCount.incrementAndGet();
				}
			} catch (Exception e) {
				ourLog.warn("Failure executing URL[{}]", url, e);
				myFailureMeter.mark();
				myFailureCount.incrementAndGet();
			}
		}
	}

	private abstract class BaseTaskCreator extends Thread {
		protected final ThreadPoolTaskExecutor myThreadPool;
		private final List<IIdType> myIdList;

		public BaseTaskCreator(ThreadPoolTaskExecutor theThreadPool, List<IIdType> theIdList) {
			myThreadPool = theThreadPool;
			myIdList = theIdList;
		}

		@Override
		public void run() {
			while (true) {
				myThreadPool.submit(() -> {
					int idIndex = (int) (Math.random() * myIdList.size());
					IIdType id = myIdList.get(idIndex);
					run(idIndex, id);
				});
			}
		}

		protected abstract void run(int thePatientIndex, IIdType thePatientId);
	}


	private class ProgressLogger extends TimerTask {

		@Override
		public void run() {
			long totalRead = myReadCount.get();
			long allTimeRead = (long) mySw.getThroughput(totalRead, TimeUnit.SECONDS);
			long perSecondRead = ((long) myReadMeter.getOneMinuteRate()) / 60L;

			long totalSearch = mySearchCount.get();
			long allTimeSearch = (long) mySw.getThroughput(totalSearch, TimeUnit.SECONDS);
			long perSecondSearch = ((long) mySearchMeter.getOneMinuteRate()) / 60L;

			long totalUpdate = myUpdateCount.get();
			long allTimeUpdate = (long) mySw.getThroughput(totalUpdate, TimeUnit.SECONDS);
			long perSecondUpdate = ((long) myUpdateMeter.getOneMinuteRate()) / 60L;

			long totalCreate = myCreateCount.get();
			long allTimeCreate = (long) mySw.getThroughput(totalCreate, TimeUnit.SECONDS);
			long perSecondCreate = ((long) myCreateMeter.getOneMinuteRate()) / 60L;

			long totalFail = myFailureCount.get();
			long perSecondFail = (long) (myFailureMeter.getOneMinuteRate() / 60L);

			ourLog.info(
				"\nREAD[ Total {} - All {}/sec - MovAvg {}/sec] " +
					"\nSEARCH[ Total {} - All {}/sec - MovAvg {}/sec] " +
					"\nUPDATE[ Total {} - All {}/sec - MovAvg {}/sec] " +
					"\nCREATE[ Total {} - All {}/sec - MovAvg {}/sec] " +
					"\nFAIL[ Total {} - MovAvg {}/sec]",
				totalRead, allTimeRead, perSecondRead,
				totalSearch, allTimeSearch, perSecondSearch,
				totalUpdate, allTimeUpdate, perSecondUpdate,
				totalCreate, allTimeCreate, perSecondCreate,
				totalFail, perSecondFail
			);

			long millis = mySw.getMillis();
			millis = millis - (millis % 1000);

			try {
				myCsvWriter.append(
					millis + "," +
						totalRead + "," + allTimeRead + "," + perSecondRead + "," +
						totalSearch + "," + allTimeSearch + "," + perSecondSearch + "," +
						totalUpdate + "," + allTimeUpdate + "," + perSecondUpdate + "," +
						totalCreate + "," + allTimeCreate + "," + perSecondCreate + "," +
						totalFail + "," + perSecondFail + "\n"
				);
				myCsvWriter.flush();
			} catch (Exception e) {
				ourLog.error("Failed to write CSV", e);
			}
		}
	}

}

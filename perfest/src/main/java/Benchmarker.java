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
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class Benchmarker {
	private static final Logger ourLog = LoggerFactory.getLogger(Benchmarker.class);
	private static final FhirContext ourCtx = FhirContext.forR4Cached();
	private static final int PATIENT_COUNT = 1000;
	private IGenericClient myGatewayFhirClient;
	private List<IIdType> myPatientIds = new ArrayList<>();
	private List<IIdType> myEncounterIds = new ArrayList<>();
	private IdentityHashMap<IIdType, Encounter> myEncounters = new IdentityHashMap<>();
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

	private void run(String[] theArgs) {
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
		myUpdateTask = new UpdateTask(mySearchThreadPool);
		myUpdateTask.start();

		myLoggerTimer = new Timer();
		myLoggerTimer.scheduleAtFixedRate(new ProgressLogger(), 0L, 1L * DateUtils.MILLIS_PER_SECOND);
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
			.map(t->t.toUnqualifiedVersionless().getValue())
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
				.map(t->(Encounter)t)
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
			IIdType nextId = next.getIdElement();
			myEncounterIds.add(nextId);
			myEncounters.put(nextId, next);
		}
	}


	public static void main(String[] theArgs) {
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

			Encounter encounter = myEncounters.get(theEncounterId);
			Encounter.EncounterStatus[] encounterStatuses = Encounter.EncounterStatus.values();
			int newIdx = (int)(Math.random() * encounterStatuses.length);
			encounter.setStatus(encounterStatuses[newIdx]);
			String newPayload = ourCtx.newJsonParser().encodeToString(encounter);


			String url = myGatewayBaseUrl + "/Encounter/" + theEncounterId.getIdPart();
			HttpPost post = new HttpPost(url);
			post.setEntity(new StringEntity(newPayload, Constants.CT_FHIR_JSON_NEW));

			try (var response = myHttpClient.execute(post)) {
				if (response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 201) {
					myUpdateMeter.mark();
					myUpdateCount.incrementAndGet();
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
				myThreadPool.submit(()->{
					int patientIndex = (int) (Math.random() * myPatientIds.size());
					IIdType patientId = myPatientIds.get(patientIndex);
					run(patientIndex, patientId);
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

			ourLog.info(
					"READ[ Total {} - All {}/sec - MovAvg {}/sec] " +
					"SEARCH[ Total {} - All {}/sec - MovAvg {}/sec] " +
					"UPDATE[ Total {} - All {}/sec - MovAvg {}/sec]",
				totalRead, allTimeRead, perSecondRead,
				totalSearch, allTimeSearch, perSecondSearch,
				totalUpdate, allTimeUpdate, perSecondUpdate
				);
		}
	}

}

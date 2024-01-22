import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.StringUtil;
import ca.uhn.fhir.util.ThreadPoolUtil;
import com.codahale.metrics.Meter;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
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
	private IGenericClient myFhirClient;
	private List<IIdType> myPatientIds = new ArrayList<>();
	private List<IIdType> myEncounterIds = new ArrayList<>();
	private IdentityHashMap<IIdType, Encounter> myEncounters = new IdentityHashMap<>();
	private ThreadPoolTaskExecutor myReadThreadPool;
	private ThreadPoolTaskExecutor mySearchThreadPool;
	private ThreadPoolTaskExecutor myUpdateThreadPool;
	private ThreadPoolTaskExecutor myCreateThreadPool;
	private CloseableHttpClient myHttpClient;
	private ReadTask myReadTask;
	private String myBaseUrl;
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

	private void run(String[] theArgs) {
		String syntaxMsg = "Syntax: " + Benchmarker.class.getName() + " [base URL] [thread count]";
		Validate.isTrue(theArgs.length == 2, syntaxMsg);
		myBaseUrl = StringUtil.chompCharacter(theArgs[0], '/');
		int threadCount = Integer.parseInt(theArgs[1]);

		myFhirClient = ourCtx.newRestfulGenericClient(myBaseUrl);
		myFhirClient.registerInterceptor(new BasicAuthInterceptor("admin", "password"));

		myHttpClient = Uploader.createHttpClient();
		ourLog.info("Benchmarker starting with {} thread count", threadCount);
		loadData();

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

	private void loadData() {
		ourLog.info("Loading Patient List Page 0...");
		Bundle outcome = myFhirClient
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
				.map(Resource::getIdElement)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
			for (var next : ids) {
				if (myPatientIds.size() <= 1000) {
					myPatientIds.add(next);
				}
			}
			if (outcome.getLink("next") == null || myPatientIds.size() >= 1000) {
				ourLog.info("Done loading Patient list, have {} IDs...", myPatientIds.size());
				break;
			}

			ourLog.info("Loading Patient List Page {}, have {} IDs...", ++pageIndex, myPatientIds.size());
			outcome = myFhirClient.loadPage().next(outcome).execute();

		}


		ourLog.info("Loading Encounter List Page 0...");
		outcome = myFhirClient
			.search()
			.forResource(Encounter.class)
			.count(100)
			.elementsSubset("id")
			.returnBundle(Bundle.class)
			.execute();
		pageIndex = 0;
		while (true) {
			List<Encounter> encounters = outcome
				.getEntry()
				.stream()
				.map(Bundle.BundleEntryComponent::getResource)
				.map(t->(Encounter)t)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
			for (var next : encounters) {
				if (myEncounterIds.size() <= 1000) {
					IdType nextId = next.getIdElement().toVersionless();
					ourLog.info("Got encounter ID: {}", nextId);
					next.getMeta().setVersionId(null);
					next.setId(nextId);
					myEncounterIds.add(nextId);
					myEncounters.put(nextId, next);
				}
			}
			if (outcome.getLink("next") == null || myEncounters.size() >= 1000) {
				ourLog.info("Done loading Encounter list, have {} IDs...", myEncounters.size());
				break;
			}

			ourLog.info("Loading Encounter List Page {}, have {} IDs...", ++pageIndex, myEncounters.size());
			outcome = myFhirClient.loadPage().next(outcome).execute();

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
			String url = myBaseUrl + "/Observation?patient=Patient/" + thePatientId.getIdPart() + "&_count=1";
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


			String url = myBaseUrl + "/Encounter/" + theEncounterId.getIdPart();
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

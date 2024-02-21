import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.client.interceptor.UrlTenantSelectionInterceptor;
import ca.uhn.fhir.util.ResourceReferenceInfo;
import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.StringUtil;
import ca.uhn.fhir.util.ThreadPoolUtil;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER;
import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER_RETURN;
import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER_RETURN_MINIMAL;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;
import static org.apache.commons.lang3.StringUtils.startsWith;

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
	private final Semaphore myReadSemaphore;
	private final Semaphore mySearchSemaphore;
	private final Semaphore myUpdateSemaphore;
	private final Semaphore myCreateSemaphore;
	private final IGenericClient myGatewayFhirClient;
	private final List<IIdType> myPatientIds = new ArrayList<>();
	private final List<IIdType> myEncounterIds = new ArrayList<>();
	private final Map<String, Encounter> myEncounters = new HashMap<>();
	private final ReadTask myReadTask;
	private final String myGatewayBaseUrl;
	private final AtomicLong myFailureCount = new AtomicLong(0);
	private final AtomicLong myReadCount = new AtomicLong(0);
	private final AtomicLong myCacheHitCount = new AtomicLong(0);
	private final AtomicLong myCacheMissCount = new AtomicLong(0);
	private final AtomicLong mySearchCount = new AtomicLong(0);
	private final AtomicLong myUpdateCount = new AtomicLong(0);
	private final AtomicLong myCreateCount = new AtomicLong(0);
	private final Meter myReadThroughputMeter;
	private final Meter mySearchThroughputMeter;
	private final Meter myUpdateThroughputMeter;
	private final Meter myCreateThroughputMeter;
	private final Meter myFailureMeter;
	private final StopWatch mySw;
	private final SearchTask mySearchTask;
	private final UpdateTask myUpdateTask;
	private final String myReadNodeBaseUrl;
	private final CreateTask myCreateTask;
	private final FileWriter myCsvWriter;
	private final Meter myRequestBytesMeter;
	private final Meter myResponseBytesMeter;
	private final Histogram myReadLatencyHistogram;
	private final Histogram mySearchLatencyHistogram;
	private final Histogram myUpdateLatencyHistogram;
	private final Histogram myCreateLatencyHistogram;
	private final boolean myCompression;
	private final int myMaxThreadCount;
	private final int myThreadIncrementPerMinute;
	private final Timer myThreadIncrementer;
	private int myActiveThreadCount;

	@SuppressWarnings("StringConcatenationInsideStringBufferAppend")
	private Benchmarker(String[] theArgs) throws IOException {
		String syntaxMsg = "Syntax: " + Benchmarker.class.getName() + " [gateway base URL] [read node base URL] [megascale DB count] [thread count] [compression true/false] [ramp up mins]";
		Validate.isTrue(theArgs.length == 6, syntaxMsg);
		myGatewayBaseUrl = StringUtil.chompCharacter(theArgs[0], '/');
		myReadNodeBaseUrl = StringUtil.chompCharacter(theArgs[1], '/');
		int megascaleDbCount = Integer.parseInt(theArgs[2]);
		myMaxThreadCount = Integer.parseInt(theArgs[3]);
		myCompression = Boolean.parseBoolean(theArgs[4]);

		int rampUpMins = Integer.parseInt(theArgs[5]);
		int initialThreadCount;
		if (rampUpMins > 0) {
			myThreadIncrementPerMinute = myMaxThreadCount / rampUpMins;
			initialThreadCount = 1;
			ourLog.info("Benchmarker starting with {} threads, ramping up by {} per minute to a maximum of {}", initialThreadCount, myThreadIncrementPerMinute, myMaxThreadCount);
		} else {
			initialThreadCount = myMaxThreadCount;
			myThreadIncrementPerMinute = 0;
			ourLog.info("Benchmarker starting with {} thread count", myMaxThreadCount);
		}

		myGatewayFhirClient = ourCtx.newRestfulGenericClient(myGatewayBaseUrl);
		myGatewayFhirClient.registerInterceptor(new BasicAuthInterceptor("admin", "password"));

		loadData(megascaleDbCount);

		ThreadPoolTaskExecutor readThreadPool = ThreadPoolUtil.newThreadPool(myMaxThreadCount, myMaxThreadCount, "read-", 100);
		ThreadPoolTaskExecutor searchThreadPool = ThreadPoolUtil.newThreadPool(myMaxThreadCount, myMaxThreadCount, "search-", 100);
		ThreadPoolTaskExecutor updateThreadPool = ThreadPoolUtil.newThreadPool(myMaxThreadCount, myMaxThreadCount, "update-", 100);
		ThreadPoolTaskExecutor createThreadPool = ThreadPoolUtil.newThreadPool(myMaxThreadCount, myMaxThreadCount, "create-", 100);

		myReadSemaphore = new Semaphore(0, false);
		mySearchSemaphore = new Semaphore(0, false);
		myUpdateSemaphore = new Semaphore(0, false);
		myCreateSemaphore = new Semaphore(0, false);

		addSemaphores(initialThreadCount);

		myReadThroughputMeter = Uploader.newMeter();
		myReadLatencyHistogram = Uploader.newHistogram();
		mySearchThroughputMeter = Uploader.newMeter();
		mySearchLatencyHistogram = Uploader.newHistogram();
		myUpdateThroughputMeter = Uploader.newMeter();
		myUpdateLatencyHistogram = Uploader.newHistogram();
		myCreateThroughputMeter = Uploader.newMeter();
		myCreateLatencyHistogram = Uploader.newHistogram();
		myFailureMeter = Uploader.newMeter();
		myRequestBytesMeter = Uploader.newMeter();
		myResponseBytesMeter = Uploader.newMeter();

		mySw = new StopWatch();

		myReadTask = new ReadTask(readThreadPool, myReadSemaphore);
		mySearchTask = new SearchTask(searchThreadPool, mySearchSemaphore);
		myUpdateTask = new UpdateTask(updateThreadPool, myUpdateSemaphore);
		myCreateTask = new CreateTask(createThreadPool, myCreateSemaphore);
		myThreadIncrementer = new Timer();

		myCsvWriter = new FileWriter("benchmark.csv");
		myCsvWriter.append("\n\n# Written: " + InstantType.now().asStringValue());
		myCsvWriter.append("\n# MillisSinceStart, " +
			"TotalRead, AllTimeReadPerSec, MovingAvgReadPerSec, " +
			"TotalSearch, AllTimeSearchPerSec, MovingAvgSearchPerSec, " +
			"TotalUpdate, AllTimeUpdatePerSec, MovingAvgUpdatePerSec, " +
			"TotalCreate, AllTimeCreatePerSec, MovingAvgCreatePerSec, " +
			"TotalFailures, MovingAvgFailuresPerSec, " +
			"MovingAvgRequestBytesPerSec, MovingAvgResponseBytesPerSec, " +
			"ThreadCountPerOperation" +
			"\n");

		Timer loggerTimer = new Timer();
		loggerTimer.scheduleAtFixedRate(new ProgressLogger(), 0L, DateUtils.MILLIS_PER_SECOND);
	}

	private void addSemaphores(int theThreadCount) {
		myReadSemaphore.release(theThreadCount);
		mySearchSemaphore.release(theThreadCount);
		myCreateSemaphore.release(theThreadCount);
		myUpdateSemaphore.release(theThreadCount);
		myActiveThreadCount += theThreadCount;
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

	private void start() {
		myReadTask.start();
		mySearchTask.start();
		myUpdateTask.start();
		myCreateTask.start();
		if (myThreadIncrementPerMinute > 0) {
			myThreadIncrementer.scheduleAtFixedRate(new ThreadIncrementerTask(), DateUtils.MILLIS_PER_MINUTE, DateUtils.MILLIS_PER_MINUTE);
		}
	}

	private void extractCommonValues(CloseableHttpResponse theResponse, boolean theReadOperation) throws IOException {
		long bytes = consumeStream(theResponse.getEntity().getContent());
		myResponseBytesMeter.mark(bytes);

		if (theReadOperation) {
			Header xCache = theResponse.getFirstHeader(Constants.HEADER_X_CACHE);
			if (xCache != null && startsWith(xCache.getValue(), "HIT")) {
				myCacheHitCount.incrementAndGet();
			} else {
				myCacheMissCount.incrementAndGet();
			}
		}
	}

	public static void main(String[] theArgs) throws IOException {
		new Benchmarker(theArgs).start();
	}

	private static long consumeStream(InputStream is) throws IOException {
		long retVal = 0;
		if (is != null) {
			retVal = IOUtils.consume(is);
			is.close();
		}
		return retVal;
	}

	private class ReadTask extends BaseTaskCreator {

		private final CloseableHttpClient myHttpClient = Uploader.createHttpClient(myCompression);

		public ReadTask(ThreadPoolTaskExecutor theThreadPool, Semaphore theSemaphore) {
			super(theThreadPool, theSemaphore, myPatientIds);
		}

		@Override
		protected void run(int thePatientIndex, IIdType thePatientId) {
			String url = thePatientId.getValue() + "?_elements=id&_elements:exclude=Patient.meta";
			HttpGet get = new HttpGet(url);
			long start = System.currentTimeMillis();
			try (var response = myHttpClient.execute(get)) {
				if (response.getStatusLine().getStatusCode() == 200) {
					myReadThroughputMeter.mark();
					myReadCount.incrementAndGet();
					myReadLatencyHistogram.update(System.currentTimeMillis() - start);
				} else {
					ourLog.warn("Failure executing URL[{}]: {}", url, response.getStatusLine());
					myFailureMeter.mark();
					myFailureCount.incrementAndGet();
				}
				extractCommonValues(response, true);
			} catch (Exception e) {
				ourLog.warn("Failure executing URL[{}]", url, e);
				myFailureMeter.mark();
				myFailureCount.incrementAndGet();
			}
		}
	}

	private class SearchTask extends BaseTaskCreator {
		private final CloseableHttpClient myHttpClient = Uploader.createHttpClient(myCompression);


		public SearchTask(ThreadPoolTaskExecutor theThreadPool, Semaphore theSemaphore) {
			super(theThreadPool, theSemaphore, myPatientIds);
		}

		@Override
		protected void run(int thePatientIndex, IIdType thePatientId) {
			String url = myGatewayBaseUrl + "/Observation?patient=Patient/" + thePatientId.getIdPart() + "&_count=1&_elements=id&_elements:exclude=Observation.meta";
			HttpGet get = new HttpGet(url);
			long start = System.currentTimeMillis();
			try (var response = myHttpClient.execute(get)) {
				if (response.getStatusLine().getStatusCode() == 200) {
					mySearchThroughputMeter.mark();
					mySearchCount.incrementAndGet();
					mySearchLatencyHistogram.update(System.currentTimeMillis() - start);
				} else {
					ourLog.warn("Failure executing URL[{}]: {}", url, response.getStatusLine());
					myFailureMeter.mark();
					myFailureCount.incrementAndGet();
				}
				extractCommonValues(response, true);
			} catch (Exception e) {
				ourLog.warn("Failure executing URL[{}]", url, e);
				myFailureMeter.mark();
				myFailureCount.incrementAndGet();
			}
		}
	}

	private class UpdateTask extends BaseTaskCreator {
		private final CloseableHttpClient myHttpClient = Uploader.createHttpClient(myCompression);


		public UpdateTask(ThreadPoolTaskExecutor theThreadPool, Semaphore theSemaphore) {
			super(theThreadPool, theSemaphore, myEncounterIds);
		}

		@Override
		protected void run(int theEncounterIndex, IIdType theEncounterId) {

			Encounter encounter = myEncounters.get(theEncounterId.getValue());
			int newIdx = (int) (Math.random() * ENCOUNTER_STATUSES.length);
			encounter.setStatus(ENCOUNTER_STATUSES[newIdx]);
			String newPayload = ourCtx.newJsonParser().encodeToString(encounter);
			myRequestBytesMeter.mark(newPayload.length());

			String url = myGatewayBaseUrl + "/Encounter/" + theEncounterId.getIdPart();
			HttpPut put = new HttpPut(url);
			put.addHeader(HEADER_PREFER, HEADER_PREFER_RETURN + "=" + HEADER_PREFER_RETURN_MINIMAL);
			put.setEntity(new StringEntity(newPayload, CONTENT_TYPE_FHIR_JSON));

			long start = System.currentTimeMillis();
			try (var response = myHttpClient.execute(put)) {
				if (response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 201) {
					myUpdateThroughputMeter.mark();
					myUpdateCount.incrementAndGet();
					myUpdateLatencyHistogram.update(System.currentTimeMillis() - start);
				} else {
					if (response.getStatusLine().getStatusCode() == 409) {
						// This means two threads tried to update the same resource, and this
						// is expected in a high-stress benchmark, so we consider this a success
						// since the server behaved appropriately
						myUpdateThroughputMeter.mark();
						myUpdateCount.incrementAndGet();
					} else {
						ourLog.warn("Failure executing URL[{}]: {}\n{}", url, response.getStatusLine(), response);
						myFailureMeter.mark();
						myFailureCount.incrementAndGet();
					}
				}
				extractCommonValues(response, false);
			} catch (Exception e) {
				ourLog.warn("Failure executing URL[{}]", url, e);
				myFailureMeter.mark();
				myFailureCount.incrementAndGet();
			}
		}
	}

	private class CreateTask extends BaseTaskCreator {

		private final CloseableHttpClient myHttpClient = Uploader.createHttpClient(myCompression);

		public CreateTask(ThreadPoolTaskExecutor theThreadPool, Semaphore theSemaphore) {
			super(theThreadPool, theSemaphore, myPatientIds);
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
			myRequestBytesMeter.mark(newPayload.length());

			String url = myGatewayBaseUrl + "/Observation";
			HttpPost post = new HttpPost(url);
			post.addHeader(HEADER_PREFER, HEADER_PREFER_RETURN + "=" + HEADER_PREFER_RETURN_MINIMAL);
			post.setEntity(new StringEntity(newPayload, CONTENT_TYPE_FHIR_JSON));

			long start = System.currentTimeMillis();
			try (var response = myHttpClient.execute(post)) {
				if (response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 201) {
					myCreateThroughputMeter.mark();
					myCreateCount.incrementAndGet();
					myCreateLatencyHistogram.update(System.currentTimeMillis() - start);
				} else {
					String results = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
					ourLog.warn("Failure executing URL[{}]: {}\n{}", url, response.getStatusLine(), results);
					myFailureMeter.mark();
					myFailureCount.incrementAndGet();
				}
				extractCommonValues(response, false);
			} catch (Exception e) {
				ourLog.warn("Failure executing URL[{}]", url, e);
				myFailureMeter.mark();
				myFailureCount.incrementAndGet();
			}
		}
	}

	private class ThreadIncrementerTask extends TimerTask {
		@Override
		public void run() {
			int threadDelta = myThreadIncrementPerMinute;
			if (myActiveThreadCount + threadDelta > myMaxThreadCount) {
				threadDelta = myMaxThreadCount - myActiveThreadCount;
			}

			if (threadDelta > 0) {
				ourLog.info("Incrementing thread count by {} - New total: {}", threadDelta, myActiveThreadCount + threadDelta);
				addSemaphores(threadDelta);
			}
		}
	}

	private class ProgressLogger extends TimerTask {

		@SuppressWarnings("StringConcatenationInsideStringBufferAppend")
		@Override
		public void run() {
			long totalRead = myReadCount.get();
			long allTimeRead = (long) mySw.getThroughput(totalRead, TimeUnit.SECONDS);
			long perSecondRead = ((long) myReadThroughputMeter.getOneMinuteRate()) / 60L;
			long avgMillisPerRead = (long) myReadLatencyHistogram.getSnapshot().getMean();

			long totalSearch = mySearchCount.get();
			long allTimeSearch = (long) mySw.getThroughput(totalSearch, TimeUnit.SECONDS);
			long perSecondSearch = ((long) mySearchThroughputMeter.getOneMinuteRate()) / 60L;
			long avgMillisPerSearch = (long) mySearchLatencyHistogram.getSnapshot().getMean();

			long totalUpdate = myUpdateCount.get();
			long allTimeUpdate = (long) mySw.getThroughput(totalUpdate, TimeUnit.SECONDS);
			long perSecondUpdate = ((long) myUpdateThroughputMeter.getOneMinuteRate()) / 60L;
			long avgMillisPerUpdate = (long) myUpdateLatencyHistogram.getSnapshot().getMean();

			long totalCreate = myCreateCount.get();
			long allTimeCreate = (long) mySw.getThroughput(totalCreate, TimeUnit.SECONDS);
			long perSecondCreate = ((long) myCreateThroughputMeter.getOneMinuteRate()) / 60L;
			long avgMillisPerCreate = (long) myCreateLatencyHistogram.getSnapshot().getMean();

			long perSecondSuccess = perSecondRead + perSecondSearch + perSecondCreate + perSecondUpdate;
			long totalFail = myFailureCount.get();
			long perSecondFail = (long) (myFailureMeter.getOneMinuteRate() / 60L);

			long requestBytesPerSec = (long) (myRequestBytesMeter.getOneMinuteRate() / 60L);
			long responseBytesPerSec = (long) (myResponseBytesMeter.getOneMinuteRate() / 60L);

//			double cacheHitCount = myCacheHitCount.get();
//			double cacheMissCount = myCacheMissCount.get();
//			long cacheHitPct = (long) ((cacheHitCount / (cacheMissCount + cacheHitCount)) * 100.0);

			ourLog.info(
				"\nREAD[ Total {} - All {}/sec - MovAvg {}/sec - Avg {}ms/tx - {} Concurrent] " +
					"\nSEARCH[ Total {} - All {}/sec - MovAvg {}/sec - Avg {}ms/tx - {} Concurrent] " +
					"\nUPDATE[ Total {} - All {}/sec - MovAvg {}/sec - Avg {}ms/tx - {} Concurrent] " +
					"\nCREATE[ Total {} - All {}/sec - MovAvg {}/sec - Avg {}ms/tx - {} Concurrent] " +
					"\nSUCCESS[ MovAvg {}/sec] -- FAIL[ Total {} - MovAvg {}/sec - {} Concurrent]" +
					"\nREQ[ {} /sec] -- RESP[ {} /sec]" +
//					"\nCACHE_HIT[ {}% ]" +
					" ",
				totalRead, allTimeRead, perSecondRead, avgMillisPerRead, myActiveThreadCount,
				totalSearch, allTimeSearch, perSecondSearch, avgMillisPerSearch, myActiveThreadCount,
				totalUpdate, allTimeUpdate, perSecondUpdate, avgMillisPerUpdate, myActiveThreadCount,
				totalCreate, allTimeCreate, perSecondCreate, avgMillisPerCreate, myActiveThreadCount,
				perSecondSuccess, totalFail, perSecondFail, myActiveThreadCount * 4,
				byteCountToDisplaySize(requestBytesPerSec), byteCountToDisplaySize(responseBytesPerSec)
//				cacheHitPct
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
						totalFail + "," + perSecondFail + "," +
						requestBytesPerSec + "," + responseBytesPerSec + "," +
						myActiveThreadCount +
						"\n"
				);
				myCsvWriter.flush();
			} catch (Exception e) {
				ourLog.error("Failed to write CSV", e);
			}
		}
	}

	private abstract static class BaseTaskCreator extends Thread {
		protected final ThreadPoolTaskExecutor myThreadPool;
		private final List<IIdType> myIdList;
		private final Semaphore mySemaphore;

		public BaseTaskCreator(ThreadPoolTaskExecutor theThreadPool, Semaphore theSemaphore, List<IIdType> theIdList) {
			myThreadPool = theThreadPool;
			myIdList = theIdList;
			mySemaphore = theSemaphore;
		}

		@SuppressWarnings("InfiniteLoopStatement")
		@Override
		public void run() {
			while (true) {
				myThreadPool.submit(() -> {
					int idIndex = (int) (Math.random() * myIdList.size());
					IIdType id = myIdList.get(idIndex);

					try {
						mySemaphore.acquire();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					try {
						run(idIndex, id);
					} finally {
						mySemaphore.release();
					}
				});
			}
		}

		protected abstract void run(int thePatientIndex, IIdType thePatientId);
	}

}

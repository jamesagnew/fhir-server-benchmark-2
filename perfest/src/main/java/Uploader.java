import ca.uhn.fhir.rest.client.impl.HttpBasicAuthInterceptor;
import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.StringUtil;
import com.codahale.metrics.Meter;
import com.codahale.metrics.SlidingTimeWindowMovingAverages;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.hl7.fhir.r4.model.InstantType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ca.uhn.fhir.rest.api.Constants.CT_FHIR_JSON_NEW;
import static ca.uhn.fhir.rest.api.Constants.ENCODING_GZIP;
import static ca.uhn.fhir.rest.api.Constants.HEADER_CONTENT_ENCODING;
import static ca.uhn.fhir.rest.api.Constants.HEADER_CONTENT_TYPE;
import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER;
import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER_RETURN;
import static ca.uhn.fhir.rest.api.Constants.HEADER_PREFER_RETURN_MINIMAL;

public class Uploader extends BaseFileIterator {

	private static final Logger ourLog = LoggerFactory.getLogger(Uploader.class);
	private final AtomicInteger myFailureCount = new AtomicInteger(0);
	private final AtomicInteger myRetryCount = new AtomicInteger(0);
	private Meter myResourcesUploadedMeter;
	private CloseableHttpClient myClient;
	private String myBaseUrl;
	private Timer myLogTimer;
	private FileWriter myCsvWriter;

	private void run(String[] args) throws Exception {
		String syntaxMsg = "Syntax: " + Uploader.class.getName() + " [baseUrl] [directory containing .gz synthea files] [number of threads] [start index]";
		Validate.isTrue(args.length == 4, syntaxMsg);
		myBaseUrl = StringUtil.chompCharacter(args[0], '/');
		Validate.isTrue(myBaseUrl.startsWith("http"), syntaxMsg);
		File sourceDir = new File(args[1]);
		Validate.isTrue(sourceDir.exists() && sourceDir.isDirectory() && sourceDir.canRead(), "Directory " + args[0] + " does not exist or can't be read");
		int threadCount = Integer.parseInt(args[2]);
		int startIndex = Integer.parseInt(args[3]);

		ourLog.info("Starting {} thread uploader from directory {}", threadCount, sourceDir.getAbsolutePath());
		myClient = createHttpClient();

		processFilesInDirectory(sourceDir, threadCount, startIndex);

	}

	protected void handleFile(File theFile, byte[] bytes, int theResourceCount, int theIndex) {
		StopWatch fileSw = new StopWatch();

		HttpPost request = new HttpPost(myBaseUrl);
		request.setEntity(new ByteArrayEntity(bytes));
		request.addHeader(HEADER_CONTENT_ENCODING, ENCODING_GZIP);
		request.addHeader(HEADER_CONTENT_TYPE, CT_FHIR_JSON_NEW);
		request.addHeader(HEADER_PREFER, HEADER_PREFER_RETURN + "=" + HEADER_PREFER_RETURN_MINIMAL);

		int errors = 0;
		while (true) {
			try (CloseableHttpResponse resp = myClient.execute(request)) {
                if (resp.getStatusLine().getStatusCode() == 200) {
                    if (errors > 0) {
						myRetryCount.addAndGet(errors);
					}
                    break;
                } else {
                    String respContent = IOUtils.toString(resp.getEntity().getContent(), StandardCharsets.UTF_8);
                    ourLog.warn("Failure in File[{}] HTTP {}: {}", theFile.getName(), resp.getStatusLine().getStatusCode(), respContent);
                    errors++;
                }
            } catch (Exception e) {
				ourLog.warn("Failure in File[{}]: {} - Cause {}", theFile.getName(), e, e.getCause() != null ? e.getCause().toString() : null);
				errors++;
			}

			if (errors > 3) {
				// Give up after 3
				myFailureCount.incrementAndGet();
				return;
			}
		}

		long filesUploaded = myFilesUploadedCount.incrementAndGet();
		long resourcesUploaded = myResourcesUploadedCount.addAndGet(theResourceCount);
		int resourcePerSecondOverall = (int) mySw.getThroughput(resourcesUploaded, TimeUnit.SECONDS);
		int filesPerSecondOverall = (int) mySw.getThroughput(filesUploaded, TimeUnit.SECONDS);
		int resourcePerSecondFile = (int) fileSw.getThroughput(theResourceCount, TimeUnit.SECONDS);

		myResourcesUploadedMeter.mark(theResourceCount);
		int resourcesPerSecondSliding = (int) (myResourcesUploadedMeter.getOneMinuteRate() / 60.0);

		String estRemaining = mySw.getEstimatedTimeRemaining(filesUploaded, myTotalFiles);
		int retryCount = myRetryCount.get();
		int failureCount = myFailureCount.get();
		ourLog.info("Uploaded file {}/{} in {} ({} res/sec), {} resources - {} files/sec(overall) - {} res/sec(sliding) - {} res/sec(overall) - Retry[{}] Fail[{}] EstRemaining {}: {}", theIndex, myTotalFiles, fileSw, resourcePerSecondFile, resourcesUploaded, filesPerSecondOverall, resourcesPerSecondSliding, resourcePerSecondOverall, retryCount, failureCount, estRemaining, theFile.getName());
	}

	@Override
	protected void starting() throws Exception {
		super.starting();

		myResourcesUploadedMeter = newMeter();

		myCsvWriter = new FileWriter("upload-synthea.csv", true);
		myCsvWriter.append("\n\n# Written: " + InstantType.now().asStringValue());
		myCsvWriter.append("\n# MillisSinceStart, FilesPerSecond, ResPerSecondOverall, ResPerSecondMovingAvg, Retries, Failures\n");

		myLogTimer = new Timer();
		long delay = DateUtils.MILLIS_PER_MINUTE;
		myLogTimer.scheduleAtFixedRate(new LogTask(), 0, delay);
	}

	@Override
	protected void finishing() throws Exception {
		myLogTimer.cancel();
		myCsvWriter.close();
	}

	public static void main(String[] args) throws Exception {
		new Uploader().run(args);
	}

	public static CloseableHttpClient createHttpClient() {
		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(5000, TimeUnit.MILLISECONDS);
		connectionManager.setMaxTotal(500);
		connectionManager.setDefaultMaxPerRoute(500);

		SocketConfig socketConfig = SocketConfig
			.copy(SocketConfig.DEFAULT)
			.setSoTimeout((int) (600 * DateUtils.MILLIS_PER_SECOND))
			.build();
		connectionManager.setDefaultSocketConfig(socketConfig);

		HttpClientBuilder builder = HttpClientBuilder
			.create()
			.setConnectionManager(connectionManager);

		HttpRequestInterceptor auth = new HttpBasicAuthInterceptor("admin", "password");
		builder.addInterceptorFirst(auth);

        return builder.build();
	}

	public static Meter newMeter() {
		return new Meter(new SlidingTimeWindowMovingAverages());
	}

	private class LogTask extends TimerTask {
		@Override
		public void run() {
			long filesUploaded = myFilesUploadedCount.get();
			long resourcesUploaded = myResourcesUploadedCount.get();
			int resourcePerSecondOverall = (int) mySw.getThroughput(resourcesUploaded, TimeUnit.SECONDS);
			int filesPerSecondOverall = (int) mySw.getThroughput(filesUploaded, TimeUnit.SECONDS);

			int resourcesPerSecondSliding = (int) myResourcesUploadedMeter.getMeanRate();

			int retryCount = myRetryCount.get();
			int failureCount = myFailureCount.get();

			try {
				long millis = mySw.getMillis();
				millis = millis - (millis % 1000);
				myCsvWriter.append(
					millis + "," +
						filesPerSecondOverall + "," +
						resourcePerSecondOverall + "," +
						resourcesPerSecondSliding + "," +
						retryCount + "," +
						failureCount +
						"\n"
				);
			} catch (IOException e) {
				ourLog.error("Failed to write CSV row", e);
				System.exit(0);
			}
		}
	}
}

import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.StringUtil;
import ca.uhn.fhir.util.ThreadPoolUtil;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class StaticResourcePerfTest {

	private static String myUrl;
	private static CloseableHttpClient ourClient;
	private static Meter myReadThroughputMeter;
	private static Histogram myReadLatencyHistogram;
	private static Meter myFailureMeter;
	private static StopWatch mySw;
	private static final AtomicLong ourCount = new AtomicLong(0);

	public static void main(String[] theArgs) {
		String syntaxMsg = "Syntax: " + StaticResourcePerfTest.class.getName() + " [URL] [thread count]";
		Validate.isTrue(theArgs.length == 2, syntaxMsg);
		myUrl = StringUtil.chompCharacter(theArgs[0], '/');
		int threadCount = Integer.parseInt(theArgs[1]);

		ourClient = Uploader.createHttpClient(true);
		ThreadPoolTaskExecutor threadPool = ThreadPoolUtil.newThreadPool(threadCount, threadCount, "worker-", 100);

		myReadThroughputMeter = Uploader.newMeter();
		myReadLatencyHistogram = Uploader.newHistogram();
		myFailureMeter = Uploader.newMeter();
		mySw = new StopWatch();

		while(true) {
			Runnable task = ()->{
				long start = System.currentTimeMillis();
				HttpGet get = new HttpGet(myUrl);
				try (var resp = ourClient.execute(get)) {
					IOUtils.toByteArray(resp.getEntity().getContent());
					if (resp.getStatusLine().getStatusCode() < 300) {
						myReadThroughputMeter.mark();
						myReadLatencyHistogram.update(System.currentTimeMillis() - start);
					} else {
						myFailureMeter.mark();
					}

				} catch (Exception e) {
					ourLog.warn("Failure: {}", e.toString());
					myFailureMeter.mark();
                }

				if (ourCount.incrementAndGet() % 100 == 0) {
					long totalRead = ourCount.get();
					long allTimeRead = (long) mySw.getThroughput(totalRead, TimeUnit.SECONDS);
					long perSecondRead = ((long) myReadThroughputMeter.getOneMinuteRate()) / 60L;
					long avgMillisPerRead = (long) myReadLatencyHistogram.getSnapshot().getMean();
					long perSecondError = ((long) myFailureMeter.getOneMinuteRate()) / 60L;
					long totalError = myFailureMeter.getCount();

					ourLog.info("Count[{}] - AllTime[{}/sec] MovAvg[{}/sec] Latency[{}ms/read] Error[{} {}/sec]", totalRead, allTimeRead, perSecondRead, avgMillisPerRead, totalError, perSecondError);
				}
            };
			threadPool.submit(task);
		}
	}
	private static final Logger ourLog = LoggerFactory.getLogger(StaticResourcePerfTest.class);
}

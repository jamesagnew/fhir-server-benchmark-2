import ca.uhn.fhir.jpa.dao.GZipUtil;
import ca.uhn.fhir.util.StopWatch;
import ca.uhn.fhir.util.ThreadPoolUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public abstract class BaseFileIterator {
	private static final Logger ourLog = LoggerFactory.getLogger(BaseFileIterator.class);
	protected int myTotalFiles;
	protected StopWatch mySw;

	protected void processFilesInDirectory(File sourceDir, int threadCount) throws IOException, InterruptedException, ExecutionException {
		ourLog.info("Scanning directory for files...");
		List<File> files = FileUtils.streamFiles(sourceDir, false, "gz").collect(Collectors.toList());
		myTotalFiles = files.size();
		ourLog.info("Have {} files", files.size());

		ThreadPoolTaskExecutor threadPool = ThreadPoolUtil.newThreadPool(threadCount, threadCount, "worker-", 100);

		mySw = new StopWatch();

		List<Future<?>> futures = new ArrayList<>();
		for (File nextFile : files) {
			if (nextFile.getName().startsWith("practitioner") || nextFile.getName().startsWith("hospital")) {
				continue;
			}
			futures.add(threadPool.submit(() -> processFile(nextFile)));
		}
		for (var next : futures) {
			next.get();
		}

		threadPool.shutdown();
	}


	private void processFile(File theFile) {
		try (FileInputStream fis = new FileInputStream(theFile)) {
			byte[] bytes = IOUtils.toByteArray(fis);
			String raw = GZipUtil.decompress(bytes);
			int resources = StringUtils.countMatches(raw, "\"resourceType\"") - 1;


			handleFile(theFile, bytes, resources);
		} catch (Exception theE) {
			throw new RuntimeException(theE);
		}
	}

	protected abstract void handleFile(File theFile, byte[] bytes, int theResourceCount);


}

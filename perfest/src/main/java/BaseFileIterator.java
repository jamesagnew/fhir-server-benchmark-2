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
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.util.Comparator.comparing;

public abstract class BaseFileIterator {
	private static final Logger ourLog = LoggerFactory.getLogger(BaseFileIterator.class);
	protected int myTotalFiles;
	protected StopWatch mySw;
	protected final AtomicLong myFilesUploadedCount = new AtomicLong(0);
	protected final AtomicLong myResourcesUploadedCount = new AtomicLong(0);

	protected void processFilesInDirectory(File sourceDir, int threadCount, int theStartIndex) throws Exception {
		ourLog.info("Scanning directory for files...");
		List<File> files = FileUtils
			.streamFiles(sourceDir, true, "gz")
			.sorted(comparing(File::getName))
			.collect(Collectors.toList());
		myTotalFiles = files.size();
		ourLog.info("Have {} files", files.size());

		ThreadPoolTaskExecutor threadPool = ThreadPoolUtil.newThreadPool(threadCount, threadCount, "worker-", 100);

		mySw = new StopWatch();

		starting();

		try {
			List<Future<?>> futures = new ArrayList<>();
            for (ListIterator<File> iterator = files.listIterator(); iterator.hasNext(); ) {
				int index = iterator.nextIndex();
				if (index < theStartIndex) {
					continue;
				}
                File nextFile = iterator.next();
                if (nextFile.getName().startsWith("practitioner") || nextFile.getName().startsWith("hospital")) {
					ourLog.info("Skipping file {}: {}", index, nextFile.getName());
                    continue;
                }
                futures.add(threadPool.submit(() -> processFile(nextFile, index)));
            }
			for (var next : futures) {
				next.get();
			}
		} finally {
			threadPool.shutdown();
			finishing();
		}
	}

	/** For overriding */
	protected void finishing() throws Exception {
		// nothing
	}

	/** For overriding */
	protected void starting() throws Exception {
		// nothing
	}


	private void processFile(File theFile, int theIndex) {
		try (FileInputStream fis = new FileInputStream(theFile)) {
			byte[] bytes = IOUtils.toByteArray(fis);
			String raw = GZipUtil.decompress(bytes);
			int resources = StringUtils.countMatches(raw, "\"resourceType\"") - 1;


			handleFile(theFile, bytes, resources, theIndex);
		} catch (Exception theE) {
			throw new RuntimeException(theE);
		}
	}

	protected abstract void handleFile(File theFile, byte[] bytes, int theResourceCount, int theIndex);


}

import org.apache.commons.lang3.Validate;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class UploadFileCounter extends BaseFileIterator {
	private static final Logger ourLog = LoggerFactory.getLogger(UploadFileCounter.class);
	private AtomicInteger myFilesUploadedCount = new AtomicInteger(0);
	private AtomicInteger myResourcesUploadedCount = new AtomicInteger(0);

	@Override
	protected void handleFile(File theFile, byte[] bytes, int theResourceCount) {
		int filesUploaded = myFilesUploadedCount.incrementAndGet();
		int resourcesUploaded = myResourcesUploadedCount.addAndGet(theResourceCount);
		String estRemaining = mySw.getEstimatedTimeRemaining(filesUploaded, myTotalFiles);
		ourLog.info("Uploaded file {}/{}, {} resources - EstRemaining: {}", filesUploaded, myTotalFiles, resourcesUploaded, estRemaining);
	}

	public static void main(String[] theArgs) throws Exception {
		new UploadFileCounter().run(theArgs);
	}

	private void run(String[] theArgs) throws Exception {
		String syntaxMsg = "Syntax: " + UploadFileCounter.class.getName() + " [directory containing .gz synthea files]";
		Validate.isTrue(theArgs.length == 1, syntaxMsg);
		File sourceDir = new File(theArgs[0]);
		Validate.isTrue(sourceDir.exists() && sourceDir.isDirectory() && sourceDir.canRead(), "Directory " + theArgs[0] + " does not exist or can't be read");

		this.processFilesInDirectory(sourceDir, 10);
	}

}

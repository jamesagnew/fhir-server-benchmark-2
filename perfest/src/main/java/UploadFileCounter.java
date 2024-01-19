import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class UploadFileCounter extends BaseFileIterator {
	private static final Logger ourLog = LoggerFactory.getLogger(UploadFileCounter.class);

	@Override
	protected void handleFile(File theFile, byte[] bytes, int theResourceCount) {
		long filesUploaded = myFilesUploadedCount.incrementAndGet();
		long resourcesUploaded = myResourcesUploadedCount.addAndGet(theResourceCount);
		String estRemaining = mySw.getEstimatedTimeRemaining(filesUploaded, myTotalFiles);
		ourLog.info("Counted file {}/{}, {} resources - EstRemaining: {}", filesUploaded, myTotalFiles, resourcesUploaded, estRemaining);
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

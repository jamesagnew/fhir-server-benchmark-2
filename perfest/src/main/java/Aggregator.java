import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.util.ClasspathUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class Aggregator {

	private static final int ourFiles = 4;
	private static final Logger ourLog = LoggerFactory.getLogger(Aggregator.class);

	private void run() throws IOException {
		List<Iterator<CSVRecord>> iterators = new ArrayList<>();
		for (int i = 1; i <= ourFiles; i++) {
			String input = ClasspathUtil.loadResource("/bench" + i + ".csv");
			CSVParser p = getDefaultParser(input);
			Iterator<CSVRecord> iterator = p.iterator();
			iterators.add(iterator);
		}

		StringBuilder output = new StringBuilder();
		output
			.append("TimeElapsed, ")
			.append("ThreadCount, ")
			.append("ReadPerSec, ")
			.append("ReadMsPerTxAvg, ")
			.append("ReadMsPerTx75Pct, ")
			.append("ReadMsPerTx95Pct, ")
			.append("SearchPerSec, ")
			.append("SearchMsPerTxAvg, ")
			.append("SearchMsPerTx75Pct, ")
			.append("SearchMsPerTx95Pct, ")
			.append("CreatePerSec, ")
			.append("CreateMsPerTxAvg, ")
			.append("CreateMsPerTx75Pct, ")
			.append("CreateMsPerTx95Pct, ")
			.append("UpdatePerSec, ")
			.append("UpdateMsPerTxAvg, ")
			.append("UpdateMsPerTx75Pct, ")
			.append("UpdateMsPerTx95Pct")
			.append("\n");

		while (true) {
			List<CSVRecord> allRecords = iterators
				.stream()
				.filter(Iterator::hasNext)
				.map(Iterator::next)
				.collect(Collectors.toList());
			if (allRecords.size() < 4) {
				break;
			}

			Set<String> keySet = allRecords.stream().map(t -> t.toMap().keySet()).flatMap(Collection::stream).collect(Collectors.toSet());
			List<CSVRecord> nextRecords = allRecords.subList(0, 3);
			String timeStamp = null;
			long totalThreads = 0;
			long readPerSec = 0;
			long readMsPerTxAvg = 0;
			long readMsPerTx75 = 0;
			long readMsPerTx95 = 0;

			long searchPerSec = 0;
			long searchMsPerTxAvg = 0;
			long searchMsPerTx75 = 0;
			long searchMsPerTx95 = 0;

			long createPerSec = 0;
			long createMsPerTxAvg = 0;
			long createMsPerTx75 = 0;
			long createMsPerTx95 = 0;

			long updatePerSec = 0;
			long updateMsPerTxAvg = 0;
			long updateMsPerTx75 = 0;
			long updateMsPerTx95 = 0;

			for (String nextKey : keySet) {
				switch (nextKey) {
					case "MillisSinceStart": {
						long millis = Long.parseLong(allRecords.get(0).get(nextKey));
						timeStamp = formatMillis(millis);
						break;
					}
					case "ThreadCountPerOperation": {
						long threadCountPerOperation = Long.parseLong(allRecords.get(0).get(nextKey));
						totalThreads = threadCountPerOperation * 4;
						break;
					}
					case "MovingAvgReadPerSec": {
						readPerSec = getTotal(nextRecords, nextKey);
						break;
					}
					case "ReadAvgMsPerTx": {
						readMsPerTxAvg = getAverage(allRecords, nextKey);
						break;
					}
					case "Read75pctMsPerTx": {
						readMsPerTx75 = getAverage(allRecords, nextKey);
						break;
					}
					case "Read95pctMsPerTx": {
						readMsPerTx95 = getAverage(allRecords, nextKey);
						break;
					}

					case "MovingAvgSearchPerSec": {
						searchPerSec = getTotal(nextRecords, nextKey);
						break;
					}
					case "SearchAvgMsPerTx": {
						searchMsPerTxAvg = getAverage(allRecords, nextKey);
						break;
					}
					case "Search75pctMsPerTx": {
						searchMsPerTx75 = getAverage(allRecords, nextKey);
						break;
					}
					case "Search95pctMsPerTx": {
						searchMsPerTx95 = getAverage(allRecords, nextKey);
						break;
					}

					case "MovingAvgCreatePerSec": {
						createPerSec = getTotal(nextRecords, nextKey);
						break;
					}
					case "CreateAvgMsPerTx": {
						createMsPerTxAvg = getAverage(allRecords, nextKey);
						break;
					}
					case "Create75pctMsPerTx": {
						createMsPerTx75 = getAverage(allRecords, nextKey);
						break;
					}
					case "Create95pctMsPerTx": {
						createMsPerTx95 = getAverage(allRecords, nextKey);
						break;
					}
					
					case "MovingAvgUpdatePerSec": {
						updatePerSec = getTotal(nextRecords, nextKey);
						break;
					}
					case "UpdateAvgMsPerTx": {
						updateMsPerTxAvg = getAverage(allRecords, nextKey);
						break;
					}
					case "Update75pctMsPerTx": {
						updateMsPerTx75 = getAverage(allRecords, nextKey);
						break;
					}
					case "Update95pctMsPerTx": {
						updateMsPerTx95 = getAverage(allRecords, nextKey);
						break;
					}

				}
			}

			output
				.append(timeStamp).append(",")
				.append(totalThreads).append(",")
				.append(readPerSec).append(",")
				.append(readMsPerTxAvg).append(",")
				.append(readMsPerTx75).append(",")
				.append(readMsPerTx95).append(",")
				.append(searchPerSec).append(",")
				.append(searchMsPerTxAvg).append(",")
				.append(searchMsPerTx75).append(",")
				.append(searchMsPerTx95).append(",")
				.append(createPerSec).append(",")
				.append(createMsPerTxAvg).append(",")
				.append(createMsPerTx75).append(",")
				.append(createMsPerTx95).append(",")
				.append(updatePerSec).append(",")
				.append(updateMsPerTxAvg).append(",")
				.append(updateMsPerTx75).append(",")
				.append(updateMsPerTx95).append("\n");
			
//			ourLog.info("{} - Threads[{}] - Read[{}/sec {}ms/tx 75pct {} 95pct {}] - SearchPerSec[{}] - UpdatePerSec[{}] - CreatePerSec[{}]", timeStamp, totalThreads, readPerSec, readMsPerTxAvg, readMsPerTx75, readMsPerTx95, searchPerSec, updatePerSec, createPerSec);
		}


		ourLog.info(output.toString());

		try (FileWriter w = new FileWriter("output.csv")) {
			w.append(output.toString());
		}
	}

	private long getTotal(List<CSVRecord> theRecords, String theKey) {
		long total = 0;
		for (CSVRecord next : theRecords) {
			String nextValue = next.get(theKey);
			if (isNotBlank(nextValue)) {
				total += Long.parseLong(nextValue);
			}
		}
		return total;
	}

	private long getAverage(List<CSVRecord> theRecords, String theKey) {
		long total = 0;
		long count = 0;
		for (CSVRecord next : theRecords) {
			String nextValue = next.toMap().get(theKey);
			if (isNotBlank(nextValue)) {
				total += Long.parseLong(nextValue);
				count++;
			}
		}
		return total / count;
	}

	private String formatMillis(long theMillis) {
		StringBuilder buf = new StringBuilder();
		appendRightAlignedNumber(
			buf, "", 2, ((theMillis % DateUtils.MILLIS_PER_DAY) / DateUtils.MILLIS_PER_HOUR));
		appendRightAlignedNumber(
			buf, ":", 2, ((theMillis % DateUtils.MILLIS_PER_HOUR) / DateUtils.MILLIS_PER_MINUTE));
		appendRightAlignedNumber(
			buf, ":", 2, ((theMillis % DateUtils.MILLIS_PER_MINUTE) / DateUtils.MILLIS_PER_SECOND));
		return buf.toString();
	}

	public static void main(String[] args) throws IOException {
		new Aggregator().run();
	}

	@Nonnull
	public static CSVFormat buildCSVFormat(String theDelimiter, boolean theParseQuotes) {
		String unescapedDelimiters = StringEscapeUtils.unescapeJava(theDelimiter);
		char delimiter = unescapedDelimiters.charAt(0);
		if (unescapedDelimiters.length() > 1) {
			ourLog.warn(
				"ETL Import contains more than one delimiter.  Only using the first delimiter: '{}'",
				StringEscapeUtils.escapeJava("" + delimiter));
		}
		CSVFormat format = CSVFormat.EXCEL.withFirstRecordAsHeader().withDelimiter(delimiter);

		if (!theParseQuotes) {
			format = format.withEscape('\\');
			format = format.withQuote(null);
			format = format.withQuoteMode(QuoteMode.NONE);
		}
		return format;
	}

	@Nonnull
	public static CSVParser buildCSVParser(Reader inputReader, CSVFormat format) {
		PushbackReader pushbackReader = new PushbackReader(inputReader);

		CSVParser reader;
		try {

			// Ignore any leading newlines
			while (true) {
				int nextChar = pushbackReader.read();
				if (nextChar == -1) {
					break;
				}
				if (nextChar != '\r' && nextChar != '\n' && nextChar != ' ') {
					pushbackReader.unread(nextChar);
					break;
				}
			}

			int firstChar = pushbackReader.read();
			if (firstChar != '#') {
				pushbackReader.unread(firstChar);
			}

			reader = format.parse(pushbackReader);
		} catch (IOException e) {
			throw new InternalErrorException(e);
		}

		ourLog.info("Got CSV headers: {}", reader.getHeaderMap().keySet());
		return reader;
	}

	/**
	 * Creates a CSVParser wrapping the provided CSV file contents, with the following default options:
	 * <ul>
	 * <li>Comma (",") as separator character</li>
	 * <li>Don't parse quotes</li>
	 * <li>Ignore surrounding spaces</li>
	 * <li>LF to separate records</li>
	 * </ul>
	 */
	@Nonnull
	public static CSVParser getDefaultParser(String csv) {
		StringReader reader = new StringReader(csv);
		CSVFormat format =
			buildCSVFormat(",", false).withIgnoreSurroundingSpaces(true).withRecordSeparator(CharUtils.LF);
		return buildCSVParser(reader, format);
	}


	static void appendRightAlignedNumber(
		StringBuilder theStringBuilder, String thePrefix, int theNumberOfDigits, long theValueToAppend) {
		theStringBuilder.append(thePrefix);
		if (theNumberOfDigits > 1) {
			int pad = (theNumberOfDigits - 1);
			for (long xa = theValueToAppend; xa > 9 && pad > 0; xa /= 10) {
				pad--;
			}
			for (int xa = 0; xa < pad; xa++) {
				theStringBuilder.append('0');
			}
		}
		theStringBuilder.append(theValueToAppend);
	}

}

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
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class Aggregator {

	private static final int ourFiles = 3;
	private static final Logger ourLog = LoggerFactory.getLogger(Aggregator.class);

	private void run() {
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
			.append("SearchPerSec, ")
			.append("CreatePerSec, ")
			.append("UpdatePerSec, ");

		while (iterators.get(0).hasNext()) {
			List<CSVRecord> nextRecords = iterators
				.stream()
				.map(t -> t.next())
				.collect(Collectors.toList());
			for (String nextKey : nextRecords.get(0).toMap().keySet()) {
				switch (nextKey) {
					case "MillisSinceStart":
						long millis = Long.parseLong(nextRecords.get(0).get("MillisSinceStart"));
						ourLog.info("Millis: {}", formatMillis(millis));
				}
			}
		}
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

	public static void main(String[] args) {
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

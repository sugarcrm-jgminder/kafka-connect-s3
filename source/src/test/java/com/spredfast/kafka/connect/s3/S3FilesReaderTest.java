package com.spredfast.kafka.connect.s3;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.Test;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListNextBatchOfObjectsRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.spredfast.kafka.connect.s3.sink.BlockGZIPFileWriter;
import com.spredfast.kafka.connect.s3.source.S3FilesReader;
import com.spredfast.kafka.connect.s3.source.S3Offset;
import com.spredfast.kafka.connect.s3.source.S3Partition;
import com.spredfast.kafka.connect.s3.source.S3SourceConfig;
import com.spredfast.kafka.connect.s3.source.S3SourceRecord;

/**
 * Covers S3 and reading raw byte records. Closer to an integration test.
 */
public class S3FilesReaderTest {
	private static final Layout.Parser LAYOUT_PARSER = new GroupedByDateLayout.Parser();

	@Test
	public void testReadingBytesFromS3() throws IOException, NoSuchFieldException {
		final Path dir = Files.createTempDirectory("s3FilesReaderTest");
		givenSomeData(dir);

		final AmazonS3 client = givenAMockS3Client(dir);

		List<String> results = whenTheRecordsAreRead(client, true, 3);

		thenTheyAreFilteredAndInOrder(results);
	}

	@Test
	public void testExcludingByMessageKey() throws IOException, NoSuchFieldException {
		final Path dir = Files.createTempDirectory("s3FilesReaderTest");
		givenSomeData(dir);

		final AmazonS3 client = givenAMockS3Client(dir);

		List<String> results = whenTheRecordsAreRead(client, Arrays.asList("1-0", "ololo"));
		assertEquals(Arrays.asList(
			"key0-0=value0-0",
			"key1-1=value1-1"
		), results);
	}

	@Test
	public void testReadingBytesFromS3_multiPartition() throws IOException, NoSuchFieldException {
		// scenario: multiple partition files at the end of a listing, page size >  # of files
		// do we read all of them?
		final Path dir = Files.createTempDirectory("s3FilesReaderTest");
		givenASingleDayWithManyPartitions(dir);

		final AmazonS3 client = givenAMockS3Client(dir);

		List<String> results = whenTheRecordsAreRead(client, true, 10);

		thenTheyAreFilteredAndInOrder(results);
	}

	@Test
	public void testReadingBytesFromS3_withOffsets() throws IOException, NoSuchFieldException {
		final Path dir = Files.createTempDirectory("s3FilesReaderTest");
		givenSomeData(dir);

		final AmazonS3 client = givenAMockS3Client(dir);

		List<String> results = whenTheRecordsAreRead(givenAReaderWithOffsets(client,
			"prefix/2015-12-31/topic-00003-000000000001.gz", 5L, "00003"));

		assertEquals(Arrays.asList(
			"willbe=skipped5",
			"willbe=skipped6",
			"willbe=skipped7",
			"willbe=skipped8",
			"willbe=skipped9"
		), results);
	}


	@Test
	public void testReadingBytesFromS3_withOffsetsAtEndOfFile() throws IOException, NoSuchFieldException {
		final Path dir = Files.createTempDirectory("s3FilesReaderTest");
		givenSomeData(dir);

		final AmazonS3 client = givenAMockS3Client(dir);

		// this file will be skipped
		List<String> results = whenTheRecordsAreRead(givenAReaderWithOffsets(client,
			"prefix/2015-12-30/topic-00003-000000000000.gz", 1L, "00003"));

		assertEquals(Arrays.asList(
			"willbe=skipped1",
			"willbe=skipped2",
			"willbe=skipped3",
			"willbe=skipped4",
			"willbe=skipped5",
			"willbe=skipped6",
			"willbe=skipped7",
			"willbe=skipped8",
			"willbe=skipped9"
		), results);
	}

	S3FilesReader givenAReaderWithOffsets(AmazonS3 client, String marker, long nextOffset, final String partition) {
		Map<S3Partition, S3Offset> offsets = new HashMap<>();
		int partInt = Integer.valueOf(partition, 10);
		offsets.put(S3Partition.from("bucket", "prefix", "topic", partInt),
			S3Offset.from(marker, nextOffset - 1 /* an S3 offset is the last record processed, so go back 1 to consume next */));
		return new S3FilesReader(new S3SourceConfig("bucket", "prefix", 1, null, S3FilesReader.InputFilter.GUNZIP,
			p -> partInt == p, null), client, offsets, LAYOUT_PARSER, () -> new BytesRecordReader(true));
	}

	@Test
	public void testReadingBytesFromS3_withoutKeys() throws IOException, NoSuchFieldException {
		final Path dir = Files.createTempDirectory("s3FilesReaderTest");
		givenSomeData(dir, false);

		final AmazonS3 client = givenAMockS3Client(dir);

		List<String> results = whenTheRecordsAreRead(client, false);

		theTheyAreInOrder(results);
	}

	void theTheyAreInOrder(List<String> results) {
		List<String> expected = Arrays.asList(
			"value0-0",
			"value1-0",
			"value1-1"
		);
		assertEquals(expected, results);
	}

	private void thenTheyAreFilteredAndInOrder(List<String> results) {
		List<String> expected = Arrays.asList(
			"key0-0=value0-0",
			"key1-0=value1-0",
			"key1-1=value1-1"
		);
		assertEquals(expected, results);
	}

	private List<String> whenTheRecordsAreRead(AmazonS3 client, boolean fileIncludesKeys) {
		return whenTheRecordsAreRead(client, fileIncludesKeys, 1);
	}

	private List<String> whenTheRecordsAreRead(AmazonS3 client, List<String> messageKeyExcludeList) {
		S3SourceConfig config = new S3SourceConfig("bucket", "prefix", 3, "prefix/2016-01-01", S3FilesReader.InputFilter.GUNZIP, null, messageKeyExcludeList);
		S3FilesReader reader = new S3FilesReader(config, client, null, LAYOUT_PARSER, () -> new BytesRecordReader(true));
		return whenTheRecordsAreRead(reader);
	}

	private List<String> whenTheRecordsAreRead(AmazonS3 client, boolean fileIncludesKeys, int pageSize) {
		S3FilesReader reader = new S3FilesReader(new S3SourceConfig("bucket", "prefix", pageSize, "prefix/2016-01-01", S3FilesReader.InputFilter.GUNZIP, null, null), client, null, LAYOUT_PARSER, () -> new BytesRecordReader(fileIncludesKeys));
		return whenTheRecordsAreRead(reader);
	}

	private List<String> whenTheRecordsAreRead(S3FilesReader reader) {
		List<String> results = new ArrayList<>();
		for (S3SourceRecord record : reader) {
			results.add((record.key() == null ? "" : new String(record.key()) + "=") + new String(record.value()));
		}
		return results;
	}

	private AmazonS3 givenAMockS3Client(final Path dir) throws NoSuchFieldException {
		final AmazonS3 client = mock(AmazonS3Client.class);
		// The aws-java-sdk v1.11+ calls a protected method beforeClientExecution that causes an exception
		// due to the requestHandler2s member variable of an abstract super class being null when mocked.
		// Since we aren't testing beforeClientExecution, default the member to an empty list to allow the
		// test to proceed.
		FieldSetter.setField(client, AmazonS3Client.class.getSuperclass().getDeclaredField("requestHandler2s"), Collections.emptyList());

		when(client.listObjects(any(ListObjectsRequest.class))).thenAnswer(new Answer<ObjectListing>() {
			@Override
			public ObjectListing answer(InvocationOnMock invocationOnMock) throws Throwable {
				final ListObjectsRequest req = (ListObjectsRequest) invocationOnMock.getArguments()[0];
				ObjectListing listing = new ObjectListing();

				final Set<File> files = new TreeSet<>();
				Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult preVisitDirectory(Path toCheck, BasicFileAttributes attrs) throws IOException {
						if (toCheck.startsWith(dir)) {
							return FileVisitResult.CONTINUE;
						}
						return FileVisitResult.SKIP_SUBTREE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						String key = key(file.toFile());
						if (req.getMarker() == null
							|| key.compareTo(req.getMarker()) > 0) {
							files.add(file.toFile());
						}
						return FileVisitResult.CONTINUE;
					}
				});

				List<S3ObjectSummary> summaries = new ArrayList<>();
				int count = 0;
				for (File file : files) {
					if (count++ < req.getMaxKeys()) {
						S3ObjectSummary summary = new S3ObjectSummary();
						String key = key(file);
						summary.setKey(key);
						listing.setNextMarker(key);
							summaries.add(summary);
					} else {
						break;
					}
				}

				listing.setMaxKeys(req.getMaxKeys());

				listing.getObjectSummaries().addAll(summaries);
				listing.setTruncated(files.size() > req.getMaxKeys());

				return listing;
			}

			private String key(File file) {
				return file.getAbsolutePath().substring(dir.toAbsolutePath().toString().length() + 1);
			}
		});
		when(client.listNextBatchOfObjects(any(ObjectListing.class))).thenCallRealMethod();
		when(client.listNextBatchOfObjects(any(ListNextBatchOfObjectsRequest.class))).thenCallRealMethod();

		when(client.getObject(anyString(), anyString())).thenAnswer(new Answer<S3Object>() {
			@Override
			public S3Object answer(InvocationOnMock invocationOnMock) throws Throwable {
				String key = (String) invocationOnMock.getArguments()[1];
				return getFile(key, dir);
			}
		});
		when(client.getObject(any(GetObjectRequest.class))).thenAnswer(new Answer<S3Object>() {
			@Override
			public S3Object answer(InvocationOnMock invocationOnMock) throws Throwable {
				String key = ((GetObjectRequest) invocationOnMock.getArguments()[0]).getKey();
				return getFile(key, dir);
			}
		});
		when(client.getObjectMetadata(anyString(), anyString())).thenAnswer(new Answer<S3Object>() {
			@Override
			public S3Object answer(InvocationOnMock invocationOnMock) throws Throwable {
				String key = (String) invocationOnMock.getArguments()[1];
				if (!new File(dir.toString(), key).exists()) {
					AmazonServiceException e = new AmazonServiceException("Nope: " + key);
					e.setErrorCode("NoSuchKey");
					throw e;
				}
				return null;
			}
		});
		return client;
	}

	S3Object getFile(String key, Path dir) throws FileNotFoundException {
		S3Object obj = mock(S3Object.class);
		File file = new File(dir.toString(), key);
		when(obj.getKey()).thenReturn(file.getName());
		S3ObjectInputStream stream = new S3ObjectInputStream(new FileInputStream(file), null);
		when(obj.getObjectContent()).thenReturn(stream);
		return obj;
	}

	private void givenASingleDayWithManyPartitions(Path dir) throws IOException {
		givenASingleDayWithManyPartitions(dir, true);
	}

	private void givenASingleDayWithManyPartitions(Path dir, boolean includeKeys) throws IOException {
		try (BlockGZIPFileWriter p0 = new BlockGZIPFileWriter(dir.toFile(), 0, 512);
			 BlockGZIPFileWriter p1 = new BlockGZIPFileWriter(dir.toFile(), 0, 512);
		) {
			write(p0, "key0-0".getBytes(), "value0-0".getBytes(), includeKeys);
			upload(p0, dir, "2016-01-01", 0);

			write(p1, "key1-0".getBytes(), "value1-0".getBytes(), includeKeys);
			write(p1, "key1-1".getBytes(), "value1-1".getBytes(), includeKeys);
			upload(p1, dir, "2016-01-01", 1);
		}
	}

	private void givenSomeData(Path dir) throws IOException {
		givenSomeData(dir, true);
	}

	private void givenSomeData(Path dir, boolean includeKeys) throws IOException {
		try (BlockGZIPFileWriter writer1 = new BlockGZIPFileWriter(dir.toFile(), 0, 512);
			 BlockGZIPFileWriter writer2 = new BlockGZIPFileWriter(dir.toFile(), 1, 512);
			 BlockGZIPFileWriter writer3 = new BlockGZIPFileWriter(dir.toFile(), 0, 512);
			 BlockGZIPFileWriter writer4 = new BlockGZIPFileWriter(dir.toFile(), 0, 512);
		) {
			write(writer1, "willbe".getBytes(), "skipped0".getBytes(), includeKeys);
			upload(writer1, dir, "2015-12-30", 3);

			for (int i = 1; i < 10; i++) {
				write(writer2, "willbe".getBytes(), ("skipped" + i).getBytes(), includeKeys);
			}
			upload(writer2, dir, "2015-12-31", 3);

			write(writer3, "key0-0".getBytes(), "value0-0".getBytes(), includeKeys);
			upload(writer3, dir, "2016-01-01", 0);

			write(writer4, "key1-0".getBytes(), "value1-0".getBytes(), includeKeys);
			write(writer4, "key1-1".getBytes(), "value1-1".getBytes(), includeKeys);
			upload(writer4, dir, "2016-01-02", 1);
		}
	}

	private void write(BlockGZIPFileWriter writer, byte[] key, byte[] value, boolean includeKeys) throws IOException {
		writer.write(new ByteLengthFormat(includeKeys).newWriter().writeBatch(Stream.of(new ProducerRecord<>("", key, value))).collect(toList()), 1);
	}

	private void upload(BlockGZIPFileWriter writer, Path dir, String date, int partition) throws IOException {
		writer.close();
		rename(writer.getDataFile(), dir, date, partition, writer.getStartOffset(), ".gz");
		rename(writer.getIndexFile(), dir, date, partition, writer.getStartOffset(), ".index.json");
	}

	private void rename(File file, Path dir, String date, int partition, long startOffset, String extension) {
		Layout.Builder layoutBuilder = new GroupedByDateLayout.Builder(() -> date);
		final BlockMetadata metadata = new BlockMetadata(new TopicPartition("topic", partition), startOffset);
		final File dest = new File(dir.toFile(), "prefix/" + layoutBuilder.buildBlockPath(metadata) + extension);
		dest.getParentFile().mkdirs();
		assertTrue(file.renameTo(dest));
	}

}

/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.runners.worker;

import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.positionFromSplitResult;
import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.splitRequestAtByteOffset;
import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.splitRequestAtFraction;
import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.splitRequestAtPosition;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.readerProgressToCloudProgress;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.api.services.dataflow.model.ApproximateReportedProgress;
import com.google.api.services.dataflow.model.Position;
import com.google.cloud.dataflow.sdk.TestUtils;
import com.google.cloud.dataflow.sdk.coders.AtomicCoder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.coders.TextualIntegerCoder;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.io.TextIO.CompressionType;
import com.google.cloud.dataflow.sdk.util.CoderUtils;
import com.google.cloud.dataflow.sdk.util.IOChannelUtils;
import com.google.cloud.dataflow.sdk.util.MimeTypes;
import com.google.cloud.dataflow.sdk.util.common.worker.ExecutorTestUtils;
import com.google.cloud.dataflow.sdk.util.common.worker.NativeReader.LegacyReaderIterator;
import com.google.cloud.dataflow.sdk.util.common.worker.NativeReader.NativeReaderIterator;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Tests for TextReader.
 */
@RunWith(JUnit4.class)
public class TextReaderTest {
  private static final String[] fileContent = {
      "<First line>\n", "<Second line>\r\n", "<Third line>"
  };
  private static final long TOTAL_BYTES_COUNT;

  static {
    long sumLen = 0L;

    for (String s : fileContent) {
      sumLen += s.length();
    }
    TOTAL_BYTES_COUNT = sumLen;
  }

  @Rule
  public TemporaryFolder tmpFolder = new TemporaryFolder();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  /**
   * A coder that verifies that all lines are of the form {@code <...>},
   * to give further assurance that TextReader is never returning or even
   * trying to decode partial lines, in the tests where this coder is used.
   */
  private static class WholeLineVerifyingCoder extends AtomicCoder<String> {
    @Override
    public void encode(String value, OutputStream outStream, Context context)
        throws CoderException, IOException {
      StringUtf8Coder.of().encode(value, outStream, context);
    }

    @Override
    public String decode(InputStream inStream, Context context) throws CoderException, IOException {
      String res = StringUtf8Coder.of().decode(inStream, context);
      if (!res.trim().startsWith("<") || !res.trim().endsWith(">")) {
        throw new CoderException("A partial line was passed to the coder by TextReader: " + res);
      }
      return res;
    }
  }

  private File initTestFile() throws IOException {
    File tmpFile = tmpFolder.newFile();
    try (FileOutputStream output = new FileOutputStream(tmpFile)) {
      for (String s : fileContent) {
        output.write(s.getBytes());
      }
    }

    return tmpFile;
  }

  @Test
  public void testReadEmptyFile() throws Exception {
    TextReader<String> textReader = new TextReader<>(tmpFolder.newFile().getPath(), true, null,
        null, new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
    try (NativeReaderIterator<String> iterator = textReader.iterator()) {
      assertFalse(iterator.start());
    }
  }

  @Test
  public void testStrippedNewlines() throws Exception {
    testNewlineHandling("\r", true);
    testNewlineHandling("\r\n", true);
    testNewlineHandling("\n", true);
  }

  @Test
  public void testStrippedNewlinesAtEndOfReadBuffer() throws Exception {
    boolean stripNewLines = true;
    StringBuilder payload = new StringBuilder();
    payload.append('<');
    for (int i = 0; i < TextReader.BUF_SIZE - 4; ++i) {
      payload.append('a');
    }
    payload.append('>');
    String[] lines = {payload.toString(), payload.toString()};
    testStringPayload(lines, "\r", stripNewLines);
    testStringPayload(lines, "\r\n", stripNewLines);
    testStringPayload(lines, "\n", stripNewLines);
  }

  @Test
  public void testUnstrippedNewlines() throws Exception {
    testNewlineHandling("\r", false);
    testNewlineHandling("\r\n", false);
    testNewlineHandling("\n", false);
  }

  @Test
  public void testUnstrippedNewlinesAtEndOfReadBuffer() throws Exception {
    boolean stripNewLines = false;
    StringBuilder payload = new StringBuilder();
    for (int i = 0; i < TextReader.BUF_SIZE - 2; ++i) {
      payload.append('a');
    }
    String[] lines = {payload.toString(), payload.toString()};
    testStringPayload(lines, "\r", stripNewLines);
    testStringPayload(lines, "\r\n", stripNewLines);
    testStringPayload(lines, "\n", stripNewLines);
  }

  @Test
  public void testStartPosition() throws Exception {
    File tmpFile = initTestFile();

    {
      TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), false, 13L, null,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
        assertEquals("<Second line>\r\n", iterator.next());
        assertEquals("<Third line>", iterator.next());
        assertFalse(iterator.hasNext());
        // The first '1' in the array represents the reading of '\n' between first and
        // second line, to confirm that we are reading from the beginning of a record.
        assertEquals(Arrays.asList(1, 15, 12), observer.getActualSizes());
      }
    }

    {
      TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), false, 24L, null,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
        assertEquals("<Third line>", iterator.next());
        assertFalse(iterator.hasNext());
        // The first '5' in the array represents the reading of a portion of the second
        // line, which had to be read to find the beginning of the third line.
        assertEquals(Arrays.asList(5, 12), observer.getActualSizes());
      }
    }

    {
      TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), true, 0L, 22L,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
        assertEquals("<First line>", iterator.next());
        assertEquals("<Second line>", iterator.next());
        assertFalse(iterator.hasNext());
        assertEquals(Arrays.asList(13, 15), observer.getActualSizes());
      }
    }

    {
      TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), true, 1L, 20L,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
        assertEquals("<Second line>", iterator.next());
        assertFalse(iterator.hasNext());
        // The first '13' in the array represents the reading of the entire first
        // line, which had to be read to find the beginning of the second line.
        assertEquals(Arrays.asList(13, 15), observer.getActualSizes());
      }
    }
  }

  @Test
  public void testUtf8Handling() throws Exception {
    File tmpFile = tmpFolder.newFile();
    try (FileOutputStream output = new FileOutputStream(tmpFile)) {
      // first line:  €\n
      // second line: ¢\n
      output.write(
          new byte[] {(byte) 0xE2, (byte) 0x82, (byte) 0xAC, '\n', (byte) 0xC2, (byte) 0xA2, '\n'});
    }

    {
      // 3L is after the first line if counting codepoints, but within
      // the first line if counting chars.  So correct behavior is to return
      // just one line, since offsets are in chars, not codepoints.
      TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), true, 0L, 3L,
          StringUtf8Coder.of(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
        assertArrayEquals("€".getBytes("UTF-8"), iterator.next().getBytes("UTF-8"));
        assertFalse(iterator.hasNext());
        assertEquals(Arrays.asList(4), observer.getActualSizes());
      }
    }

    {
      // Starting location is mid-way into a codepoint.
      // Ensures we don't fail when skipping over an incomplete codepoint.
      TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), true, 2L, null,
          StringUtf8Coder.of(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
        assertArrayEquals("¢".getBytes("UTF-8"), iterator.next().getBytes("UTF-8"));
        assertFalse(iterator.hasNext());
        // The first '3' in the array represents the reading of a portion of the first
        // line, which had to be read to find the beginning of the second line.
        assertEquals(Arrays.asList(3, 3), observer.getActualSizes());
      }
    }
  }

  private void testNewlineHandling(String separator, boolean stripNewlines) throws Exception {
    File tmpFile = tmpFolder.newFile();
    List<String> expected = Arrays.asList("", "  hi there  ", "bob", "", "  ", "--zowie!--", "");
    List<Integer> expectedSizes = new ArrayList<>();
    try (PrintStream writer = new PrintStream(new FileOutputStream(tmpFile))) {
      for (String line : expected) {
        writer.print(line);
        writer.print(separator);
        expectedSizes.add(line.length() + separator.length());
      }
    }

    TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), stripNewlines, null, null,
        StringUtf8Coder.of(), TextIO.CompressionType.UNCOMPRESSED);
    ExecutorTestUtils.TestReaderObserver observer =
        new ExecutorTestUtils.TestReaderObserver(textReader);

    List<String> actual = new ArrayList<>();
    try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
      while (iterator.hasNext()) {
        actual.add(iterator.next());
      }
    }

    if (stripNewlines) {
      assertEquals(expected, actual);
    } else {
      List<String> unstripped = new LinkedList<>();
      for (String s : expected) {
        unstripped.add(s + separator);
      }
      assertEquals(unstripped, actual);
    }

    assertEquals(expectedSizes, observer.getActualSizes());
  }

  private void testStringPayload(String[] lines, String separator, boolean stripNewlines)
      throws Exception {
    File tmpFile = tmpFolder.newFile();
    List<String> expected = new ArrayList<>();
    try (PrintStream writer = new PrintStream(new FileOutputStream(tmpFile))) {
      for (String line : lines) {
        writer.print(line);
        writer.print(separator);
        expected.add(stripNewlines ? line : line + separator);
      }
    }

    TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), stripNewlines, null, null,
        StringUtf8Coder.of(), TextIO.CompressionType.UNCOMPRESSED);
    List<String> actual = new ArrayList<>();
    try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
      while (iterator.hasNext()) {
        actual.add(iterator.next());
      }
    }
    assertEquals(expected, actual);
  }

  @Test
  public void testNonStringCoders() throws Exception {
    File tmpFile = tmpFolder.newFile();
    List<Integer> expected = TestUtils.INTS;
    List<Integer> expectedSizes = new ArrayList<>();
    try (PrintStream writer = new PrintStream(new FileOutputStream(tmpFile))) {
      for (Integer elem : expected) {
        byte[] encodedElem = CoderUtils.encodeToByteArray(TextualIntegerCoder.of(), elem);
        writer.print(elem);
        writer.print("\n");
        expectedSizes.add(1 + encodedElem.length);
      }
    }

    TextReader<Integer> textReader = new TextReader<>(tmpFile.getPath(), true, null, null,
        TextualIntegerCoder.of(), TextIO.CompressionType.UNCOMPRESSED);
    ExecutorTestUtils.TestReaderObserver observer =
        new ExecutorTestUtils.TestReaderObserver(textReader);

    List<Integer> actual = new ArrayList<>();
    try (LegacyReaderIterator<Integer> iterator = textReader.iterator()) {
      while (iterator.hasNext()) {
        actual.add(iterator.next());
      }
    }

    assertEquals(expected, actual);
    assertEquals(expectedSizes, observer.getActualSizes());
  }

  @Test
  public void testGetProgressNoEndOffset() throws Exception {
    File tmpFile = initTestFile();
    TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), false, 0L, null,
        new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);

    try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
      ApproximateReportedProgress progress = readerProgressToCloudProgress(iterator.getProgress());
      assertEquals(0L, progress.getPosition().getByteOffset().longValue());
      iterator.next();
      progress = readerProgressToCloudProgress(iterator.getProgress());
      assertEquals(13L, progress.getPosition().getByteOffset().longValue());
      iterator.next();
      progress = readerProgressToCloudProgress(iterator.getProgress());
      assertEquals(28L, progress.getPosition().getByteOffset().longValue());
      // Since end position is not specified, percentComplete should be null.
      assertNull(progress.getFractionConsumed());

      iterator.next();
      progress = readerProgressToCloudProgress(iterator.getProgress());
      assertEquals(40L, progress.getPosition().getByteOffset().longValue());
      assertFalse(iterator.hasNext());
    }
  }

  @Test
  public void testGetProgressWithEndOffset() throws Exception {
    File tmpFile = initTestFile();
    TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), false, 0L, 40L,
        new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);

    try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
      iterator.next();
      ApproximateReportedProgress progress = readerProgressToCloudProgress(iterator.getProgress());
      // Returned a record that starts at position 0 of 40 - 1/40 fraction consumed.
      assertEquals(1.0 / 40, progress.getFractionConsumed(), 1e-6);
      iterator.next();
      iterator.next();
      progress = readerProgressToCloudProgress(iterator.getProgress());
      // Returned a record that starts at position 28 - 29/40 consumed.
      assertEquals(1.0 * 29 / 40, progress.getFractionConsumed(), 1e-6);
      assertFalse(iterator.hasNext());
    }
  }

  @Test
  public void testUpdateStopPosition() throws Exception {
    final long end = 10L; // in the first line
    final long stop = 14L; // in the middle of the second line
    File tmpFile = initTestFile();
    long fileSize = tmpFile.length();

    // Illegal proposed stop position, no update.
    {
      TextReader<String> textReader = new TextReader<>(
          tmpFile.getPath(), false, 0L, fileSize,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);

      try (TextReader<String>.TextFileIterator iterator =
          (TextReader<String>.TextFileIterator) textReader.iterator()) {
        // Poke the iterator so we can test dynamic splitting.
        assertTrue(iterator.hasNext());

        assertNull(iterator.requestDynamicSplit(splitRequestAtPosition(new Position())));
      }
    }

    // Successful update.
    {
      TextReader<String> textReader = new TextReader<>(
          tmpFile.getPath(), false, 0L, fileSize,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (TextReader<String>.TextFileIterator iterator =
          (TextReader<String>.TextFileIterator) textReader.iterator()) {
        // Poke the iterator so we can test dynamic splitting.
        assertTrue(iterator.hasNext());

        assertEquals(fileSize, iterator.getEndOffset());
        assertEquals(
            Long.valueOf(stop),
            positionFromSplitResult(iterator.requestDynamicSplit(splitRequestAtByteOffset(stop)))
                .getByteOffset());
        assertEquals(stop, iterator.getEndOffset());
        assertEquals(fileContent[0], iterator.next());
        assertEquals(fileContent[1], iterator.next());
        assertFalse(iterator.hasNext());
        assertEquals(
            Arrays.asList(fileContent[0].length(), fileContent[1].length()),
            observer.getActualSizes());
      }
    }

    // Update based on fraction.
    {
      TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), false, 0L, fileSize,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (TextReader<String>.TextFileIterator iterator =
          (TextReader<String>.TextFileIterator) textReader.iterator()) {
        // Poke the iterator so we can test dynamic splitting.
        assertTrue(iterator.hasNext());

        // Trying to split at 0 or 1 will fail.
        assertNull(iterator.requestDynamicSplit(splitRequestAtFraction(0)));
        assertNull(iterator.requestDynamicSplit(splitRequestAtFraction(1)));

        // must be less than or equal to (size of first two lines / size of file) for this test to
        // pass.
        float splitPos = 0.61f;

        long stopPosition = (long) Math.ceil(fileSize * splitPos);
        assertEquals(fileSize, iterator.getEndOffset());
        assertEquals(
            Long.valueOf(stopPosition),
            positionFromSplitResult(iterator.requestDynamicSplit(splitRequestAtFraction(splitPos)))
                .getByteOffset());
        assertEquals(stopPosition, iterator.getEndOffset());
        assertEquals(fileContent[0], iterator.next());
        assertEquals(fileContent[1], iterator.next());
        assertFalse(iterator.hasNext());
        assertEquals(
            Arrays.asList(fileContent[0].length(), fileContent[1].length()),
            observer.getActualSizes());
      }
    }

    // Proposed stop position is before the current position, no update.
    {
      TextReader<String> textReader = new TextReader<>(
          tmpFile.getPath(), false, 0L, fileSize,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (TextReader<String>.TextFileIterator iterator =
          (TextReader<String>.TextFileIterator) textReader.iterator()) {
        assertEquals(fileContent[0], iterator.next());
        assertEquals(fileContent[1], iterator.next());
        assertThat(
            readerProgressToCloudProgress(iterator.getProgress()).getPosition().getByteOffset(),
            greaterThan(stop));
        assertTrue(iterator.hasNext());
        // The iterator just promised to return the next record, which is beyond "stop".
        assertNull(iterator.requestDynamicSplit(splitRequestAtByteOffset(stop)));
        assertEquals(fileSize, iterator.getEndOffset());
        assertTrue(iterator.hasNext());
        assertEquals(fileContent[2], iterator.next());
        assertEquals(
            Arrays.asList(
                fileContent[0].length(), fileContent[1].length(), fileContent[2].length()),
            observer.getActualSizes());
      }
    }

    // Proposed stop position is after the current stop (end) position, no update.
    {
      TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), false, null, end,
          new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
      ExecutorTestUtils.TestReaderObserver observer =
          new ExecutorTestUtils.TestReaderObserver(textReader);

      try (TextReader<String>.TextFileIterator iterator =
          (TextReader<String>.TextFileIterator) textReader.iterator()) {
        assertTrue(iterator.hasNext());
        assertEquals(fileContent[0], iterator.next());
        assertNull(iterator.requestDynamicSplit(splitRequestAtByteOffset(stop)));
        assertEquals(end, iterator.getEndOffset());
        assertFalse(iterator.hasNext());
        assertEquals(Arrays.asList(fileContent[0].length()), observer.getActualSizes());
      }
    }
  }

  @Test
  public void testUpdateStopPositionExhaustive() throws Exception {
    File tmpFile = initTestFile();

    // Checks for every possible position in the file, that either we fail to
    // "updateStop" at it, or we succeed and then reading both halves together
    // yields the original file with no missed records or duplicates.
    for (long start = 0; start < TOTAL_BYTES_COUNT - 1; start++) {
      for (long end = start + 1; end < TOTAL_BYTES_COUNT; end++) {
        for (long stop = start; stop <= end; stop++) {
          stopPositionTestInternal(start, end, stop, tmpFile);
        }
      }
    }

    // Test with null start/end positions.
    for (long stop = 0L; stop < TOTAL_BYTES_COUNT; stop++) {
      stopPositionTestInternal(null, null, stop, tmpFile);
    }
  }

  private void stopPositionTestInternal(
      Long startOffset, Long endOffset, Long stopOffset, File tmpFile) throws Exception {
    String readWithoutSplit;
    String readWithSplit1, readWithSplit2;
    StringBuilder accumulatedRead = new StringBuilder();

    // Read from source without split attempts.
    TextReader<String> textReader = new TextReader<>(tmpFile.getPath(), false, startOffset,
        endOffset, new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);

    try (TextReader<String>.TextFileIterator iterator =
        (TextReader<String>.TextFileIterator) textReader.iterator()) {
      while (iterator.hasNext()) {
        accumulatedRead.append(iterator.next());
      }
      readWithoutSplit = accumulatedRead.toString();
    }

    // Read the first half of the split.
    textReader = new TextReader<>(tmpFile.getPath(), false, startOffset, stopOffset,
        new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
    accumulatedRead = new StringBuilder();

    try (TextReader<String>.TextFileIterator iterator =
        (TextReader<String>.TextFileIterator) textReader.iterator()) {
      while (iterator.hasNext()) {
        accumulatedRead.append(iterator.next());
      }
      readWithSplit1 = accumulatedRead.toString();
    }

    // Read the second half of the split.
    textReader = new TextReader<>(tmpFile.getPath(), false, stopOffset, endOffset,
        new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
    accumulatedRead = new StringBuilder();

    try (TextReader<String>.TextFileIterator iterator =
        (TextReader<String>.TextFileIterator) textReader.iterator()) {
      while (iterator.hasNext()) {
        accumulatedRead.append(iterator.next());
      }
      readWithSplit2 = accumulatedRead.toString();
    }

    assertEquals(readWithoutSplit, readWithSplit1 + readWithSplit2);
  }

  private OutputStream getOutputStreamForCompressionType(
      OutputStream stream, CompressionType compressionType) throws IOException {
    switch (compressionType) {
      case GZIP:
        return new GZIPOutputStream(stream);
      case BZIP2:
        return new BZip2CompressorOutputStream(stream);
      case UNCOMPRESSED:
      case AUTO:
        return stream;
      default:
        fail("Unrecognized stream type");
    }
    return stream;
  }

  private File createFileWithCompressionType(
      String[] lines, String filename, CompressionType compressionType) throws IOException {
    File tmpFile = tmpFolder.newFile(filename);
    try (PrintStream writer =
            new PrintStream(
                getOutputStreamForCompressionType(
                    new FileOutputStream(tmpFile), compressionType))) {
      for (String line : lines) {
        writer.println(line);
      }
    }
    return tmpFile;
  }

  private void testCompressionTypeHelper(String[] lines, String filename,
      CompressionType outputCompressionType, CompressionType inputCompressionType)
      throws IOException {
    File tmpFile = createFileWithCompressionType(lines, filename, outputCompressionType);

    List<String> expected = new ArrayList<>();
    for (String line : lines) {
      expected.add(line);
    }

    TextReader<String> textReader = new TextReader<>(
        tmpFile.getPath(), true, null, null, new WholeLineVerifyingCoder(), inputCompressionType);

    List<String> actual = new ArrayList<>();
    try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
      while (iterator.hasNext()) {
        actual.add(iterator.next());
      }
    }
    assertEquals(expected, actual);
    tmpFile.delete();
  }

  @Test
  public void testCompressionTypeOneFile() throws IOException {
    String[] contents = {"<Miserable pigeon>", "<Vulnerable sparrow>", "<Brazen crow>"};
    // test AUTO compression type with different extensions
    testCompressionTypeHelper(contents, "test.gz", CompressionType.GZIP, CompressionType.AUTO);
    testCompressionTypeHelper(contents, "test.bz2", CompressionType.BZIP2, CompressionType.AUTO);
    testCompressionTypeHelper(
        contents, "test.txt", CompressionType.UNCOMPRESSED, CompressionType.AUTO);
    testCompressionTypeHelper(contents, "test", CompressionType.UNCOMPRESSED, CompressionType.AUTO);
    // test GZIP, BZIP2, and UNCOMPRESSED
    testCompressionTypeHelper(contents, "test.txt", CompressionType.GZIP, CompressionType.GZIP);
    testCompressionTypeHelper(contents, "test.txt", CompressionType.BZIP2, CompressionType.BZIP2);
    testCompressionTypeHelper(
        contents, "test.gz", CompressionType.UNCOMPRESSED, CompressionType.UNCOMPRESSED);
  }

  @Test
  public void testCompressionTypeFileGlob() throws IOException {
    String[][] contents = {
        {"<Miserable pigeon>", "<Vulnerable sparrow>", "<Brazen crow>"},
        {"<Timid osprey>", "<Lazy vulture>"},
        {"<Erratic finch>", "<Impressible parakeet>"},
    };
    File[] files = {
        createFileWithCompressionType(contents[0], "test.gz", CompressionType.GZIP),
        createFileWithCompressionType(contents[1], "test.bz2", CompressionType.BZIP2),
        createFileWithCompressionType(contents[2], "test.txt", CompressionType.UNCOMPRESSED),
    };

    List<String> expected = new ArrayList<>();
    for (String[] fileContents : contents) {
      for (String line : fileContents) {
        expected.add(line);
      }
    }

    String path = tmpFolder.getRoot().getPath() + System.getProperty("file.separator") + "*";

    TextReader<String> textReader =
        new TextReader<>(path, true, null, null, new WholeLineVerifyingCoder(),
                         CompressionType.AUTO);

    List<String> actual = new ArrayList<>();
    try (LegacyReaderIterator<String> iterator = textReader.iterator()) {
      while (iterator.hasNext()) {
        actual.add(iterator.next());
      }
    }
    assertThat(actual, containsInAnyOrder(expected.toArray()));
    for (File file : files) {
      file.delete();
    }
  }

  @Test
  public void testParallelismEstimatesDeclaredNotCompressed() throws IOException {
    File file =
        createFileWithCompressionType(fileContent, "test.gz", CompressionType.UNCOMPRESSED);
    TextReader<String> textReader =
        new TextReader<>(
            file.getPath(),
            true /*stripTrailingNewlines*/,
            null /*startPos*/,
            null /*endPos*/,
            new WholeLineVerifyingCoder(),
            CompressionType.UNCOMPRESSED);
    assertEquals(Double.POSITIVE_INFINITY, textReader.getTotalParallelism(), 0 /*tolerance*/);
    file.delete();
  }

  @Test
  public void testParallelismEstimatesDeclaredCompressed() throws IOException {
    File file = createFileWithCompressionType(fileContent, "test.txt", CompressionType.GZIP);
    TextReader<String> textReader =
        new TextReader<>(
            file.getPath(), true, null, null, new WholeLineVerifyingCoder(), CompressionType.GZIP);
    assertEquals(1, textReader.getTotalParallelism(), 0 /*tolerance*/);
    file.delete();
  }

  @Test
  public void testParallelismEstimatesAutoNotCompressed() throws IOException {
    File file =
        createFileWithCompressionType(fileContent, "test.txt", CompressionType.UNCOMPRESSED);
    TextReader<String> textReader =
        new TextReader<>(
            file.getPath(), true, null, null, new WholeLineVerifyingCoder(), CompressionType.AUTO);
    assertEquals(Double.POSITIVE_INFINITY, textReader.getTotalParallelism(), 0 /*tolerance*/);
    file.delete();
  }

  @Test
  public void testParallelismEstimatesAutoCompressed() throws IOException {
    File file = createFileWithCompressionType(fileContent, "test.gz", CompressionType.GZIP);
    TextReader<String> textReader =
        new TextReader<>(
            file.getPath(), true, null, null, new WholeLineVerifyingCoder(), CompressionType.AUTO);
    assertEquals(1, textReader.getTotalParallelism(), 0 /*tolerance*/);
    file.delete();
  }

  @Test
  public void testParallelismEstimatesPartialRead() throws IOException {
    File file =
        createFileWithCompressionType(fileContent, "test.txt", CompressionType.UNCOMPRESSED);
    TextReader<String> textReader =
        new TextReader<>(
            file.getPath(),
            true /*stripTrailingNewlines*/,
            10L /*startPos*/,
            17L /*endPos*/,
            new WholeLineVerifyingCoder(),
            CompressionType.AUTO);
    assertEquals(7, textReader.getTotalParallelism(), 0 /*tolerance*/);
    file.delete();
  }

  @Test
  public void testParallelismEstimatesCompressedGlob() throws IOException {
    File gzip = createFileWithCompressionType(fileContent, "test.gz", CompressionType.GZIP);
    File bzip = createFileWithCompressionType(fileContent, "test.bz2", CompressionType.BZIP2);
    String pattern = new File(tmpFolder.getRoot(), "*").getPath();
    TextReader<String> textReader =
        new TextReader<>(
            pattern, true, null, null, new WholeLineVerifyingCoder(), CompressionType.AUTO);
    assertEquals(2, textReader.getTotalParallelism(), 0 /*tolerance*/);
    gzip.delete();
    bzip.delete();
  }

  @Test
  public void testParallelismEstimatesMixedGlob() throws IOException {
    File gzip = createFileWithCompressionType(fileContent, "test.gz", CompressionType.GZIP);
    File txt = createFileWithCompressionType(fileContent, "test.txt", CompressionType.UNCOMPRESSED);
    String pattern = new File(tmpFolder.getRoot(), "*").getPath();
    TextReader<String> textReader =
        new TextReader<>(
            pattern, true, null, null, new WholeLineVerifyingCoder(), CompressionType.AUTO);
    assertEquals(Double.POSITIVE_INFINITY, textReader.getTotalParallelism(), 0 /*tolerance*/);
    gzip.delete();
    txt.delete();
  }

  @Test
  public void testErrorOnFileNotFound() throws Exception {
    expectedException.expect(FileNotFoundException.class);
    TextReader<String> textReader = new TextReader<>(
        "file-not-found", true, 0L, 100L,
        new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
    textReader.iterator().close();
  }

  @Test
  public void testErrorOnMultipleFiles() throws Exception {
    File file1 = tmpFolder.newFile("foo1.avro");
    File file2 = tmpFolder.newFile("foo2.avro");
    Channels.newOutputStream(IOChannelUtils.create(file1.getPath(), MimeTypes.BINARY)).close();
    Channels.newOutputStream(IOChannelUtils.create(file2.getPath(), MimeTypes.BINARY)).close();
    TextReader<String> textReader = new TextReader<>(
        new File(tmpFolder.getRoot(), "*").getPath(), true, 0L, 100L,
        new WholeLineVerifyingCoder(), TextIO.CompressionType.UNCOMPRESSED);
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("more than 1 file matched");
    textReader.iterator().close();
  }

  // TODO: sharded filenames
  // TODO: reading from GCS
}

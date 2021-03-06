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

package com.google.cloud.dataflow.sdk.util.common.worker;

import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.positionAtIndex;
import static com.google.cloud.dataflow.sdk.runners.worker.ReaderTestUtils.splitRequestAtIndex;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.cloudPositionToReaderPosition;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.cloudProgressToReaderProgress;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.readerProgressToCloudProgress;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.splitRequestToApproximateSplitRequest;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.toCloudPosition;
import static com.google.cloud.dataflow.sdk.testing.SystemNanoTimeSleeper.sleepMillis;
import static com.google.cloud.dataflow.sdk.util.common.Counter.AggregationKind;
import static com.google.cloud.dataflow.sdk.util.common.Counter.AggregationKind.MEAN;
import static com.google.cloud.dataflow.sdk.util.common.Counter.AggregationKind.SUM;
import static com.google.cloud.dataflow.sdk.util.common.worker.TestOutputReceiver.TestOutputCounter.getMeanByteCounterName;
import static com.google.cloud.dataflow.sdk.util.common.worker.TestOutputReceiver.TestOutputCounter.getObjectCounterName;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.api.services.dataflow.model.ApproximateReportedProgress;
import com.google.api.services.dataflow.model.ApproximateSplitRequest;
import com.google.api.services.dataflow.model.Position;
import com.google.cloud.dataflow.sdk.io.range.OffsetRangeTracker;
import com.google.cloud.dataflow.sdk.util.common.Counter;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.worker.ExecutorTestUtils.TestReader;
import com.google.common.base.Preconditions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;

/**
 * Tests for ReadOperation.
 */
@RunWith(JUnit4.class)
public class ReadOperationTest {

  private <T> void assertCounterKindAndContents(
      CounterSet counterSet, String name, AggregationKind kind, T contents) {
    @SuppressWarnings("unchecked")
    Counter<T> counter = (Counter<T>) counterSet.getExistingCounter(name);
    assertThat(counter.getKind(), equalTo(kind));
    assertThat(counter.getAggregate(), equalTo(contents));
  }

  private <T> void assertCounterMean(
      CounterSet counterSet, String name, long count, T aggregate) {
    @SuppressWarnings("unchecked")
    Counter<T> counter = (Counter<T>) counterSet.getExistingCounter(name);
    assertThat(counter.getKind(), equalTo(MEAN));
    assertThat(counter.getMean().getCount(), equalTo(count));
    assertThat(counter.getMean().getAggregate(), equalTo(aggregate));
  }

  private void assertCounterKind(
      CounterSet counterSet, String name, AggregationKind kind) {
    assertThat(counterSet.getExistingCounter(name).getKind(), equalTo(kind));
  }

  /**
   * Tests that a {@link ReadOperation} has expected counters, and that their
   * values are reasonable.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testRunReadOperation() throws Exception {
    TestReader reader = new TestReader("hi", "there", "", "bob");

    CounterSet counterSet = new CounterSet();
    String counterPrefix = "test-";
    StateSampler stateSampler = new StateSampler(counterPrefix, counterSet.getAddCounterMutator());
    TestOutputReceiver receiver = new TestOutputReceiver(counterSet);

    ReadOperation readOperation = ReadOperation.forTest(
        reader, receiver, counterPrefix, counterSet.getAddCounterMutator(), stateSampler);

    readOperation.start();
    readOperation.finish();

    assertThat(receiver.outputElems, containsInAnyOrder((Object) "hi", "there", "", "bob"));

    assertCounterKindAndContents(counterSet, "ReadOperation-ByteCount", SUM, 2L + 5 + 0 + 3);
    assertCounterKindAndContents(counterSet, getObjectCounterName("test_receiver_out"), SUM, 4L);
    assertCounterMean(counterSet, getMeanByteCounterName("test_receiver_out"), 4, 10L);
    assertCounterKind(counterSet, "test-ReadOperation-start-msecs", SUM);
    assertCounterKind(counterSet, "test-ReadOperation-process-msecs", SUM);
    assertCounterKind(counterSet, "test-ReadOperation-finish-msecs", SUM);
  }

  @Test
  public void testGetProgress() throws Exception {
    MockReaderIterator iterator = new MockReaderIterator(0, 5);
    CounterSet counterSet = new CounterSet();
    String counterPrefix = "test-";
    final ReadOperation readOperation = ReadOperation.forTest(new MockReader(iterator),
        new TestOutputReceiver("out", null, counterSet), counterPrefix,
        counterSet.getAddCounterMutator(),
        new StateSampler(counterPrefix, counterSet.getAddCounterMutator()));
    // Update progress not continuously, but so that it's never more than 1 record stale.
    readOperation.setProgressUpdatePeriodMs(150);

    Thread thread = runReadLoopInThread(readOperation);
    for (int i = 0; i < 5; ++i) {
      sleepMillis(500); // Wait for the operation to start and block.
      // Ensure that getProgress() doesn't block while the next() method is blocked.
      ApproximateReportedProgress progress = readerProgressToCloudProgress(
          readOperation.getProgress());
      long observedIndex = progress.getPosition().getRecordIndex().longValue();
      assertTrue("Actual: " + observedIndex, i == observedIndex || i == observedIndex + 1);
      iterator.offerNext(i);
    }
    thread.join();
  }

  @Test
  public void testDynamicSplit() throws Exception {
    MockReaderIterator iterator = new MockReaderIterator(0, 10);
    CounterSet counterSet = new CounterSet();
    MockOutputReceiver receiver = new MockOutputReceiver();
    ReadOperation readOperation = ReadOperation.forTest(new MockReader(iterator), receiver, "test-",
        counterSet.getAddCounterMutator(),
        new StateSampler("test-", counterSet.getAddCounterMutator()));
    readOperation.setProgressUpdatePeriodMs(ReadOperation.UPDATE_ON_EACH_ITERATION);

    // An unstarted ReadOperation refuses split requests.
    assertNull(
        readOperation.requestDynamicSplit(splitRequestAtIndex(8L)));

    Thread thread = runReadLoopInThread(readOperation);
    iterator.offerNext(0); // Await start() and return 0 from getCurrent().
    receiver.unblockProcess();
    // Await advance() and return 1 from getCurrent().
    iterator.offerNext(1);
    NativeReader.DynamicSplitResultWithPosition split =
        (NativeReader.DynamicSplitResultWithPosition)
            readOperation.requestDynamicSplit(splitRequestAtIndex(8L));
    assertNotNull(split);
    assertEquals(positionAtIndex(8L), toCloudPosition(split.getAcceptedPosition()));

    // Check that the progress has been recomputed.
    ApproximateReportedProgress progress = readerProgressToCloudProgress(
        readOperation.getProgress());
    assertEquals(1, progress.getPosition().getRecordIndex().longValue());
    assertEquals(2.0f / 8.0, progress.getFractionConsumed(), 0.001);

    receiver.unblockProcess();
    iterator.offerNext(2);
    receiver.unblockProcess();
    iterator.offerNext(3);

    // Should accept a split at an earlier position than previously requested.
    // Should reject a split at a later position than previously requested.
    // Note that here we're testing our own MockReaderIterator class, so it's kind of pointless,
    // but we're also testing that ReadOperation correctly relays the request to the iterator.
    split =
        (NativeReader.DynamicSplitResultWithPosition)
            readOperation.requestDynamicSplit(splitRequestAtIndex(6L));
    assertNotNull(split);
    assertEquals(positionAtIndex(6L), toCloudPosition(split.getAcceptedPosition()));
    split =
        (NativeReader.DynamicSplitResultWithPosition)
            readOperation.requestDynamicSplit(splitRequestAtIndex(6L));
    assertNull(split);
    receiver.unblockProcess();

    iterator.offerNext(4);
    receiver.unblockProcess();
    iterator.offerNext(5);
    receiver.unblockProcess();

    // Should return false from hasNext() and exit read loop now.

    thread.join();

    // Operation is now finished. Check that it refuses a split request.
    assertNull(readOperation.requestDynamicSplit(splitRequestAtIndex(5L)));
  }

  @Test
  public void testDynamicSplitDoesNotBlock() throws Exception {
    MockReaderIterator iterator = new MockReaderIterator(0, 10);
    CounterSet counterSet = new CounterSet();
    MockOutputReceiver receiver = new MockOutputReceiver();
    ReadOperation readOperation = ReadOperation.forTest(new MockReader(iterator), receiver, "test-",
        counterSet.getAddCounterMutator(),
        new StateSampler("test-", counterSet.getAddCounterMutator()));

    Thread thread = runReadLoopInThread(readOperation);
    iterator.offerNext(0);
    receiver.unblockProcess();
    // Read loop is blocked in next(). Do not offer another next item,
    // but check that we can still split while the read loop is blocked.
    NativeReader.DynamicSplitResultWithPosition split =
        (NativeReader.DynamicSplitResultWithPosition)
            readOperation.requestDynamicSplit(splitRequestAtIndex(5L));
    assertNotNull(split);
    assertEquals(positionAtIndex(5L), toCloudPosition(split.getAcceptedPosition()));

    for (int i = 1; i < 5; ++i) {
      iterator.offerNext(i);
      receiver.unblockProcess();
    }

    thread.join();
  }

  @Test
  public void testRaceBetweenCloseAndDynamicSplit() throws Exception {
    MockReaderIterator iterator = new MockReaderIterator(0, 10);
    CounterSet counterSet = new CounterSet();
    MockOutputReceiver receiver = new MockOutputReceiver();
    final ReadOperation readOperation = ReadOperation.forTest(
        new MockReader(iterator), receiver, "test-",
        counterSet.getAddCounterMutator(),
        new StateSampler("test-", counterSet.getAddCounterMutator()));

    // We simulate the following sequence:
    // "Reader thread" calls ReadOperation.start() and returns from it
    // "Main thread" calls requestDynamicSplit()
    // "Reader thread" calls ReadOperation.finish()
    // We use CountDownLatch as synchronization barriers to establish this sequence.
    final CountDownLatch startCompleted = new CountDownLatch(1);
    final CountDownLatch requestDynamicSplitCompleted = new CountDownLatch(1);
    Thread thread =
        new Thread() {
          @Override
          public void run() {
            try {
              readOperation.start();
              // Synchronize with main test thread to notify it that .start() has finished,
              // meaning the ReadOperation has finished reading.
              startCompleted.countDown();
              // Synchronize with main test thread to wait until requestDynamicSplit()
              // has completed.
              requestDynamicSplitCompleted.await();
              // Now finish the ReadOperation.
              readOperation.finish();
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        };
    thread.start();

    for (int i = 0; i < 10; ++i) {
      iterator.offerNext(i);
      receiver.unblockProcess();
    }
    // Synchronize with reader thread to wait until ReadOperation.start() finishes.
    startCompleted.await();
    // Check that requestDynamicSplit is safe (no-op) if the operation is done with start()
    // but not yet done with finish()
    readOperation.requestDynamicSplit(splitRequestAtIndex(5L));
    // Synchronize with reader thread to notify it that we're done calling requestDynamicSplit().
    requestDynamicSplitCompleted.countDown();

    // Let the reader thread complete (it just calls finish()).
    thread.join();

    // Check once more that requestDynamicSplit on a finished operation is also safe (no-op).
    readOperation.requestDynamicSplit(splitRequestAtIndex(5L));
  }

  private Thread runReadLoopInThread(final ReadOperation readOperation) {
    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          readOperation.start();
          readOperation.finish();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    thread.start();
    return thread;
  }

  private static class MockReaderIterator extends NativeReader.NativeReaderIterator<Integer> {
    private final OffsetRangeTracker tracker;
    private Exchanger<Integer> exchanger = new Exchanger<>();
    private int current;
    private volatile boolean isClosed;

    public MockReaderIterator(int from, int to) {
      this.tracker = new OffsetRangeTracker(from, to);
      this.current = from - 1;
    }

    @Override
    public boolean start() throws IOException {
      return advance();
    }

    @Override
    public boolean advance() throws IOException {
      if (!tracker.tryReturnRecordAt(true, current + 1)) {
        return false;
      }
      ++current;
      exchangeCurrent();
      return true;
    }

    private void exchangeCurrent() {
      try {
        current = exchanger.exchange(current);
      } catch (InterruptedException e) {
        throw new NoSuchElementException("interrupted");
      }
    }

    @Override
    public Integer getCurrent() {
      return current;
    }

    @Override
    public NativeReader.Progress getProgress() {
      Preconditions.checkState(!isClosed);
      return cloudProgressToReaderProgress(
          new ApproximateReportedProgress()
              .setPosition(new Position().setRecordIndex((long) current))
              .setFractionConsumed(tracker.getFractionConsumed()));
    }

    @Override
    public NativeReader.DynamicSplitResult requestDynamicSplit(
        NativeReader.DynamicSplitRequest splitRequest) {
      Preconditions.checkState(!isClosed);
      ApproximateSplitRequest approximateSplitRequest = splitRequestToApproximateSplitRequest(
          splitRequest);
      int index = approximateSplitRequest.getPosition().getRecordIndex().intValue();
      if (!tracker.trySplitAtPosition(index)) {
        return null;
      }
      return new NativeReader.DynamicSplitResultWithPosition(
          cloudPositionToReaderPosition(approximateSplitRequest.getPosition()));
    }

    public int offerNext(int next) {
      try {
        return exchanger.exchange(next);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void close() throws IOException {
      isClosed = true;
    }
  }

  private static class MockReader extends NativeReader<Integer> {
    private NativeReaderIterator<Integer> iterator;

    private MockReader(NativeReaderIterator<Integer> iterator) {
      this.iterator = iterator;
    }

    @Override
    public NativeReaderIterator<Integer> iterator() throws IOException {
      return iterator;
    }
  }

  /**
   * A mock {@link OutputReceiver} that blocks the read loop in {@link ReadOperation}.
   */
  private static class MockOutputReceiver extends OutputReceiver {
    private Exchanger<Object> exchanger = new Exchanger<>();

    @Override
    public void process(Object elem) throws Exception {
      exchanger.exchange(null);
    }

    public void unblockProcess() {
      try {
        exchanger.exchange(null);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }
}

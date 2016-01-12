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

import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.readerProgressToCloudProgress;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.toCloudPosition;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.toDynamicSplitRequest;

import com.google.api.services.dataflow.model.ApproximateReportedProgress;
import com.google.api.services.dataflow.model.ApproximateSplitRequest;
import com.google.api.services.dataflow.model.ConcatPosition;
import com.google.api.services.dataflow.model.Position;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.common.worker.NativeReader;
import com.google.cloud.dataflow.sdk.util.common.worker.NativeReader.NativeReaderIterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Helpers for testing {@link NativeReader} and related classes, especially
 * {@link NativeReaderIterator#getProgress} and {@link NativeReaderIterator#requestDynamicSplit}.
 */
public class ReaderTestUtils {
  public static Position positionAtIndex(@Nullable Long index) {
    return new Position().setRecordIndex(index);
  }

  public static Position positionAtByteOffset(@Nullable Long byteOffset) {
    return new Position().setByteOffset(byteOffset);
  }

  public static Position positionAtConcatPosition(
      @Nullable Integer index, @Nullable Position innerPosition) {
    return new Position().setConcatPosition(
        new ConcatPosition().setIndex(index).setPosition(innerPosition));
  }

  public static ApproximateReportedProgress approximateProgressAtPosition(
      @Nullable Position position) {
    return new ApproximateReportedProgress().setPosition(position);
  }

  public static ApproximateSplitRequest approximateSplitRequestAtPosition(
      @Nullable Position position) {
    return new ApproximateSplitRequest().setPosition(position);
  }

  public static ApproximateReportedProgress approximateProgressAtIndex(
      @Nullable Long index) {
    return approximateProgressAtPosition(positionAtIndex(index));
  }

  public static ApproximateSplitRequest approximateSplitRequestAtIndex(
      @Nullable Long index) {
    return approximateSplitRequestAtPosition(positionAtIndex(index));
  }

  public static ApproximateReportedProgress approximateProgressAtByteOffset(
      @Nullable Long byteOffset) {
    return approximateProgressAtPosition(positionAtByteOffset(byteOffset));
  }

  public static ApproximateSplitRequest approximateSplitRequestAtByteOffset(
      @Nullable Long byteOffset) {
    return approximateSplitRequestAtPosition(positionAtByteOffset(byteOffset));
  }

  public static ApproximateReportedProgress approximateProgressAtConcatPosition(
      @Nullable Integer index, @Nullable Position innerPosition) {
    return approximateProgressAtPosition(positionAtConcatPosition(index, innerPosition));
  }

  public static ApproximateSplitRequest approximateSplitRequestAtConcatPosition(
      @Nullable Integer index, @Nullable Position innerPosition) {
    return approximateSplitRequestAtPosition(positionAtConcatPosition(index, innerPosition));
  }

  public static ApproximateReportedProgress approximateProgressAtFraction(
      @Nullable Double fraction) {
    return new ApproximateReportedProgress().setFractionConsumed(fraction);
  }

  public static ApproximateSplitRequest approximateSplitRequestAtFraction(
      @Nullable Double fraction) {
    return new ApproximateSplitRequest().setFractionConsumed(fraction);
  }

  public static NativeReader.DynamicSplitRequest splitRequestAtPosition(
      @Nullable Position position) {
    return toDynamicSplitRequest(approximateSplitRequestAtPosition(position));
  }

  public static NativeReader.DynamicSplitRequest splitRequestAtIndex(@Nullable Long index) {
    return toDynamicSplitRequest(approximateSplitRequestAtIndex(index));
  }

  public static NativeReader.DynamicSplitRequest splitRequestAtByteOffset(
      @Nullable Long byteOffset) {
    return toDynamicSplitRequest(approximateSplitRequestAtByteOffset(byteOffset));
  }

  public static NativeReader.DynamicSplitRequest splitRequestAtConcatPosition(
      @Nullable Integer index, @Nullable Position innerPosition) {
    return toDynamicSplitRequest(approximateSplitRequestAtConcatPosition(index, innerPosition));
  }

  public static Position positionFromSplitResult(
      NativeReader.DynamicSplitResult dynamicSplitResult) {
    return toCloudPosition(
        ((NativeReader.DynamicSplitResultWithPosition) dynamicSplitResult).getAcceptedPosition());
  }

  public static Position positionFromProgress(NativeReader.Progress progress) {
    return readerProgressToCloudProgress(progress).getPosition();
  }

  public static NativeReader.DynamicSplitRequest splitRequestAtFraction(double fraction) {
    return toDynamicSplitRequest(approximateSplitRequestAtFraction(fraction));
  }

  /**
   * Appends all values from a collection of {@code WindowedValue} values to a collection of values.
   */
  public static <T> List<T> windowedValuesToValues(Collection<WindowedValue<T>> windowedValues) {
    List<T> res = new ArrayList<>();
    for (WindowedValue<T> windowedValue : windowedValues) {
      res.add(windowedValue.getValue());
    }
    return res;
  }

  /**
   * Creates a {@link NativeReaderIterator} from the given {@link NativeReader} and reads it
   * to the end.
   *
   * @param reader {@link NativeReader} to read from
   * @throws IOException
   */
  public static <T> List<T> readAllFromReader(NativeReader<T> reader) throws IOException {
    try (NativeReaderIterator<T> iterator = reader.iterator()) {
      return readRemainingFromReader(iterator, false);
    }
  }

  /**
   * Read elements from a {@link NativeReader.NativeReaderIterator} until either the reader is
   * exhausted, or n elements are read.
   */
  public static <T> List<T> readNItemsFromReader(
      NativeReader.NativeReaderIterator<T> reader, int n, boolean started) throws IOException {
    List<T> res = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (!((i == 0 && !started) ? reader.start() : reader.advance())) {
        break;
      }
      res.add(reader.getCurrent());
    }
    return res;
  }

  /**
   * Read elements from a {@link NativeReader.NativeReaderIterator} until either the reader is
   * exhausted, or n elements are read.
   */
  public static <T> List<T> readNItemsFromUnstartedReader(
      NativeReader.NativeReaderIterator<T> reader, int n) throws IOException {
    return readNItemsFromReader(reader, n, false);
  }

  public static <T> List<T> readRemainingFromReader(
      NativeReader.NativeReaderIterator<T> reader, boolean started) throws IOException {
    return readNItemsFromReader(reader, Integer.MAX_VALUE, started);
  }
}

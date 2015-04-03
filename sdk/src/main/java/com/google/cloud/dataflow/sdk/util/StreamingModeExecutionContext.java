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

package com.google.cloud.dataflow.sdk.util;

import static com.google.cloud.dataflow.sdk.util.StateFetcher.SideInputState;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.runners.worker.windmill.Windmill;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.values.CodedTupleTag;
import com.google.cloud.dataflow.sdk.values.CodedTupleTagMap;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TimestampedValue;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.protobuf.ByteString;

import org.joda.time.Instant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutionContext} for use in streaming mode.
 */
public class StreamingModeExecutionContext extends ExecutionContext {
  private String computation;
  private Windmill.WorkItem work;
  private StateFetcher stateFetcher;
  private Windmill.WorkItemCommitRequest.Builder outputBuilder;
  private Map<TupleTag<?>, Map<BoundedWindow, Object>> sideInputCache;

  public StreamingModeExecutionContext(String computation, StateFetcher stateFetcher) {
    this.computation = computation;
    this.stateFetcher = stateFetcher;
  }

  public void start(Windmill.WorkItem work, Windmill.WorkItemCommitRequest.Builder outputBuilder) {
    this.work = work;
    this.outputBuilder = outputBuilder;
    this.sideInputCache = new HashMap<>();
  }

  @Override
  public ExecutionContext.StepContext createStepContext(String stepName) {
    return new StepContext(stepName);
  }

  @Override
  public void setTimer(String timer, Instant timestamp) {
    long timestampMicros = TimeUnit.MILLISECONDS.toMicros(timestamp.getMillis());
    outputBuilder.addOutputTimers(
        Windmill.Timer.newBuilder()
        .setTimestamp(timestampMicros)
        .setTag(ByteString.copyFromUtf8(timer))
        .build());
  }

  @Override
  public void deleteTimer(String timer) {
    outputBuilder.addOutputTimers(
        Windmill.Timer.newBuilder().setTag(ByteString.copyFromUtf8(timer)).build());
  }

  @Override
  public <T> T getSideInput(
      PCollectionView<T> view, BoundedWindow mainInputWindow, PTuple sideInputs) {
    if (!sideInputs.has(view.getTagInternal())) {
      throw new IllegalArgumentException(
          "calling sideInput() with unknown view; " +
          "did you forget to pass the view in " +
          "ParDo.withSideInputs()?");
    }

    return fetchSideInput(view, mainInputWindow, SideInputState.KNOWN_READY);
  }

  /**
   * Fetch the given side input asynchronously and return true if it is present.
   */
  public boolean issueSideInputFetch(
      PCollectionView<?> view, BoundedWindow mainInputWindow) {
    return fetchSideInput(view, mainInputWindow, SideInputState.UNKNOWN) != null;
  }

  /**
   * Fetches the requested sideInput, and maintains a view of the cache that doesn't remove
   * items until the active work item is finished.
   */
  private <T> T fetchSideInput(
      PCollectionView<?> view, BoundedWindow mainInputWindow, SideInputState state) {
    BoundedWindow sideInputWindow = view.getWindowFnInternal().getSideInputWindow(mainInputWindow);

    Map<BoundedWindow, Object> tagCache = sideInputCache.get(view.getTagInternal());
    if (tagCache == null) {
      tagCache = new HashMap<>();
      sideInputCache.put(view.getTagInternal(), tagCache);
    }

    T sideInput = (T) tagCache.get(sideInputWindow);
    if (sideInput == null) {
      sideInput = (T) stateFetcher.fetchSideInput(view, sideInputWindow, state);
      if (sideInput != null) {
        tagCache.put(sideInputWindow, sideInput);
        return sideInput;
      } else {
        return null;
      }
    } else {
      return sideInput;
    }
  }

  @Override
  public <T, W extends BoundedWindow> void writePCollectionViewData(
      TupleTag<?> tag,
      Iterable<WindowedValue<T>> data, Coder<Iterable<WindowedValue<T>>> dataCoder,
      W window, Coder<W> windowCoder) throws IOException {
    if (getSerializedKey().size() != 0) {
      throw new IllegalStateException("writePCollectionViewData must follow a Combine.globally");
    }

    ByteString.Output dataStream = ByteString.newOutput();
    dataCoder.encode(data, dataStream, Coder.Context.OUTER);

    ByteString.Output windowStream = ByteString.newOutput();
    windowCoder.encode(window, windowStream, Coder.Context.OUTER);

    outputBuilder.addGlobalDataUpdates(
        Windmill.GlobalData.newBuilder()
        .setDataId(
            Windmill.GlobalDataId.newBuilder()
            .setTag(tag.getId())
            .setVersion(windowStream.toByteString())
            .build())
        .setData(dataStream.toByteString())
        .build());
  }

  public Iterable<Windmill.GlobalDataId> getSideInputNotifications() {
    return work.getGlobalDataIdNotificationsList();
  }

  public void setBlockingSideInputs(Iterable<Windmill.GlobalDataId> sideInputs) {
    for (Windmill.GlobalDataId id : sideInputs) {
      outputBuilder.addGlobalDataIdRequests(id);
    }
  }

  public ByteString getSerializedKey() {
    return work.getKey();
  }

  public long getWorkToken() {
    return work.getWorkToken();
  }

  public Windmill.WorkItem getWork() {
    return work;
  }

  public Windmill.WorkItemCommitRequest.Builder getOutputBuilder() {
    return outputBuilder;
  }

  public void flushState() {
    for (ExecutionContext.StepContext stepContext : getAllStepContexts()) {
      ((StepContext) stepContext).flushState();
    }
  }

  public Map<CodedTupleTag<?>, Object> lookupState(
      String prefix, List<? extends CodedTupleTag<?>> tags) throws CoderException, IOException {
    return stateFetcher.fetch(computation, getSerializedKey(), getWorkToken(), prefix, tags);
  }

  class StepContext extends ExecutionContext.StepContext {
    private final String mangledPrefix;
    private Map<CodedTupleTag<?>, KV<?, ByteString>> stateCache = new HashMap<>();
    private Map<CodedTupleTag<?>, List<KV<ByteString, Instant>>> tagListUpdates = new HashMap<>();

    public StepContext(String stepName) {
      super(stepName);
      // Mangle such that there are no partially overlapping prefixes.
      this.mangledPrefix = stepName.length() + ":" + stepName;
    }

    @Override
    public <T> void store(CodedTupleTag<T> tag, T value) throws CoderException, IOException {
      ByteString.Output stream = ByteString.newOutput();
      tag.getCoder().encode(value, stream, Coder.Context.OUTER);
      stateCache.put(tag, KV.of(value, stream.toByteString()));
    }

    @Override
    public <T> void remove(CodedTupleTag<T> tag) {
      // Write ByteString.EMPTY to indicate the value associated with the tag is removed.
      stateCache.put(tag, KV.of(null, ByteString.EMPTY));
    }

    @Override
    public CodedTupleTagMap lookup(List<? extends CodedTupleTag<?>> tags)
        throws CoderException, IOException {
      List<CodedTupleTag<?>> tagsToLookup = new ArrayList<>();
      List<CodedTupleTag<?>> residentTags = new ArrayList<>();
      for (CodedTupleTag<?> tag : tags) {
        if (stateCache.containsKey(tag)) {
          residentTags.add(tag);
        } else {
          tagsToLookup.add(tag);
        }
      }
      Map<CodedTupleTag<?>, Object> result =
          StreamingModeExecutionContext.this.lookupState(mangledPrefix, tagsToLookup);
      for (CodedTupleTag<?> tag : residentTags) {
        result.put(tag, stateCache.get(tag).getKey());
      }
      return CodedTupleTagMap.of(result);
    }

    @Override
    public <T> void writeToTagList(CodedTupleTag<T> tag, T value, Instant timestamp)
        throws IOException {
      List<KV<ByteString, Instant>> list = tagListUpdates.get(tag);
      if (list == null) {
        list = new ArrayList<>();
        tagListUpdates.put(tag, list);
      }
      ByteString.Output stream = ByteString.newOutput();
      tag.getCoder().encode(value, stream, Coder.Context.OUTER);
      list.add(KV.of(stream.toByteString(), timestamp));
    }

    @Override
    public <T> Iterable<TimestampedValue<T>> readTagList(CodedTupleTag<T> tag) throws IOException {
      return stateFetcher.fetchList(
          computation, getSerializedKey(), getWorkToken(), mangledPrefix, tag);
    }

    @Override
    public <T> void deleteTagList(CodedTupleTag<T> tag) {
      outputBuilder.addListUpdates(
          Windmill.TagList.newBuilder()
          .setTag(serializeTag(tag))
          .setEndTimestamp(Long.MAX_VALUE)
          .build());
    }

    public void flushState() {
      for (Map.Entry<CodedTupleTag<?>, KV<?, ByteString>> entry : stateCache.entrySet()) {
        CodedTupleTag<?> tag = entry.getKey();
        ByteString encodedValue = entry.getValue().getValue();
        outputBuilder.addValueUpdates(
            Windmill.TagValue.newBuilder()
            .setTag(serializeTag(tag))
            .setValue(
                Windmill.Value.newBuilder()
                .setData(encodedValue)
                .setTimestamp(Long.MAX_VALUE)
                .build())
            .build());
      }

      for (Map.Entry<CodedTupleTag<?>, List<KV<ByteString, Instant>>> entry :
               tagListUpdates.entrySet()) {
        CodedTupleTag<?> tag = entry.getKey();
        Windmill.TagList.Builder listBuilder =
            Windmill.TagList.newBuilder()
            .setTag(serializeTag(tag));
        for (KV<ByteString, Instant> item : entry.getValue()) {
          long timestampMicros = TimeUnit.MILLISECONDS.toMicros(item.getValue().getMillis());
          listBuilder.addValues(
              Windmill.Value.newBuilder()
              .setData(item.getKey())
              .setTimestamp(timestampMicros));
        }
        outputBuilder.addListUpdates(listBuilder.build());
      }

      stateCache.clear();
      tagListUpdates.clear();
    }

    private ByteString serializeTag(CodedTupleTag<?> tag) {
      return ByteString.copyFromUtf8(mangledPrefix + tag.getId());
    }
  }
}

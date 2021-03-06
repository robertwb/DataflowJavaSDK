/*******************************************************************************
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
 ******************************************************************************/

package com.google.cloud.dataflow.sdk.runners.worker;

import com.google.api.services.dataflow.model.SourceOperationRequest;
import com.google.api.services.dataflow.model.SourceOperationResponse;
import com.google.api.services.dataflow.model.SourceSplitRequest;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.runners.dataflow.CustomSources;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.worker.WorkExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * An executor for a source operation, defined by a {@code SourceOperationRequest}.
 */
public class SourceOperationExecutor extends WorkExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(SourceOperationExecutor.class);
  public static final String SPLIT_RESPONSE_TOO_LARGE_ERROR =
      "Total size of the BoundedSource objects generated by splitIntoBundles() operation is larger"
          + " than the allowable limit. For more information, please check the corresponding FAQ"
          + " entry at https://cloud.google.com/dataflow/pipelines/troubleshooting-your-pipeline";

  private final PipelineOptions options;
  private final SourceOperationRequest request;
  private SourceOperationResponse response;

  public SourceOperationExecutor(PipelineOptions options,
                                 SourceOperationRequest request,
                                 CounterSet counters) {
    super(counters);
    this.options = options;
    this.request = request;
  }

  @Override
  public void execute() throws Exception {
    LOG.debug("Executing source operation");
    SourceSplitRequest split = request.getSplit();
    if (split != null) {
      this.response = CustomSources.performSplit(split, options);
    } else {
      throw new UnsupportedOperationException("Unsupported source operation request: " + request);
    }
    LOG.debug("Source operation execution complete");
  }

  public SourceOperationResponse getResponse() {
    return response;
  }

  static boolean isSplitResponseTooLarge(SourceOperationResponse operationResponse) {
    try {
      long splitResponseSize =
          DataflowApiUtils.computeSerializedSizeBytes(operationResponse.getSplit());
      return splitResponseSize > CustomSources.DATAFLOW_SPLIT_RESPONSE_API_SIZE_BYTES;
    } catch (IOException e) {
      /* Assume that the size is not too large, so that the actual API error is exposed. */
      LOG.warn("Error determining the size of the split response.", e);
      return false;
    }
  }
}

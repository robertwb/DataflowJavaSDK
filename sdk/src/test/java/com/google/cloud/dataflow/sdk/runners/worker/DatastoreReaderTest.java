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

import static com.google.api.services.datastore.client.DatastoreHelper.makeProperty;
import static com.google.api.services.datastore.client.DatastoreHelper.makeValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.datastore.DatastoreV1.Entity;
import com.google.api.services.datastore.DatastoreV1.EntityResult;
import com.google.api.services.datastore.DatastoreV1.EntityResult.ResultType;
import com.google.api.services.datastore.DatastoreV1.Query;
import com.google.api.services.datastore.DatastoreV1.QueryResultBatch;
import com.google.api.services.datastore.DatastoreV1.QueryResultBatch.MoreResultsType;
import com.google.api.services.datastore.DatastoreV1.RunQueryRequest;
import com.google.api.services.datastore.DatastoreV1.RunQueryResponse;
import com.google.api.services.datastore.client.Datastore;
import com.google.api.services.datastore.client.DatastoreException;
import com.google.cloud.dataflow.sdk.io.DatastoreIO;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unit tests for {@code DatastoreSource}.
 */
@RunWith(JUnit4.class)
public class DatastoreReaderTest {
  private static final String TEST_KIND = "mykind";
  private static final String TEST_PROPERTY = "myproperty";
  private static final String TEST_NAMESPACE = "mynamespace";

  private static class IsValidRequestWithNamespace extends ArgumentMatcher<RunQueryRequest> {
    private final String namespace;

    public IsValidRequestWithNamespace(String namespace) {
      this.namespace = namespace;
    }
    @Override
    public boolean matches(Object o) {
      RunQueryRequest request = (RunQueryRequest) o;
      return request.hasQuery()
          && Objects.equals(request.getPartitionId().getNamespace(), namespace);
    }
  }

  private EntityResult createEntityResult(String val) {
    Entity entity =
        Entity.newBuilder().addProperty(makeProperty(TEST_PROPERTY, makeValue(val))).build();
    return EntityResult.newBuilder().setEntity(entity).build();
  }

  private Datastore buildMockDatastore() throws DatastoreException {
    Datastore datastore = mock(Datastore.class);
    RunQueryResponse.Builder firstResponseBuilder = RunQueryResponse.newBuilder();
    RunQueryResponse.Builder secondResponseBuilder = RunQueryResponse.newBuilder();
    RunQueryResponse.Builder thirdResponseBuilder = RunQueryResponse.newBuilder();
    RunQueryResponse.Builder firstNamespaceResponseBuilder = RunQueryResponse.newBuilder();
    {
      QueryResultBatch.Builder resultsBatch = QueryResultBatch.newBuilder();
      resultsBatch.addEntityResult(0, createEntityResult("val0"));
      resultsBatch.addEntityResult(1, createEntityResult("val1"));
      resultsBatch.addEntityResult(2, createEntityResult("val2"));
      resultsBatch.addEntityResult(3, createEntityResult("val3"));
      resultsBatch.addEntityResult(4, createEntityResult("val4"));
      resultsBatch.setEntityResultType(ResultType.FULL);

      resultsBatch.setMoreResults(MoreResultsType.NOT_FINISHED);

      firstResponseBuilder.setBatch(resultsBatch.build());
    }
    {
      QueryResultBatch.Builder resultsBatch = QueryResultBatch.newBuilder();
      resultsBatch.addEntityResult(0, createEntityResult("val5"));
      resultsBatch.addEntityResult(1, createEntityResult("val6"));
      resultsBatch.addEntityResult(2, createEntityResult("val7"));
      resultsBatch.addEntityResult(3, createEntityResult("val8"));
      resultsBatch.addEntityResult(4, createEntityResult("val9"));
      resultsBatch.setEntityResultType(ResultType.FULL);

      resultsBatch.setMoreResults(MoreResultsType.NOT_FINISHED);

      secondResponseBuilder.setBatch(resultsBatch.build());
    }
    {
      QueryResultBatch.Builder resultsBatch = QueryResultBatch.newBuilder();
      resultsBatch.setEntityResultType(ResultType.FULL);

      resultsBatch.setMoreResults(MoreResultsType.NO_MORE_RESULTS);

      thirdResponseBuilder.setBatch(resultsBatch.build());
    }
    {
      QueryResultBatch.Builder resultsBatch = QueryResultBatch.newBuilder();
      resultsBatch.addEntityResult(0, createEntityResult("nsval0"));
      resultsBatch.addEntityResult(1, createEntityResult("nsval1"));
      resultsBatch.addEntityResult(2, createEntityResult("nsval2"));
      resultsBatch.addEntityResult(3, createEntityResult("nsval3"));
      resultsBatch.addEntityResult(4, createEntityResult("nsval4"));
      resultsBatch.setEntityResultType(ResultType.FULL);

      resultsBatch.setMoreResults(MoreResultsType.NO_MORE_RESULTS);

      firstNamespaceResponseBuilder.setBatch(resultsBatch.build());
    }
    // Without namespace
    when(datastore.runQuery(argThat(new IsValidRequestWithNamespace(""))))
        .thenReturn(firstResponseBuilder.build())
        .thenReturn(secondResponseBuilder.build())
        .thenReturn(thirdResponseBuilder.build());

    // With namespace
    doReturn(firstNamespaceResponseBuilder.build()).when(datastore)
    .runQuery(argThat(new IsValidRequestWithNamespace(TEST_NAMESPACE)));

    return datastore;
  }


  @Test
  public void testRead() throws Exception {
    Datastore datastore = buildMockDatastore();

    Query.Builder q = Query.newBuilder();
    q.addKindBuilder().setName(TEST_KIND);
    Query query = q.build();

    List<Entity> entityResults = new ArrayList<Entity>();

    try (DatastoreIO.DatastoreReader iterator =
            new DatastoreIO.DatastoreReader(DatastoreIO.source().withQuery(query), datastore)) {
      while (iterator.advance()) {
        entityResults.add(iterator.getCurrent());
      }
    }

    assertEquals(10, entityResults.size());
    for (int i = 0; i < 10; i++) {
      assertNotNull(entityResults.get(i).getPropertyList());
      assertEquals(entityResults.get(i).getPropertyList().size(), 1);
      assertTrue(entityResults.get(i).getPropertyList().get(0).hasValue());
      assertTrue(entityResults.get(i).getPropertyList().get(0).getValue().hasStringValue());
      assertEquals(
          entityResults.get(i).getPropertyList().get(0).getValue().getStringValue(), "val" + i);
    }
  }

  @Test
  public void testReadWithNamespace() throws Exception {
    Datastore datastore = buildMockDatastore();

    Query.Builder q = Query.newBuilder();
    q.addKindBuilder().setName(TEST_KIND);
    Query query = q.build();

    List<Entity> entityResults = new ArrayList<Entity>();

    try (DatastoreIO.DatastoreReader iterator = new DatastoreIO.DatastoreReader(
        DatastoreIO.source().withQuery(query).withNamespace(TEST_NAMESPACE), datastore)) {
      while (iterator.advance()) {
        entityResults.add(iterator.getCurrent());
      }
    }

    assertEquals(5, entityResults.size());
    for (int i = 0; i < 5; i++) {
      assertNotNull(entityResults.get(i).getPropertyList());
      assertEquals(entityResults.get(i).getPropertyList().size(), 1);
      assertTrue(entityResults.get(i).getPropertyList().get(0).hasValue());
      assertTrue(entityResults.get(i).getPropertyList().get(0).getValue().hasStringValue());
      assertEquals(
          entityResults.get(i).getPropertyList().get(0).getValue().getStringValue(), "nsval" + i);
    }
  }
}

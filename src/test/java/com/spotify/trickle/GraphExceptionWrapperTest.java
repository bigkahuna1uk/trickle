/*
 * Copyright 2013-2014 Spotify AB. All rights reserved.
 *
 * The contents of this file are licensed under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.trickle;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@SuppressWarnings("ThrowableResultOfMethodCallIgnored")
public class GraphExceptionWrapperTest {

  private static final List<NodeInfo> NO_ARGS = Collections.emptyList();
  private static final List<ListenableFuture<?>> NO_VALUES = emptyList();
  GraphExceptionWrapper wrapper;
  Throwable t;
  TraverseState traverseState;
  TraverseState.FutureCallInformation currentCall;

  NodeInfo currentNodeInfo;
  List<ListenableFuture<?>> currentNodeValues;

  @Before
  public void setUp() throws Exception {
    wrapper = new GraphExceptionWrapper();

    t = new RuntimeException("the original problem");

    Map<Input<?>, Object> emptyMap = Collections.emptyMap();
    traverseState = new TraverseState(emptyMap, MoreExecutors.sameThreadExecutor(), true);

    List<? extends NodeInfo> currentNodeParameters = ImmutableList.of(
        new TestNodeInfo("arg1", Collections .<NodeInfo>emptyList()),
        new TestNodeInfo("argument 2", Collections .<NodeInfo>emptyList())
        );

    currentNodeInfo = new TestNodeInfo("the node", currentNodeParameters);
    currentNodeValues = ImmutableList.<ListenableFuture<?>>of(
        immediateFuture("value 1"),
        immediateFuture("andra värdet")
    );
    currentCall = new TraverseState.FutureCallInformation(currentNodeInfo, currentNodeValues);
  }

  @Test
  public void shouldHaveOriginalExceptionAsCause() throws Exception {
    assertThat(wrapper.wrapException(t, currentCall, traverseState).getCause(), equalTo(t));
  }

  @Test
  public void shouldIncludeCurrentNodeInMessage() throws Exception {
    String message = wrapper.wrapException(t, currentCall, traverseState).getMessage();

    assertThat(message, containsString(currentNodeInfo.name()));
  }

  @Test
  public void shouldIncludeCurrentNodeParametersInMessage() throws Exception {
    String message = wrapper.wrapException(t, currentCall, traverseState).getMessage();

    for (NodeInfo parameter : currentNodeInfo.arguments()) {
      assertThat(message, containsString(parameter.name()));
    }
  }

  @Test
  public void shouldIncludeCurrentNodeValuesInMessage() throws Exception {
    String message = wrapper.wrapException(t, currentCall, traverseState).getMessage();

    for (ListenableFuture<?> value : currentNodeValues) {
      assertThat(message, containsString(value.get().toString()));
    }
  }

  @Test
  public void shouldIncludeCompletedCallsInInfo() throws Exception {
    TestNodeInfo node1 = new TestNodeInfo("completed 1", NO_ARGS);
    TestNodeInfo node2 = new TestNodeInfo("completed 2",
                                         ImmutableList.<NodeInfo>of(
                                             new TestNodeInfo("param 1", NO_ARGS),
                                             new TestNodeInfo("param 2", NO_ARGS)
                                         ));
    traverseState.record(node1, NO_VALUES);
    traverseState.record(node2, asFutures("value 1", "value 2"));

    GraphExecutionException e =
        (GraphExecutionException) wrapper.wrapException(t, currentCall, traverseState);

    assertThat(e.getCalls().size(), equalTo(2));

    boolean found1 = false;
    boolean found2 = false;

    for (CallInfo callInfo : e.getCalls()) {
      if (callInfo.getNodeInfo().equals(node1)) found1 = true;
      if (callInfo.getNodeInfo().equals(node2)) found2 = true;
    }

    assertThat(found1, is(true));
    assertThat(found2, is(true));

    // TODO: verify individual calls?
  }

  private List<ListenableFuture<?>> asFutures(String... values) {
    return Lists.transform(Arrays.asList(values), new Function<String, ListenableFuture<?>>() {
      @Nullable
      @Override
      public ListenableFuture<?> apply(@Nullable String input) {
        return immediateFuture(input);
      }
    });
  }

  @Test
  public void shouldNotIncludeIncompleteCallsInInfo() throws Exception {
    TestNodeInfo node1 = new TestNodeInfo("completed 1", NO_ARGS);
    TestNodeInfo node2 = new TestNodeInfo("incomplete 2",
                                          ImmutableList.<NodeInfo>of(
                                              new TestNodeInfo("param 1", NO_ARGS)
                                          ));
    SettableFuture<?> future = SettableFuture.create();

    traverseState.record(node1, NO_VALUES);
    traverseState.record(node2, ImmutableList.<ListenableFuture<?>>of(future));

    GraphExecutionException e =
        (GraphExecutionException) wrapper.wrapException(t, currentCall, traverseState);

    assertThat(e.getCalls().size(), equalTo(1));

    boolean found1 = false;
    boolean found2 = false;

    for (CallInfo callInfo : e.getCalls()) {
      if (callInfo.getNodeInfo().equals(node1)) found1 = true;
      if (callInfo.getNodeInfo().equals(node2)) found2 = true;
    }

    assertThat(found1, is(true));
    assertThat(found2, is(false));
  }

  static class TestNodeInfo implements NodeInfo {
    private final String name;
    private final List<? extends NodeInfo> arguments;

    TestNodeInfo(String name, List<? extends NodeInfo> arguments) {
      this.name = name;
      this.arguments = arguments;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<? extends NodeInfo> arguments() {
      return arguments;
    }

    @Override
    public Iterable<? extends NodeInfo> predecessors() {
      return emptyList();
    }

    @Override
    public Type type() {
      return Type.NODE;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
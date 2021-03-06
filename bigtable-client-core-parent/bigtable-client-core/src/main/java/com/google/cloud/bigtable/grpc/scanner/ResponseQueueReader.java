/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.grpc.scanner;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.cloud.bigtable.grpc.BigtableDataGrpcClient;
import com.google.cloud.bigtable.metrics.BigtableClientMetrics;
import com.google.cloud.bigtable.metrics.BigtableClientMetrics.MetricLevel;
import com.google.cloud.bigtable.metrics.Timer;
import com.google.common.base.Preconditions;

import io.grpc.stub.StreamObserver;

/**
 * Manages a queue of {@link ResultQueueEntry}s of {@link FlatRow}.
 *
 * @author sduskis
 * @version $Id: $Id
 * @see BigtableDataGrpcClient#readFlatRows(com.google.bigtable.v2.ReadRowsRequest) for more
 *     information.
 */
public class ResponseQueueReader implements StreamObserver<FlatRow> {

  private static Timer firstResponseTimer;

  private synchronized static Timer getFirstResponseTimer() {
    if (firstResponseTimer == null) {
      firstResponseTimer = BigtableClientMetrics
        .timer(MetricLevel.Info, "grpc.method.ReadRows.firstResponse.latency");
    }
    return firstResponseTimer;
  }

  protected final BlockingQueue<ResultQueueEntry<FlatRow>> resultQueue;
  protected AtomicBoolean completionMarkerFound = new AtomicBoolean(false);
  private boolean lastResponseProcessed = false;
  private Long startTime;

  /**
   * <p>Constructor for ResponseQueueReader.</p>
   * @param capacityCap a int.
   */
  public ResponseQueueReader(int capacityCap) {
    this.resultQueue = new LinkedBlockingQueue<>(capacityCap);
    if (BigtableClientMetrics.isEnabled(MetricLevel.Info)) {
      startTime = System.nanoTime();
    }
  }

  /**
   * Get the next complete {@link FlatRow} object from the response queue.
   *
   * @return null if end-of-stream, otherwise a complete {@link FlatRow}.
   * @throws java.io.IOException On errors.
   */
  public synchronized FlatRow getNextMergedRow() throws IOException {
    if (lastResponseProcessed) {
      return null;
    }

    ResultQueueEntry<FlatRow> queueEntry = getNext();

    switch (queueEntry.getType()) {
    case CompletionMarker:
      lastResponseProcessed = true;
      break;
    case Data:
      if (startTime != null) {
        getFirstResponseTimer().update(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        startTime = null;
      }
      return queueEntry.getResponseOrThrow();
    case Exception:
      return queueEntry.getResponseOrThrow();
    default:
      throw new IllegalStateException("Cannot process type: " + queueEntry.getType());
    }

    Preconditions.checkState(lastResponseProcessed,
      "Should only exit merge loop with by returning a complete FlatRow or hitting end of stream.");
    return null;
  }

  /**
   * <p>getNext.</p>
   *
   * @return a {@link com.google.cloud.bigtable.grpc.scanner.ResultQueueEntry} object.
   * @throws java.io.IOException if any.
   */
  protected ResultQueueEntry<FlatRow> getNext() throws IOException {
    ResultQueueEntry<FlatRow> queueEntry;
    try {
      queueEntry = resultQueue.poll(250, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for next result", e);
    }
    if (queueEntry == null) {
      throw new ScanTimeoutException("Timeout while merging responses.");
    }

    return queueEntry;
  }

  /**
   * <p>available.</p>
   *
   * @return a int.
   */
  public int available() {
    return resultQueue.size();
  }

  /** {@inheritDoc} */
  @Override
  public void onNext(FlatRow row) {
    try {
      resultQueue.put(ResultQueueEntry.fromResponse(row));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while adding a ResultQueueEntry", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void onError(Throwable t) {
    try {
      resultQueue.put(ResultQueueEntry.<FlatRow> fromThrowable(t));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while adding a ResultQueueEntry", e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void onCompleted() {
    try {
      completionMarkerFound.set(true);
      resultQueue.put(ResultQueueEntry.<FlatRow> completionMarker());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while adding a ResultQueueEntry", e);
    }
  }
}
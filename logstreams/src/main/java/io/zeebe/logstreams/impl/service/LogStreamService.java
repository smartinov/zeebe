/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.impl.service;

import static io.zeebe.logstreams.impl.service.LogStreamServiceNames.logStorageAppenderRootService;
import static io.zeebe.logstreams.impl.service.LogStreamServiceNames.logStorageAppenderServiceName;
import static io.zeebe.logstreams.impl.service.LogStreamServiceNames.logStreamServiceName;
import static io.zeebe.logstreams.impl.service.LogStreamServiceNames.logWriteBufferServiceName;
import static io.zeebe.logstreams.impl.service.LogStreamServiceNames.logWriteBufferSubscriptionServiceName;

import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.dispatcher.DispatcherBuilder;
import io.zeebe.dispatcher.Dispatchers;
import io.zeebe.dispatcher.Subscription;
import io.zeebe.logstreams.impl.LogStorageAppender;
import io.zeebe.logstreams.impl.Loggers;
import io.zeebe.logstreams.log.BufferedLogStreamReader;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.spi.LogStorage;
import io.zeebe.servicecontainer.CompositeServiceBuilder;
import io.zeebe.servicecontainer.Injector;
import io.zeebe.servicecontainer.Service;
import io.zeebe.servicecontainer.ServiceContainer;
import io.zeebe.servicecontainer.ServiceName;
import io.zeebe.servicecontainer.ServiceStartContext;
import io.zeebe.servicecontainer.ServiceStopContext;
import io.zeebe.util.ByteValue;
import io.zeebe.util.sched.ActorCondition;
import io.zeebe.util.sched.channel.ActorConditions;
import io.zeebe.util.sched.future.ActorFuture;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.agrona.concurrent.status.Position;
import org.slf4j.Logger;

public class LogStreamService implements LogStream, Service<LogStream> {
  public static final long INVALID_ADDRESS = -1L;

  private static final Logger LOG = Loggers.LOGSTREAMS_LOGGER;
  private static final String APPENDER_SUBSCRIPTION_NAME = "appender";

  private final Injector<LogStorage> logStorageInjector = new Injector<>();
  private final ServiceContainer serviceContainer;
  private final ActorConditions onCommitPositionUpdatedConditions;
  private final String logName;
  private final int partitionId;
  private final ByteValue maxFrameLength;
  private final Position commitPosition;

  private BufferedLogStreamReader reader;
  private ServiceStartContext serviceContext;
  private LogStorage logStorage;
  private ActorFuture<Dispatcher> writeBufferFuture;
  private ActorFuture<LogStorageAppender> appenderFuture;
  private Dispatcher writeBuffer;
  private LogStorageAppender appender;

  public LogStreamService(
      final ServiceContainer serviceContainer,
      final ActorConditions onCommitPositionUpdatedConditions,
      final String logName,
      final int partitionId,
      final ByteValue maxFrameLength,
      final Position commitPosition,
      final LogStorage logStorage) {
    this.serviceContainer = serviceContainer;
    this.onCommitPositionUpdatedConditions = onCommitPositionUpdatedConditions;
    this.logName = logName;
    this.partitionId = partitionId;
    this.maxFrameLength = maxFrameLength;
    this.commitPosition = commitPosition;
    this.logStorage = logStorage;
  }

  @Override
  public void start(final ServiceStartContext startContext) {
    if (logStorage == null) {
      logStorage = logStorageInjector.getValue();
    }

    try {
      logStorage.open();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    commitPosition.setVolatile(INVALID_ADDRESS);
    serviceContext = startContext;
    this.reader = new BufferedLogStreamReader(logStorage);
    setCommitPosition(reader.seekToEnd());
  }

  @Override
  public void stop(final ServiceStopContext stopContext) {
    logStorage.close();
  }

  @Override
  public LogStream get() {
    return this;
  }

  @Override
  public int getPartitionId() {
    return partitionId;
  }

  @Override
  public String getLogName() {
    return logName;
  }

  @Override
  public void close() {
    closeAsync().join();
  }

  @Override
  public ActorFuture<Void> closeAsync() {
    return serviceContainer.removeService(logStreamServiceName(logName));
  }

  @Override
  public long getCommitPosition() {
    return commitPosition.get();
  }

  @Override
  public void setCommitPosition(final long commitPosition) {
    this.commitPosition.setOrdered(commitPosition);

    onCommitPositionUpdatedConditions.signalConsumers();
  }

  @Override
  public LogStorage getLogStorage() {
    return logStorage;
  }

  @Override
  public Dispatcher getWriteBuffer() {
    if (writeBuffer == null && writeBufferFuture != null) {
      writeBuffer = writeBufferFuture.join();
    }
    return writeBuffer;
  }

  @Override
  public LogStorageAppender getLogStorageAppender() {
    if (appender == null && appenderFuture != null) {
      appender = appenderFuture.join();
    }
    return appender;
  }

  @Override
  public ActorFuture<Void> closeAppender() {
    appenderFuture = null;
    writeBufferFuture = null;
    appender = null;
    writeBuffer = null;

    final String logName = getLogName();
    return serviceContext.removeService(logStorageAppenderRootService(logName));
  }

  @Override
  public ActorFuture<LogStorageAppender> openAppender() {
    final String logName = getLogName();
    final ServiceName<Void> logStorageAppenderRootService = logStorageAppenderRootService(logName);
    final ServiceName<Dispatcher> logWriteBufferServiceName = logWriteBufferServiceName(logName);
    final ServiceName<Subscription> appenderSubscriptionServiceName =
        logWriteBufferSubscriptionServiceName(logName, APPENDER_SUBSCRIPTION_NAME);
    final ServiceName<LogStorageAppender> logStorageAppenderServiceName =
        logStorageAppenderServiceName(logName);

    final DispatcherBuilder writeBufferBuilder =
        Dispatchers.create(logWriteBufferServiceName.getName()).maxFragmentLength(maxFrameLength);

    final CompositeServiceBuilder installOperation =
        serviceContext.createComposite(logStorageAppenderRootService);

    final LogWriteBufferService writeBufferService =
        new LogWriteBufferService(writeBufferBuilder, logStorage);
    writeBufferFuture =
        installOperation.createService(logWriteBufferServiceName, writeBufferService).install();

    final LogWriteBufferSubscriptionService subscriptionService =
        new LogWriteBufferSubscriptionService(APPENDER_SUBSCRIPTION_NAME);
    installOperation
        .createService(appenderSubscriptionServiceName, subscriptionService)
        .dependency(logWriteBufferServiceName, subscriptionService.getWritebufferInjector())
        .install();

    final LogStorageAppenderService appenderService =
        new LogStorageAppenderService(getLogStorage(), partitionId, (int) maxFrameLength.toBytes());
    appenderFuture =
        installOperation
            .createService(logStorageAppenderServiceName, appenderService)
            .dependency(
                appenderSubscriptionServiceName, appenderService.getAppenderSubscriptionInjector())
            .install();

    return installOperation.installAndReturn(logStorageAppenderServiceName);
  }

  @Override
  public void delete(final long position) {
    final boolean positionNotExist = !reader.seek(position);
    if (positionNotExist) {
      LOG.debug(
          "Tried to delete from log stream, but found no corresponding address for the given position {}.",
          position);
      return;
    }

    final long blockAddress = reader.lastReadAddress();
    LOG.debug(
        "Delete data from log stream until position '{}' (address: '{}').", position, blockAddress);

    logStorage.delete(blockAddress);
  }

  @Override
  public void registerOnCommitPositionUpdatedCondition(final ActorCondition condition) {
    onCommitPositionUpdatedConditions.registerConsumer(condition);
  }

  @Override
  public void removeOnCommitPositionUpdatedCondition(final ActorCondition condition) {
    onCommitPositionUpdatedConditions.removeConsumer(condition);
  }

  public Injector<LogStorage> getLogStorageInjector() {
    return logStorageInjector;
  }
}

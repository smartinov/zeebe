/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.util;

import static org.mockito.Mockito.spy;

import io.atomix.raft.partition.impl.RaftNamespaces;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.log.RaftLog;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.log.RaftLogReader.Mode;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.raft.storage.system.MetaStore;
import io.atomix.raft.zeebe.EntryValidator;
import io.atomix.raft.zeebe.ValidationResult;
import io.atomix.raft.zeebe.ZeebeEntry;
import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.atomix.storage.StorageLevel;
import io.atomix.storage.journal.Indexed;
import io.zeebe.logstreams.spi.LogStorage;
import io.zeebe.logstreams.storage.atomix.AtomixAppenderSupplier;
import io.zeebe.logstreams.storage.atomix.AtomixLogStorage;
import io.zeebe.logstreams.storage.atomix.AtomixReaderFactory;
import io.zeebe.logstreams.storage.atomix.ZeebeIndexAdapter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

public final class AtomixLogStorageRule extends ExternalResource
    implements AtomixReaderFactory, AtomixAppenderSupplier, ZeebeLogAppender, Supplier<LogStorage> {

  private final TemporaryFolder temporaryFolder;
  private final int partitionId;
  private final UnaryOperator<RaftStorage.Builder> builder;

  private ZeebeIndexAdapter indexMapping;
  private RaftStorage raftStorage;
  private RaftLog raftLog;
  private MetaStore metaStore;

  private AtomixLogStorage storage;
  private LongConsumer positionListener;
  private Consumer<Throwable> writeErrorListener;
  private final EntryValidator entryValidator;

  public AtomixLogStorageRule(final TemporaryFolder temporaryFolder) {
    this(temporaryFolder, 0);
  }

  public AtomixLogStorageRule(final TemporaryFolder temporaryFolder, final int partitionId) {
    this(
        temporaryFolder,
        partitionId,
        UnaryOperator.identity(),
        (a, b) -> ValidationResult.success());
  }

  public AtomixLogStorageRule(
      final TemporaryFolder temporaryFolder,
      final int partitionId,
      final EntryValidator entryValidator) {
    this(temporaryFolder, partitionId, UnaryOperator.identity(), entryValidator);
  }

  public AtomixLogStorageRule(
      final TemporaryFolder temporaryFolder,
      final int partitionId,
      final UnaryOperator<RaftStorage.Builder> builder) {
    this(temporaryFolder, partitionId, builder, (a, b) -> ValidationResult.success());
  }

  public AtomixLogStorageRule(
      final TemporaryFolder temporaryFolder,
      final int partitionId,
      final UnaryOperator<RaftStorage.Builder> builder,
      final EntryValidator entryValidator) {
    this.temporaryFolder = temporaryFolder;
    this.partitionId = partitionId;
    this.builder = builder;
    this.entryValidator = entryValidator;
  }

  @Override
  public void before() {
    open();
  }

  @Override
  public void after() {
    close();
  }

  @Override
  public void appendEntry(
      final long lowestPosition,
      final long highestPosition,
      final ByteBuffer data,
      final AppendListener listener) {
    final ZeebeEntry zbEntry =
        new ZeebeEntry(0, System.currentTimeMillis(), lowestPosition, highestPosition, data);
    final Indexed<RaftLogEntry> lastEntry = raftLog.getLastEntry();

    ZeebeEntry lastZbEntry = null;
    if (lastEntry != null && lastEntry.type() == ZeebeEntry.class) {
      lastZbEntry = ((ZeebeEntry) lastEntry.cast().entry());
    }

    final ValidationResult result = entryValidator.validateEntry(lastZbEntry, zbEntry);
    if (result.failed()) {
      final Throwable exception = new IllegalStateException(result.getErrorMessage());
      listener.onWriteError(exception);
      if (writeErrorListener != null) {
        writeErrorListener.accept(exception);
      }

      return;
    }

    final Indexed<ZeebeEntry> entry = raftLog.append(zbEntry);

    listener.onWrite(entry);
    raftLog.commit(entry.index());

    listener.onCommit(entry);
    if (positionListener != null) {
      positionListener.accept(highestPosition);
    }
  }

  public Indexed<ZeebeEntry> appendEntry(
      final long lowestPosition, final long highestPosition, final ByteBuffer data) {
    final NoopListener listener = new NoopListener();
    appendEntry(lowestPosition, highestPosition, data, listener);

    return listener.lastWrittenEntry;
  }

  @Override
  public AtomixLogStorage get() {
    return storage;
  }

  @Override
  public Optional<ZeebeLogAppender> getAppender() {
    return Optional.of(this);
  }

  public CompletableFuture<Void> compact(final long index) {
    raftLog.compact(index);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public RaftLogReader create(final long index, final Mode mode) {
    return raftLog.openReader(index, mode);
  }

  public void setPositionListener(final LongConsumer positionListener) {
    this.positionListener = positionListener;
  }

  public void setWriteErrorListener(final Consumer<Throwable> errorListener) {
    writeErrorListener = errorListener;
  }

  public void open() {
    open(builder);
  }

  public void open(final UnaryOperator<RaftStorage.Builder> builder) {
    final File directory;
    close();

    try {
      directory = temporaryFolder.newFolder(String.format("atomix-%d", partitionId));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    indexMapping = ZeebeIndexAdapter.ofDensity(1);
    raftStorage =
        builder
            .apply(buildDefaultStorage())
            .withDirectory(directory)
            .withJournalIndexFactory(() -> indexMapping)
            .build();
    raftLog = raftStorage.openLog();
    metaStore = raftStorage.openMetaStore();

    storage = spy(new AtomixLogStorage(indexMapping, this, this));
  }

  public void close() {
    Optional.ofNullable(raftLog).ifPresent(RaftLog::close);
    raftLog = null;
    Optional.ofNullable(metaStore).ifPresent(MetaStore::close);
    metaStore = null;
    Optional.ofNullable(storage).ifPresent(AtomixLogStorage::close);
    storage = null;
    Optional.ofNullable(raftStorage).ifPresent(RaftStorage::deleteLog);
    raftStorage = null;
    positionListener = null;
    writeErrorListener = null;
  }

  public int getPartitionId() {
    return partitionId;
  }

  public AtomixLogStorage getStorage() {
    return storage;
  }

  public RaftStorage getRaftStorage() {
    return raftStorage;
  }

  public RaftLog getRaftLog() {
    return raftLog;
  }

  public MetaStore getMetaStore() {
    return metaStore;
  }

  public ZeebeIndexAdapter getIndexMapping() {
    return indexMapping;
  }

  private RaftStorage.Builder buildDefaultStorage() {
    return RaftStorage.builder()
        .withFlushExplicitly(true)
        .withStorageLevel(StorageLevel.DISK)
        .withNamespace(RaftNamespaces.RAFT_STORAGE)
        .withRetainStaleSnapshots();
  }

  private final class NoopListener implements AppendListener {
    private Indexed<ZeebeEntry> lastWrittenEntry;

    @Override
    public void onWrite(final Indexed<ZeebeEntry> indexed) {
      lastWrittenEntry = indexed;
    }

    @Override
    public void onWriteError(final Throwable error) {}

    @Override
    public void onCommit(final Indexed<ZeebeEntry> indexed) {}

    @Override
    public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
  }
}

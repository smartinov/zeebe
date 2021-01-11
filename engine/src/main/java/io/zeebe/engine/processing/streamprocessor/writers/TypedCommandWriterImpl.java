/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processing.streamprocessor.writers;

import io.zeebe.protocol.impl.record.RecordMetadata;
import io.zeebe.protocol.impl.record.UnifiedRecordValue;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.intent.Intent;
import java.util.function.Consumer;

public class TypedCommandWriterImpl implements TypedCommandWriter {

  private final TypedRecordWriter recordWriter;

  public TypedCommandWriterImpl(final TypedRecordWriter recordWriter) {
    this.recordWriter = recordWriter;
  }

  @Override
  public void appendNewCommand(final Intent intent, final UnifiedRecordValue value) {
    recordWriter.appendRecord(-1, RecordType.COMMAND, intent, value);
  }

  @Override
  public void appendFollowUpCommand(
      final long key, final Intent intent, final UnifiedRecordValue value) {
    recordWriter.appendRecord(key, RecordType.COMMAND, intent, value);
  }

  @Override
  public void appendFollowUpCommand(
      final long key,
      final Intent intent,
      final UnifiedRecordValue value,
      final Consumer<RecordMetadata> metadata) {
    recordWriter.appendRecord(key, RecordType.COMMAND, intent, value, metadata);
  }

  @Override
  public void reset() {
    recordWriter.reset();
  }

  @Override
  public long flush() {
    return recordWriter.flush();
  }
}

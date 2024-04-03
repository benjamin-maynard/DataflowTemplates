/*
 * Copyright (C) 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.spanner;

import com.google.cloud.ByteArray;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Value;
import com.google.cloud.teleport.spanner.ddl.Ddl;
import com.google.cloud.teleport.spanner.ddl.IndexColumn;
import com.google.cloud.teleport.spanner.ddl.Table;
import com.google.cloud.teleport.spanner.proto.TextImportProtos.ImportManifest.TableManifest;
import com.google.cloud.teleport.spanner.proto.TextImportProtos.ImportManifest.TableManifest.Column;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Longs;
import com.google.spanner.v1.ExecuteSqlRequest.QueryOptions;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpannerReadWriteFn extends DoFn<KV<String, CSVRecord>, String> {
  private static final Logger LOG = LoggerFactory.getLogger(SpannerReadWriteFn.class);
  // Schema of the destination Spanner database.
  private final PCollectionView<Ddl> ddlView;
  private final PCollectionView<Map<String, List<TableManifest.Column>>> tableColumnsView;
  private final ValueProvider<String> dateFormat;
  private final ValueProvider<String> timestampFormat;
  private final ValueProvider<String> invalidOutputPath;
  private final TupleTag<String> errorTag;
  private final SpannerConfig spannerConfig;
  private Mutation.WriteBuilder writeBuilder = null;

  DatabaseId db;
  Spanner spanner;

  public SpannerReadWriteFn(
      PCollectionView<Ddl> ddlView,
      PCollectionView<Map<String, List<TableManifest.Column>>> tableColumnsView,
      ValueProvider<String> dateFormat,
      ValueProvider<String> timestampFormat,
      ValueProvider<String> invalidOutputPath,
      TupleTag<String> errorTag,
      SpannerConfig spannerConfig) {
    this.ddlView = ddlView;
    this.tableColumnsView = tableColumnsView;
    this.dateFormat = dateFormat;
    this.timestampFormat = timestampFormat;
    this.invalidOutputPath = invalidOutputPath;
    this.errorTag = errorTag;
    this.spannerConfig = spannerConfig;
  }

  @Setup
  public void setup() {
    try {
      String projectId = spannerConfig.getProjectId().get();
      String instanceId = spannerConfig.getInstanceId().get();
      String databaseId = spannerConfig.getDatabaseId().get();
      db = DatabaseId.of(projectId, instanceId, databaseId);
      SpannerOptions options =
          SpannerOptions.newBuilder()
              .setDefaultQueryOptions(
                  db,
                  QueryOptions.newBuilder()
                      .setOptimizerVersion("1")
                      // The list of available statistics packages can be found by querying the
                      // "INFORMATION_SCHEMA.SPANNER_STATISTICS" table.
                      .setOptimizerStatisticsPackage("latest")
                      .build())
              .build();
      spanner = options.getService();
    } catch (Exception ex) {
      LOG.error("Error while setting up Spanner db client");
      LOG.error(ex.toString());
    } // try/catch
  }

  @Teardown
  public void teardown() {
    if (spanner != null) {
      spanner.close();
      spanner = null;
    }
  }

  @ProcessElement
  public void processElement(ProcessContext c) throws IOException {
    /**
     * Input string is one line but Apache CSVParser process multiple lines, so we only take the
     * first item in the result list
     */
    KV<String, CSVRecord> kv = c.element();
    String tableName = kv.getKey();
    Ddl ddl = c.sideInput(ddlView);
    Map<String, List<Column>> tableColumnsMap = c.sideInput(tableColumnsView);
    Table table = ddl.table(tableName);
    PrimaryKeyList pkListOut = new PrimaryKeyList(table.primaryKeys());
    List<String> pkCols = pkListOut.getCols();
    CSVRecord row = kv.getValue();
    writeBuilder = Mutation.newInsertOrUpdateBuilder(table.name());
    try {
      Mutation mutation =
          parseRow(writeBuilder, row, table, tableColumnsMap.get(tableName), pkListOut);
      DatabaseClient dbClient = spanner.getDatabaseClient(db);
      dbClient
          .readWriteTransaction()
          .run(
              transaction -> {
                Struct struct =
                    transaction.readRow(
                        tableName, com.google.cloud.spanner.Key.of(pkListOut.getValues()), pkCols);

                // if row doesn't exist, execute mutation
                if (struct == null) {
                  transaction.buffer(mutation);
                  String result =
                      String.format(
                          "Wrote mutation for table %s, row %s", tableName, pkListOut.getValues());
                  c.output(result);
                } else {
                  String result =
                      String.format(
                          "Did not write mutation for table %s, row %s",
                          tableName, pkListOut.getValues());
                  c.output(result);
                }

                return null;
              });
    } catch (Exception e) {

      // Send to error tag only if output path is given, otherwise, throw exception.
      if (invalidOutputPath != null && StringUtils.isNotEmpty(invalidOutputPath.get())) {
        c.output(
            errorTag,
            StreamSupport.stream(row.spliterator(), false).collect(Collectors.joining(",")));
      } else {
        throw new RuntimeException(
            String.format("Error to parseRow. row: %s, table: %s", row, table), e);
      }
    }
  }

  protected final Mutation parseRow(
      Mutation.WriteBuilder builder,
      CSVRecord row,
      Table table,
      List<TableManifest.Column> manifestColumns,
      PrimaryKeyList pkList)
      throws IllegalArgumentException {
    // The input row's column count could be less than or equal to that of DB schema's.
    if (row.size() > table.columns().size()) {
      throw new RuntimeException(
          String.format(
              "Parsed row's column count is larger than that of the schema's. "
                  + "Row size: %d, Column size: %d, Row content: %s",
              row.size(), table.columns().size(), row.toString()));
    }

    if (manifestColumns.size() > 0 && row.size() > manifestColumns.size()) {
      throw new RuntimeException(
          String.format(
              "Parsed row's column count is larger than that of the manifest's column list. "
                  + "Row size: %d, Manifest column size: %d, Row content: %s",
              row.size(), manifestColumns.size(), row.toString()));
    }

    // Extract cell by cell and construct Mutation object
    for (int i = 0; i < row.size(); i++) {
      // If column info is provided in manifest, we use the name from manifest.
      // Otherwise, we use the column name read from DB.
      String columnName =
          manifestColumns != null && manifestColumns.size() > 0
              ? manifestColumns.get(i).getColumnName()
              : table.columns().get(i).name();
      com.google.cloud.teleport.spanner.common.Type columnType = table.column(columnName).type();
      String cellValue = row.get(i);
      boolean isNullValue = Strings.isNullOrEmpty(cellValue);
      Value columnValue = null;
      // TODO: make the tests below match Spanner's SQL literal rules wherever possible,
      // in terms of how input is accepted, and throw exceptions on invalid input.
      switch (columnType.getCode()) {
        case BOOL:
        case PG_BOOL:
          if (isNullValue) {
            columnValue = Value.bool(null);
          } else {
            Boolean bCellValue;
            if (cellValue.trim().equalsIgnoreCase("true")) {
              bCellValue = Boolean.TRUE;
            } else if (cellValue.trim().equalsIgnoreCase("false")) {
              bCellValue = Boolean.FALSE;
            } else {
              throw new IllegalArgumentException(
                  cellValue.trim() + " is not recognizable value " + "for BOOL type");
            }
            columnValue = Value.bool(Boolean.valueOf(cellValue));
            pkList.setIfKey(columnName, bCellValue);
          }
          break;
        case INT64:
        case PG_INT8:
          columnValue =
              isNullValue ? Value.int64(null) : Value.int64(Long.valueOf(cellValue.trim()));
          pkList.setIfKey(columnName, isNullValue ? null : Long.valueOf(cellValue.trim()));
          break;
        case FLOAT64:
        case PG_FLOAT8:
          columnValue =
              isNullValue ? Value.float64(null) : Value.float64(Double.valueOf(cellValue.trim()));
          pkList.setIfKey(columnName, isNullValue ? null : Double.valueOf(cellValue.trim()));
          break;
        case STRING:
        case PG_VARCHAR:
        case PG_TEXT:
          columnValue = Value.string(cellValue);
          pkList.setIfKey(columnName, cellValue);
          break;
        case DATE:
        case PG_DATE:
          if (isNullValue) {
            columnValue = Value.date(null);
          } else {
            LocalDate dt =
                LocalDate.parse(
                    cellValue.trim(),
                    DateTimeFormatter.ofPattern(
                        dateFormat.get() == null
                            ? "yyyy-M[M]-d[d][' 00:00:00']"
                            : dateFormat.get()));
            columnValue =
                Value.date(
                    com.google.cloud.Date.fromYearMonthDay(
                        dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth()));
            pkList.setIfKey(
                columnName,
                com.google.cloud.Date.fromYearMonthDay(
                    dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth()));
          }
          break;
        case TIMESTAMP:
        case PG_TIMESTAMPTZ:
        case PG_SPANNER_COMMIT_TIMESTAMP:
          if (isNullValue) {
            columnValue = Value.timestamp(null);
          } else {
            // Timestamp is either a long integer representing Unix epoch time or a string, which
            // will be parsed using the pattern corresponding to the timestampFormat flag.
            Long microseconds = Longs.tryParse(cellValue);
            if (microseconds != null) {
              columnValue =
                  Value.timestamp(com.google.cloud.Timestamp.ofTimeMicroseconds(microseconds));
              pkList.setIfKey(
                  columnName, com.google.cloud.Timestamp.ofTimeMicroseconds(microseconds));
            } else {
              DateTimeFormatter formatter =
                  timestampFormat.get() == null
                      ? DateTimeFormatter.ISO_INSTANT
                      : DateTimeFormatter.ofPattern(timestampFormat.get());
              TemporalAccessor temporalAccessor = formatter.parse(cellValue.trim());

              Instant ts;
              try {
                ts = Instant.from(temporalAccessor);
              } catch (DateTimeException e) {
                // Date format may not be converted because it lacks timezone, retry with UTC
                LocalDateTime localDateTime = LocalDateTime.from(temporalAccessor);
                ZonedDateTime zonedDateTime = ZonedDateTime.of(localDateTime, ZoneOffset.UTC);
                ts = Instant.from(zonedDateTime);
              }

              columnValue =
                  Value.timestamp(
                      com.google.cloud.Timestamp.ofTimeSecondsAndNanos(
                          ts.getEpochSecond(), ts.getNano()));
              pkList.setIfKey(
                  columnName,
                  com.google.cloud.Timestamp.ofTimeSecondsAndNanos(
                      ts.getEpochSecond(), ts.getNano()));
            }
          }
          break;
        case NUMERIC:
        case JSON:
        case PG_JSONB:
          columnValue = isNullValue ? Value.string(null) : Value.string(cellValue.trim());
          pkList.setIfKey(columnName, isNullValue ? null : cellValue.trim());
          break;
        case PG_NUMERIC:
          columnValue = isNullValue ? Value.pgNumeric(null) : Value.pgNumeric(cellValue.trim());
          pkList.setIfKey(columnName, isNullValue ? null : cellValue.trim());
          break;
        case BYTES:
        case PG_BYTEA:
          columnValue =
              isNullValue ? Value.bytes(null) : Value.bytes(ByteArray.fromBase64(cellValue.trim()));
          pkList.setIfKey(columnName, isNullValue ? null : ByteArray.fromBase64(cellValue.trim()));
          break;
        default:
          throw new IllegalArgumentException(
              "Unrecognized column data type: " + columnType.getCode());
      }

      builder.set(columnName).to(columnValue);
    }

    return builder.build();
  }

  private static class PrimaryKeyWrapper {
    private IndexColumn indexColumn;
    private Object value;

    private PrimaryKeyWrapper(IndexColumn indexCol) {
      this.indexColumn = indexCol;
    }

    public IndexColumn getIndexColumn() {
      return indexColumn;
    }

    public void setIndexColumn(IndexColumn indexColumn) {
      this.indexColumn = indexColumn;
    }

    public Object getValue() {
      return value;
    }

    public void setValue(Object value) {
      this.value = value;
    }

    public static List<PrimaryKeyWrapper> getPrimaryKeyWrappers(
        ImmutableList<IndexColumn> indexCols) {
      ArrayList<PrimaryKeyWrapper> retVal = new ArrayList<>();

      for (int i = 0; i < indexCols.size(); i++) {
        retVal.add(new PrimaryKeyWrapper(indexCols.get(i)));
      }

      return retVal;
    }
  }

  private static class PrimaryKeyList {
    List<PrimaryKeyWrapper> primaryKeyWrappers;

    public PrimaryKeyList(ImmutableList<IndexColumn> indexCols) {
      primaryKeyWrappers = new ArrayList<>();

      for (int i = 0; i < indexCols.size(); i++) {
        primaryKeyWrappers.add(new PrimaryKeyWrapper(indexCols.get(i)));
      }
    }

    public void setIfKey(String columnName, Object value) {
      for (int i = 0; i < primaryKeyWrappers.size(); i++) {
        PrimaryKeyWrapper pk = primaryKeyWrappers.get(i);
        if (pk.getIndexColumn().name().equals(columnName)) {
          pk.setValue(value);
        } // if
      } // for
    }

    public Object[] getValues() {
      ArrayList<Object> vals = new ArrayList<>();

      for (int i = 0; i < primaryKeyWrappers.size(); i++) {
        vals.add(primaryKeyWrappers.get(i).getValue());
      }

      return vals.toArray();
    }

    public List<String> getCols() {
      List<String> pkCols =
          primaryKeyWrappers.stream().map(ic -> ic.indexColumn.name()).collect(Collectors.toList());

      return pkCols;
    }
  }
} // class SpannerReadWriteFn

/*
 * Copyright (c) 2011-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.jdbcclient.impl.actions;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.jdbc.impl.actions.AbstractJDBCAction;
import io.vertx.ext.jdbc.impl.actions.JDBCStatementHelper;
import io.vertx.ext.sql.SQLOptions;
import io.vertx.jdbcclient.impl.JDBCRow;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import io.vertx.sqlclient.impl.RowDesc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.*;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collector;

/**
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 */
public abstract class JDBCQueryAction<C, R> extends AbstractJDBCAction<JDBCResponse<R>> {

  private final Collector<Row, C, R> collector;

  public JDBCQueryAction(JDBCStatementHelper helper, SQLOptions options, Collector<Row, C, R> collector) {
    super(helper, options);
    this.collector = collector;
  }

  protected JDBCResponse<R> decode(Statement statement, boolean returnedResultSet, boolean returnedKeys, List<Integer> out) throws SQLException {

    final JDBCResponse<R> response = new JDBCResponse<>(statement.getUpdateCount());

    if (returnedResultSet) {
      // normal return only
      while (returnedResultSet) {
        try (ResultSet rs = statement.getResultSet()) {
          decodeResultSet(rs, response);
        }
        if (returnedKeys) {
          decodeReturnedKeys(statement, response);
        }
        returnedResultSet = statement.getMoreResults();
      }
    } else {
      collector.accumulator();
      // first rowset includes the output results
      C container = collector.supplier().get();

      response.empty(collector.finisher().apply(container));
      if (returnedKeys) {
        decodeReturnedKeys(statement, response);
      }
    }

    if (out.size() > 0) {
      decodeOutput((CallableStatement) statement, out, response);
    }

    return response;
  }

  protected JDBCResponse<R> decode(Statement statement, int[] returnedBatchResult, boolean returnedKeys) throws SQLException {
    final JDBCResponse<R> response = new JDBCResponse<>(returnedBatchResult.length);

    BiConsumer<C, Row> accumulator = collector.accumulator();

    RowDesc desc = new RowDesc(Collections.emptyList());
    C container = collector.supplier().get();
    for (int result : returnedBatchResult) {
      Row row = new JDBCRow(desc);
      row.addValue(result);
      accumulator.accept(container, row);
    }

    response
      .push(collector.finisher().apply(container), desc, returnedBatchResult.length);

    if (returnedBatchResult.length != 0) {
      // no queries were executed
      if (returnedKeys) {
        decodeReturnedKeys(statement, response);
      }
    }

    return response;
  }

  private void decodeResultSet(ResultSet rs, JDBCResponse<R> response) throws SQLException {
    BiConsumer<C, Row> accumulator = collector.accumulator();

    List<String> columnNames = new ArrayList<>();
    RowDesc desc = new RowDesc(columnNames);
    C container = collector.supplier().get();
    int size = 0;
    ResultSetMetaData metaData = rs.getMetaData();
    int cols = metaData.getColumnCount();
    for (int i = 1; i <= cols; i++) {
      columnNames.add(metaData.getColumnLabel(i));
    }
    while (rs.next()) {
      size++;
      Row row = new JDBCRow(desc);
      for (int i = 1; i <= cols; i++) {
        Object res = convertSqlValue(rs, i);
        row.addValue(res);
      }
      accumulator.accept(container, row);
    }

    response
      .push(collector.finisher().apply(container), desc, size);
  }

  private R decodeRawResultSet(ResultSet rs) throws SQLException {
    BiConsumer<C, Row> accumulator = collector.accumulator();

    List<String> columnNames = new ArrayList<>();
    RowDesc desc = new RowDesc(columnNames);
    C container = collector.supplier().get();

    ResultSetMetaData metaData = rs.getMetaData();
    int cols = metaData.getColumnCount();
    for (int i = 1; i <= cols; i++) {
      columnNames.add(metaData.getColumnLabel(i));
    }
    while (rs.next()) {
      Row row = new JDBCRow(desc);
      for (int i = 1; i <= cols; i++) {
        Object res = convertSqlValue(rs, i);
        row.addValue(res);
      }
      accumulator.accept(container, row);
    }

    return collector.finisher().apply(container);
  }

  private void decodeOutput(CallableStatement cs, List<Integer> out, JDBCResponse<R> output) throws SQLException {
    BiConsumer<C, Row> accumulator = collector.accumulator();

    // first rowset includes the output results
    C container = collector.supplier().get();

    // the result is unlabeled
    Row row = new JDBCRow(new RowDesc(Collections.emptyList()));
    for (Integer idx : out) {
      final Object o = cs.getObject(idx);
      if (o instanceof ResultSet) {
        row.addValue(decodeRawResultSet((ResultSet) o));
      } else {
        Object res = convertSqlValue(o);
        row.addValue(res);
      }
    }

    accumulator.accept(container, row);

    R result = collector.finisher().apply(container);

    output.outputs(result, null, 1);
  }

  private void decodeReturnedKeys(Statement statement, JDBCResponse<R> response) throws SQLException {
    Row keys = null;

    ResultSet keysRS = statement.getGeneratedKeys();

    if (keysRS != null) {
      if (keysRS.next()) {
        // only try to access metadata if there are rows
        ResultSetMetaData metaData = keysRS.getMetaData();
        if (metaData != null) {
          int cols = metaData.getColumnCount();
          if (cols > 0) {
            List<String> keysColumnNames = new ArrayList<>();
            RowDesc keysDesc = new RowDesc(keysColumnNames);
            for (int i = 1; i <= cols; i++) {
              keysColumnNames.add(metaData.getColumnLabel(i));
            }

            keys = new JDBCRow(keysDesc);
            for (int i = 1; i <= cols; i++) {
              Object res = convertSqlValue(keysRS, i);
              keys.addValue(res);
            }
          }
          response.returnedKeys(keys);
        }
      }
    }
  }

  private static Object convertSqlValue(Object o) throws SQLException {
    try {
      return convertSqlValue(null, -1, o);
    } catch (Throwable t) {
      t.printStackTrace();
      return null;
    }
  }

  private static Object convertSqlValue(ResultSet rs, int pos) throws SQLException {
    try {
      return convertSqlValue(rs, pos, rs.getObject(pos));
    } catch (Throwable t) {
      t.printStackTrace();
      return null;
    }
  }

  private static Object convertSqlValue(ResultSet rs, int pos, Object value) throws SQLException {
    if (value == null) {
      return null;
    }

    // valid json types are just returned as is
    if (value instanceof Boolean || value instanceof String || value instanceof byte[]) {
      return value;
    }

    // numeric values
    if (value instanceof Number) {
      if (value instanceof BigDecimal) {
        BigDecimal d = (BigDecimal) value;
        if (d.scale() == 0) {
          return ((BigDecimal) value).toBigInteger();
        } else {
          // we might loose precision here
          return ((BigDecimal) value).doubleValue();
        }
      }

      return value;
    }

    // JDBC temporal values

    if (value instanceof Time) {
      return ((Time) value).toLocalTime();
    }

    if (value instanceof Date) {
      return ((Date) value).toLocalDate();
    }

    if (value instanceof Timestamp) {
      return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }

    // large objects
    if (value instanceof Clob) {
      if (rs != null) {
        return rs.getString(pos);
      }

      Clob c = (Clob) value;
      return getString(c);
    }

    if (value instanceof Blob) {
      if (rs != null) {
        return Buffer.buffer(rs.getBytes(pos));
      }

      Blob b = (Blob) value;
      return getBinary(b);
    }

    // arrays
    if (value instanceof Array) {
      Array a = (Array) value;
      try {
        Object arr = a.getArray();
        if (arr != null) {
          int len = java.lang.reflect.Array.getLength(arr);
          Object[] castedArray = new Object[len];
          for (int i = 0; i < len; i++) {
            castedArray[i] = convertSqlValue(java.lang.reflect.Array.get(arr, i));
          }
          return castedArray;
        }
      } finally {
        a.free();
      }
    }

    // RowId
    if (value instanceof RowId) {
      return ((RowId) value).getBytes();
    }

    // Struct
    if (value instanceof Struct) {
      return Tuple.of(((Struct) value).getAttributes());
    }

    // fallback to String
    return value.toString();
  }

  boolean returnAutoGeneratedKeys(Connection conn) {
    boolean autoGeneratedKeys = options == null || options.isAutoGeneratedKeys();
    boolean autoGeneratedIndexes = options != null && options.getAutoGeneratedKeysIndexes() != null && options.getAutoGeneratedKeysIndexes().size() > 0;
    // even though the user wants it, the DBMS may not support it
    if (autoGeneratedKeys || autoGeneratedIndexes) {
      try {
        DatabaseMetaData dbmd = conn.getMetaData();
        if (dbmd != null) {
          return dbmd.supportsGetGeneratedKeys();
        }
      } catch (SQLException e) {
        // ignore...
      }
    }
    return false;
  }

  private static String getString(Clob data) throws SQLException {
    if (data == null) {
      return null;
    }

    if (data.length() == 0L) {
      return "";
    }

    StringBuilder buffer = new StringBuilder();
    char[] buf = new char[1024];
    try (Reader in = data.getCharacterStream()) {
      int l;
      while ((l = in.read(buf)) > -1) {
        buffer.append(buf, 0, l);
      }
    } catch (IOException ioe) {
      throw new SQLException("Unable to read character stream from Clob.", ioe);
    }
    return buffer.toString();
  }

  private static Buffer getBinary(Blob data) throws SQLException {
    if (data == null) {
      return null;
    }

    if (data.length() == 0L) {
      return Buffer.buffer(0);
    }

    Buffer buffer = Buffer.buffer(1024);
    byte[] buf = new byte[1024];
    try (InputStream in = data.getBinaryStream()) {
      int l;
      while ((l = in.read(buf)) > -1) {
        buffer.appendBytes(buf, 0, l);
      }
    } catch (IOException ioe) {
      throw new SQLException("Unable to read binary stream from Blob.", ioe);
    }
    return buffer;
  }
}

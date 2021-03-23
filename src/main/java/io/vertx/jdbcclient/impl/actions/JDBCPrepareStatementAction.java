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

import io.vertx.core.json.JsonArray;
import io.vertx.ext.jdbc.impl.actions.AbstractJDBCAction;
import io.vertx.ext.jdbc.impl.actions.JDBCStatementHelper;
import io.vertx.ext.sql.SQLOptions;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 */
public class JDBCPrepareStatementAction extends AbstractJDBCAction<io.vertx.sqlclient.impl.PreparedStatement> {

  private final String sql;

  public JDBCPrepareStatementAction(JDBCStatementHelper helper, SQLOptions options, String sql) {
    super(helper, options);
    this.sql = sql;
  }

  @Override
  public io.vertx.sqlclient.impl.PreparedStatement execute(Connection conn) throws SQLException {

    boolean autoGeneratedKeys = options == null || options.isAutoGeneratedKeys();
    boolean autoGeneratedIndexes = options != null && options.getAutoGeneratedKeysIndexes() != null;

    final java.sql.PreparedStatement ps;

    if (autoGeneratedKeys && !autoGeneratedIndexes) {
      ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    } else if (autoGeneratedIndexes) {
      // convert json array to int or string array
      JsonArray indexes = options.getAutoGeneratedKeysIndexes();
      try {
        if (indexes.getValue(0) instanceof Number) {
          int[] keys = new int[indexes.size()];
          for (int i = 0; i < keys.length; i++) {
            keys[i] = indexes.getInteger(i);
          }
          ps = conn.prepareStatement(sql, keys);
        } else if (indexes.getValue(0) instanceof String) {
          String[] keys = new String[indexes.size()];
          for (int i = 0; i < keys.length; i++) {
            keys[i] = indexes.getString(i);
          }
          ps = conn.prepareStatement(sql, keys);
        } else {
          throw new SQLException("Invalid type of index, only [int, String] allowed");
        }
      } catch (RuntimeException e) {
        // any exception due to type conversion
        throw new SQLException(e);
      }
    } else {
      ps = conn.prepareStatement(sql);
    }

    // apply statement options
    applyStatementOptions(ps);

    return new JDBCPreparedStatement(sql, ps);
  }
}
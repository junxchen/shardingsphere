/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingjdbc.jdbc.core.statement;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.shardingsphere.core.constant.properties.ShardingPropertiesConstant;
import org.apache.shardingsphere.core.optimize.OptimizeEngineFactory;
import org.apache.shardingsphere.core.optimize.common.OptimizedStatement;
import org.apache.shardingsphere.core.parse.sql.statement.SQLStatement;
import org.apache.shardingsphere.core.rewrite.SQLRewriteEngine;
import org.apache.shardingsphere.core.route.SQLLogger;
import org.apache.shardingsphere.core.route.SQLUnit;
import org.apache.shardingsphere.shardingjdbc.jdbc.adapter.AbstractShardingPreparedStatementAdapter;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.connection.EncryptConnection;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.resultset.EncryptResultSet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedList;

/**
 * Encrypt prepared statement.
 *
 * @author panjuan
 */
public final class EncryptPreparedStatement extends AbstractShardingPreparedStatementAdapter {
    
    private final String sql;
    
    private final EncryptPreparedStatementGenerator preparedStatementGenerator;
    
    private final Collection<SQLUnit> sqlUnits = new LinkedList<>();
    
    private PreparedStatement preparedStatement;
    
    private EncryptResultSet resultSet;
    
    @SneakyThrows
    public EncryptPreparedStatement(final EncryptConnection connection, final String sql) {
        this.sql = sql;
        preparedStatementGenerator = new EncryptPreparedStatementGenerator(connection);
    }
    
    @SneakyThrows
    public EncryptPreparedStatement(final EncryptConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency) {
        this.sql = sql;
        preparedStatementGenerator = new EncryptPreparedStatementGenerator(connection, resultSetType, resultSetConcurrency);
    }
    
    @SneakyThrows
    public EncryptPreparedStatement(final EncryptConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) {
        this.sql = sql;
        preparedStatementGenerator = new EncryptPreparedStatementGenerator(connection, resultSetType, resultSetConcurrency, resultSetHoldability);
    }
    
    @SneakyThrows
    public EncryptPreparedStatement(final EncryptConnection connection, final String sql, final int autoGeneratedKeys) {
        this.sql = sql;
        preparedStatementGenerator = new EncryptPreparedStatementGenerator(connection, autoGeneratedKeys);
    }
    
    @SneakyThrows
    public EncryptPreparedStatement(final EncryptConnection connection, final String sql, final int[] columnIndexes) {
        this.sql = sql;
        preparedStatementGenerator = new EncryptPreparedStatementGenerator(connection, columnIndexes);
    }
    
    @SneakyThrows
    public EncryptPreparedStatement(final EncryptConnection connection, final String sql, final String[] columnNames) {
        this.sql = sql;
        preparedStatementGenerator = new EncryptPreparedStatementGenerator(connection, columnNames);
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        try {
            SQLUnit sqlUnit = getSQLUnit(sql);
            preparedStatement = preparedStatementGenerator.createPreparedStatement(sqlUnit.getSql());
            replaySetParameter(preparedStatement, sqlUnit.getParameters());
            this.resultSet = new EncryptResultSet(this, preparedStatement.executeQuery(), preparedStatementGenerator.connection.getEncryptRule());
            return resultSet;
        } finally {
            clearParameters();
        }
    }
    
    @Override
    public ResultSet getResultSet() {
        return resultSet;
    }
    
    @Override
    public int executeUpdate() throws SQLException {
        try {
            SQLUnit sqlUnit = getSQLUnit(sql);
            preparedStatement = preparedStatementGenerator.createPreparedStatement(sqlUnit.getSql());
            replaySetParameter(preparedStatement, sqlUnit.getParameters());
            return preparedStatement.executeUpdate();
        } finally {
            clearParameters();
        }
    }
    
    @Override
    public boolean execute() throws SQLException {
        try {
            SQLUnit sqlUnit = getSQLUnit(sql);
            preparedStatement = preparedStatementGenerator.createPreparedStatement(sqlUnit.getSql());
            replaySetParameter(preparedStatement, sqlUnit.getParameters());
            boolean result = preparedStatement.execute();
            this.resultSet = createEncryptResultSet(preparedStatement);
            return result;
        } finally {
            clearParameters();
        }
    }
    
    private EncryptResultSet createEncryptResultSet(final PreparedStatement preparedStatement) throws SQLException {
        return null == preparedStatement.getResultSet() ? null : new EncryptResultSet(this, preparedStatement.getResultSet(), preparedStatementGenerator.connection.getEncryptRule());
    }
    
    @Override
    public void addBatch() {
        sqlUnits.add(getSQLUnit(sql));
        clearParameters();
    }
    
    private SQLUnit getSQLUnit(final String sql) {
        EncryptConnection connection = preparedStatementGenerator.connection;
        SQLStatement sqlStatement = connection.getParseEngine().parse(sql, true);
        OptimizedStatement optimizedStatement = OptimizeEngineFactory.newInstance(connection.getEncryptRule(), connection.getShardingTableMetaData(), sqlStatement, getParameters()).optimize();
        SQLRewriteEngine encryptSQLRewriteEngine = new SQLRewriteEngine(connection.getEncryptRule(), optimizedStatement, getParameters());
        SQLUnit result = encryptSQLRewriteEngine.generateSQL();
        boolean showSQL = connection.getShardingProperties().<Boolean>getValue(ShardingPropertiesConstant.SQL_SHOW);
        if (showSQL) {
            SQLLogger.logSQL(result.getSql());
        }
        return result;
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        try {
            preparedStatement = preparedStatementGenerator.createPreparedStatement(sqlUnits.iterator().next().getSql());
            replayBatchPreparedStatement();
            return preparedStatement.executeBatch();
        } finally {
            clearBatch();
        }
    }
    
    private void replayBatchPreparedStatement() throws SQLException {
        for (SQLUnit each : sqlUnits) {
            replaySetParameter(preparedStatement, each.getParameters());
            preparedStatement.addBatch();
        }
    }
    
    @Override
    public void clearBatch() throws SQLException {
        preparedStatement.clearBatch();
        sqlUnits.clear();
        clearParameters();
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return preparedStatement.getGeneratedKeys();
    }
    
    @Override
    public Connection getConnection() {
        return preparedStatementGenerator.connection;
    }
    
    @Override
    public int getResultSetConcurrency() {
        return preparedStatementGenerator.resultSetConcurrency;
    }
    
    @Override
    public int getResultSetType() {
        return preparedStatementGenerator.resultSetType;
    }
    
    @Override
    public int getResultSetHoldability() {
        return preparedStatementGenerator.resultSetHoldability;
    }
    
    @Override
    protected boolean isAccumulate() {
        return false;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    protected Collection<? extends Statement> getRoutedStatements() {
        Collection<Statement> result = new LinkedList();
        result.add(preparedStatement);
        return result;
    }
    
    @RequiredArgsConstructor
    private final class EncryptPreparedStatementGenerator {
        
        private final EncryptConnection connection;
        
        private final int resultSetType;
        
        private final int resultSetConcurrency;
        
        private final int resultSetHoldability;
        
        private final int autoGeneratedKeys;
        
        private final int[] columnIndexes;
        
        private final String[] columnNames;
        
        private EncryptPreparedStatementGenerator(final EncryptConnection connection) {
            this(connection, -1, -1, -1, -1, null, null);
        }
        
        private EncryptPreparedStatementGenerator(final EncryptConnection connection, final int resultSetType, final int resultSetConcurrency) {
            this(connection, resultSetType, resultSetConcurrency, -1, -1, null, null);
        }
        
        private EncryptPreparedStatementGenerator(final EncryptConnection connection, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) {
            this(connection, resultSetType, resultSetConcurrency, resultSetHoldability, -1, null, null);
        }
        
        private EncryptPreparedStatementGenerator(final EncryptConnection connection, final int autoGeneratedKeys) {
            this(connection, -1, -1, -1, autoGeneratedKeys, null, null);
        }
        
        private EncryptPreparedStatementGenerator(final EncryptConnection connection, final int[] columnIndexes) {
            this(connection, -1, -1, -1, -1, columnIndexes, null);
        }
        
        private EncryptPreparedStatementGenerator(final EncryptConnection connection, final String[] columnNames) {
            this(connection, -1, -1, -1, -1, null, columnNames);
        }
        
        private PreparedStatement createPreparedStatement(final String sql) throws SQLException {
            if (-1 != resultSetType && -1 != resultSetConcurrency && -1 != resultSetHoldability) {
                return connection.getConnection().prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
            }
            if (-1 != resultSetType && -1 != resultSetConcurrency) {
                return connection.getConnection().prepareStatement(sql, resultSetType, resultSetConcurrency);
            }
            if (-1 != autoGeneratedKeys) {
                return connection.getConnection().prepareStatement(sql, autoGeneratedKeys);
            }
            if (null != columnIndexes) {
                return connection.getConnection().prepareStatement(sql, columnIndexes);
            }
            if (null != columnNames) {
                return connection.getConnection().prepareStatement(sql, columnNames);
            }
            return connection.getConnection().prepareStatement(sql);
        }
    }
}

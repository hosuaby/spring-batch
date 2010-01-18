/*
 * Copyright 2006-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.batch.item.database;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ReaderNotOpenException;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.SQLWarningException;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.metadata.CallMetaDataContext;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.object.SqlCall;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * <p>
 * Item reader that executes a stored procedure and then reads the returned cursor and continually 
 * retrieves the next row in the ResultSet. 
 * </p>
 * 
 * <p>
 * By default the procedure will be executed using a separate connection. The ResultSet for the cursor
 * is held open regardless of commits or roll backs in a surrounding transaction. Clients of this 
 * reader are responsible for buffering the items in the case that they need to be re-presented on a
 * rollback. This buffering is handled by the step implementations provided and is only a concern for
 * anyone writing their own step implementations.
 * </p>
 * 
 * <p>
 * The callable statement used to open the cursor is created with the 'READ_ONLY' option as well as with the 
 * 'TYPE_FORWARD_ONLY' option. By default the cursor will be opened using a separate connection which means 
 * that it will not participate in any transactions created as part of the step processing.
 * </p>
 *  
 * <p>
 * There is an option ({@link #setUseSharedExtendedConnection(boolean)} that will share the connection 
 * used for the cursor with the rest of the step processing. If you set this flag to <code>true</code> 
 * then you must wrap the DataSource in a {@link ExtendedConnectionDataSourceProxy} to prevent the 
 * connection from being closed and released after each commit performed as part of the step processing. 
 * You must also use a JDBC driver supporting JDBC 3.0 or later since the cursor will be opened with the 
 * additional option of 'HOLD_CUSORS_OVER_COMMIT' enabled. 
 * </p>
 * 
 * <p>
 * Each call to {@link #read()} will call the provided RowMapper, passing in the
 * ResultSet. There is currently no wrapping of the ResultSet to suppress calls
 * to next(). However, if the RowMapper (mistakenly) increments the current row,
 * the next call to read will verify that the current row is at the expected
 * position and throw a DataAccessException if it is not. The reason for such strictness on the
 * ResultSet is due to the need to maintain control for transactions and
 * restartability. This ensures that each call to {@link #read()} returns the
 * ResultSet at the correct row, regardless of rollbacks or restarts.
 * </p>
 * 
 * <p>
 * {@link ExecutionContext}: The current row is returned as restart data, and
 * when restored from that same data, the cursor is opened and the current row
 * set to the value within the restart data. See
 * {@link #setDriverSupportsAbsolute(boolean)} for improving restart
 * performance.
 * </p>
 * 
 * <p>
 * Calling close on this {@link ItemStream} will cause all resources it is
 * currently using to be freed. (Connection, ResultSet, etc). It is then illegal
 * to call {@link #read()} again until it has been re-opened.
 * </p>
 * 
 * <p>
 * Known limitation: when used with Derby
 * {@link #setVerifyCursorPosition(boolean)} needs to be <code>false</code>
 * because {@link ResultSet#getRow()} call used for cursor position verification
 * is not available for 'TYPE_FORWARD_ONLY' result sets.
 * </p>
 * 
 * <p>
 * This class is modeled after the similar <code>JdbcCursorItemReader</code> class. 
 * </p>
 * 
 * @author Lucas Ward
 * @author Peter Zozom
 * @author Robert Kasanicky
 * @author Thomas Risberg
 */
public class StoredProcedureItemReader<T> extends AbstractItemCountingItemStreamItemReader<T> implements InitializingBean {

	private static Log log = LogFactory.getLog(StoredProcedureItemReader.class);

	public static final int VALUE_NOT_SET = -1;

	private Connection con;

	private CallableStatement callableStatement;

	private PreparedStatementSetter preparedStatementSetter;

	protected ResultSet rs;

	private DataSource dataSource;

	private String procedureName;

	private int fetchSize = VALUE_NOT_SET;

	private int maxRows = VALUE_NOT_SET;

	private int queryTimeout = VALUE_NOT_SET;

	private boolean ignoreWarnings = true;

	private boolean verifyCursorPosition = true;

	private SQLExceptionTranslator exceptionTranslator;

	private RowMapper rowMapper;

	private boolean initialized = false;

	private boolean driverSupportsAbsolute = false;

	private boolean useSharedExtendedConnection = false;
	
	private SqlParameter[] parameters = new SqlParameter[0];
	
	private boolean function = false;
	
	private int refCursorPosition = 0;

	public StoredProcedureItemReader() {
		setName(ClassUtils.getShortName(StoredProcedureItemReader.class));
	}

	/**
	 * Assert that mandatory properties are set.
	 * 
	 * @throws IllegalArgumentException if either data source or sql properties
	 * not set.
	 */
	public void afterPropertiesSet() throws Exception {
		Assert.notNull(dataSource, "DataSource must be provided");
		Assert.notNull(procedureName, "The name of the stored procedure must be provided");
		Assert.notNull(rowMapper, "RowMapper must be provided");
	}

	/**
	 * Public setter for the data source for injection purposes.
	 * 
	 * @param dataSource
	 */
	public void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Executes the provided SQL query. 
	 */
	private void executeQuery() {

		Assert.state(dataSource != null, "DataSource must not be null.");
		Assert.state(procedureName != null, "Procedure Name must not be null.");
		Assert.state(refCursorPosition == 0 || refCursorPosition > parameters.length, 
				"refCursorPosition specified as " + refCursorPosition + " but there are only " + 
				parameters.length + " parameters defined.");

		CallMetaDataContext callContext = new CallMetaDataContext();
		callContext.setAccessCallParameterMetaData(false);
		callContext.setProcedureName(procedureName);
		callContext.setFunction(function);
		callContext.initializeMetaData(dataSource);
		SqlParameter cursorParameter = callContext.createReturnResultSetParameter("return", rowMapper);
					
		int cursorSqlType = Types.OTHER;
		if (function) {
			if (cursorParameter instanceof SqlOutParameter) {
				cursorSqlType = cursorParameter.getSqlType();
			}
		}
		else {
			if (refCursorPosition > 0 && refCursorPosition <= parameters.length) {
				cursorSqlType = parameters[refCursorPosition - 1].getSqlType();
			}
		}
		
		SqlCall call = new SqlCall(dataSource, procedureName){};
		call.setParameters(parameters);
		call.setFunction(function);
		call.compile();
		String callString = call.getCallString();
		call = null;

		try {
			if (useSharedExtendedConnection) {
				if (!(dataSource instanceof ExtendedConnectionDataSourceProxy)) {
					throw new InvalidDataAccessApiUsageException(
							"You must use a ExtendedConnectionDataSourceProxy for the dataSource when " +
							"useSharedExtendedConnection is set to true.");
				}
				this.con = DataSourceUtils.getConnection(dataSource);
				((ExtendedConnectionDataSourceProxy)dataSource).startCloseSuppression(this.con);
				callableStatement = this.con.prepareCall(callString, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
						ResultSet.HOLD_CURSORS_OVER_COMMIT);
			}
			else {
				this.con = dataSource.getConnection();
				callableStatement = this.con.prepareCall(callString, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			}
			applyStatementSettings(callableStatement);
			if (this.preparedStatementSetter != null) {
				preparedStatementSetter.setValues(callableStatement);
			}
			
			if (function) {
				callableStatement.registerOutParameter(1, cursorSqlType);
			}
			else {
				callableStatement.registerOutParameter(refCursorPosition, cursorSqlType);
			}
			boolean results = callableStatement.execute();
			if (results) {
				rs = callableStatement.getResultSet();
			}
			else {
				if (function) {
					rs = (ResultSet) callableStatement.getObject(1);
				}
				else {
					rs = (ResultSet) callableStatement.getObject(refCursorPosition);
				}
			}
			handleWarnings(callableStatement);
		}
		catch (SQLException se) {
			close();
			throw getExceptionTranslator().translate("Executing stored procedure", callString, se);
		}

	}

	/**
	 * Prepare the given JDBC Statement (or PreparedStatement or
	 * CallableStatement), applying statement settings such as fetch size, max
	 * rows, and query timeout. @param stmt the JDBC Statement to prepare
	 * @throws SQLException
	 * 
	 * @see #setFetchSize
	 * @see #setMaxRows
	 * @see #setQueryTimeout
	 */
	private void applyStatementSettings(PreparedStatement stmt) throws SQLException {
		if (fetchSize != VALUE_NOT_SET) {
			stmt.setFetchSize(fetchSize);
			stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
		}
		if (maxRows != VALUE_NOT_SET) {
			stmt.setMaxRows(maxRows);
		}
		if (queryTimeout != VALUE_NOT_SET) {
			stmt.setQueryTimeout(queryTimeout);
		}
	}

	/**
	 * Return the exception translator for this instance.
	 * 
	 * Creates a default SQLErrorCodeSQLExceptionTranslator for the specified
	 * DataSource if none is set.
	 */
	protected SQLExceptionTranslator getExceptionTranslator() {
		if (exceptionTranslator == null) {
			if (dataSource != null) {
				exceptionTranslator = new SQLErrorCodeSQLExceptionTranslator(dataSource);
			}
			else {
				exceptionTranslator = new SQLStateSQLExceptionTranslator();
			}
		}
		return exceptionTranslator;
	}

	/**
	 * Throw a SQLWarningException if we're not ignoring warnings, else log the
	 * warnings (at debug level).
	 * 
	 * @param warnings the warnings object from the current statement. May be
	 * <code>null</code>, in which case this method does nothing.
	 * @throws SQLException
	 * 
	 * @see org.springframework.jdbc.SQLWarningException
	 */
	private void handleWarnings(PreparedStatement pstmt) throws SQLWarningException, SQLException {
		if (ignoreWarnings) {
			if (log.isDebugEnabled()) {
				SQLWarning warningToLog = pstmt.getWarnings();
				while (warningToLog != null) {
					log.debug("SQLWarning ignored: SQL state '" + warningToLog.getSQLState() + "', error code '"
							+ warningToLog.getErrorCode() + "', message [" + warningToLog.getMessage() + "]");
					warningToLog = warningToLog.getNextWarning();
				}
			}
		}
		else {
			SQLWarning warnings = pstmt.getWarnings();
			if (warnings != null) {
				throw new SQLWarningException("Warning not ignored", warnings);
			}
		}
	}

	/**
	 * Moves the cursor in the ResultSet to the position specified by the row
	 * parameter by traversing the ResultSet.
	 * @param row
	 */
	private void moveCursorToRow(int row) {
		try {
			int count = 0;
			while (row != count && rs.next()) {
				count++;
			}
		}
		catch (SQLException se) {
			throw getExceptionTranslator().translate("Attempted to move ResultSet to last committed row", procedureName, se);
		}
	}

	/**
	 * Gives the JDBC driver a hint as to the number of rows that should be
	 * fetched from the database when more rows are needed for this
	 * <code>ResultSet</code> object. If the fetch size specified is zero, the
	 * JDBC driver ignores the value.
	 * 
	 * @param fetchSize the number of rows to fetch
	 * @see ResultSet#setFetchSize(int)
	 */
	public void setFetchSize(int fetchSize) {
		this.fetchSize = fetchSize;
	}

	/**
	 * Sets the limit for the maximum number of rows that any
	 * <code>ResultSet</code> object can contain to the given number.
	 * 
	 * @param maxRows the new max rows limit; zero means there is no limit
	 * @see Statement#setMaxRows(int)
	 */
	public void setMaxRows(int maxRows) {
		this.maxRows = maxRows;
	}

	/**
	 * Sets the number of seconds the driver will wait for a
	 * <code>Statement</code> object to execute to the given number of seconds.
	 * If the limit is exceeded, an <code>SQLException</code> is thrown.
	 * 
	 * @param queryTimeout seconds the new query timeout limit in seconds; zero
	 * means there is no limit
	 * @see Statement#setQueryTimeout(int)
	 */
	public void setQueryTimeout(int queryTimeout) {
		this.queryTimeout = queryTimeout;
	}

	/**
	 * Set whether SQLWarnings should be ignored (only logged) or exception
	 * should be thrown.
	 * 
	 * @param ignoreWarnings if TRUE, warnings are ignored
	 */
	public void setIgnoreWarnings(boolean ignoreWarnings) {
		this.ignoreWarnings = ignoreWarnings;
	}

	/**
	 * Allow verification of cursor position after current row is processed by
	 * RowMapper or RowCallbackHandler. Default value is TRUE.
	 * 
	 * @param verifyCursorPosition if true, cursor position is verified
	 */
	public void setVerifyCursorPosition(boolean verifyCursorPosition) {
		this.verifyCursorPosition = verifyCursorPosition;
	}

	/**
	 * Set the RowMapper to be used for all calls to read().
	 * 
	 * @param rowMapper
	 */
	public void setRowMapper(RowMapper rowMapper) {
		this.rowMapper = rowMapper;
	}

	/**
	 * Set the SQL statement to be used when creating the cursor. This statement
	 * should be a complete and valid SQL statement, as it will be run directly
	 * without any modification.
	 * 
	 * @param sprocedureName
	 */
	public void setProcedureName(String sprocedureName) {
		this.procedureName = sprocedureName;
	}

	/**
	 * Set the PreparedStatementSetter to use if any parameter values that need
	 * to be set in the supplied query.
	 * 
	 * @param preparedStatementSetter
	 */
	public void setPreparedStatementSetter(PreparedStatementSetter preparedStatementSetter) {
		this.preparedStatementSetter = preparedStatementSetter;
	}

	/**
	 * Indicate whether the JDBC driver supports setting the absolute row on a
	 * {@link ResultSet}. It is recommended that this is set to
	 * <code>true</code> for JDBC drivers that supports ResultSet.absolute() as
	 * it may improve performance, especially if a step fails while working with
	 * a large data set.
	 * 
	 * @see ResultSet#absolute(int)
	 * 
	 * @param driverSupportsAbsolute <code>false</code> by default
	 */
	public void setDriverSupportsAbsolute(boolean driverSupportsAbsolute) {
		this.driverSupportsAbsolute = driverSupportsAbsolute;
	}

	/**
	 * Indicate whether the connection used for the cursor should be used by all other processing
	 * thus sharing the same transaction. If this is set to false, which is the default, then the 
	 * cursor will be opened using in its connection and will not participate in any transactions
	 * started for the rest of the step processing. If you set this flag to true then you must 
	 * wrap the DataSource in a {@link ExtendedConnectionDataSourceProxy} to prevent the
	 * connection from being closed and released after each commit.
	 * 
	 * When you set this option to <code>true</code> then the statement used to open the cursor 
	 * will be created with both 'READ_ONLY' and 'HOLD_CUSORS_OVER_COMMIT' options. This allows 
	 * holding the cursor open over transaction start and commits performed in the step processing. 
	 * To use this feature you need a database that supports this and a JDBC driver supporting 
	 * JDBC 3.0 or later.
	 *
	 * @param useSharedExtendedConnection <code>false</code> by default
	 */
	public void setUseSharedExtendedConnection(boolean useSharedExtendedConnection) {
		this.useSharedExtendedConnection = useSharedExtendedConnection;
	}
	
	/**
	 * Add one or more declared parameters. Used for configuring this operation when used in a 
	 * bean factory. Each parameter will specify SQL type and (optionally) the parameter's name. 
	 * 
	 * @param parameters Array containing the declared <code>SqlParameter</code> objects
	 */
	public void setParameters(SqlParameter[] parameters) {
		this.parameters = parameters;
	}
	
	/**
	 * Set whether this stored procedure is a function.
	 */
	public void setFunction(boolean function) {
		this.function = function;
	}

	/**
	 * Set the parameter position of the REF CURSOR. Only used for Oracle and
	 * PostgreSQL that use REF CURSORs. For any other database this should be 
	 * kept as 0 which is the default.
	 *  
	 * @param refCursorPosition The parameter position of the REF CURSOR
	 */
	public void setRefCursorPosition(int refCursorPosition) {
		this.refCursorPosition = refCursorPosition;
	}

	/**
	 * Check the result set is in synch with the currentRow attribute. This is
	 * important to ensure that the user hasn't modified the current row.
	 */
	private void verifyCursorPosition(long expectedCurrentRow) throws SQLException {
		if (verifyCursorPosition) {
			if (expectedCurrentRow != this.rs.getRow()) {
				throw new InvalidDataAccessResourceUsageException("Unexpected cursor position change.");
			}
		}
	}

	/**
	 * Close the cursor and database connection.
	 */
	protected void doClose() throws Exception {
		initialized = false;
		JdbcUtils.closeResultSet(this.rs);
		rs = null;
		JdbcUtils.closeStatement(this.callableStatement);
		if (useSharedExtendedConnection && dataSource instanceof ExtendedConnectionDataSourceProxy) {
			((ExtendedConnectionDataSourceProxy)dataSource).stopCloseSuppression(this.con);
			if (!TransactionSynchronizationManager.isActualTransactionActive()) {
				DataSourceUtils.releaseConnection(con, dataSource);
			}
		}
		else {
			JdbcUtils.closeConnection(this.con);
		}
	}

	/**
	 * Execute the {@link #setSql(String)} query.
	 */
	protected void doOpen() throws Exception {
		Assert.state(!initialized, "Stream is already initialized.  Close before re-opening.");
		Assert.isNull(rs, "ResultSet still open!  Close before re-opening.");
		executeQuery();
		initialized = true;

	}

	/**
	 * Read next row and map it to item, verify cursor position if
	 * {@link #setVerifyCursorPosition(boolean)} is true.
	 */
	@SuppressWarnings("unchecked")
	protected T doRead() throws Exception {
		if (rs == null) {
			throw new ReaderNotOpenException("Reader must be open before it can be read.");
		}

		try {
			if (!rs.next()) {
				return null;
			}
			int currentRow = getCurrentItemCount();
			T item = (T) rowMapper.mapRow(rs, currentRow);
			verifyCursorPosition(currentRow);
			return item;
		}
		catch (SQLException se) {
			throw getExceptionTranslator().translate("Attempt to process next row failed", procedureName, se);
		}
	}

	/**
	 * Use {@link ResultSet#absolute(int)} if possible, otherwise scroll by
	 * calling {@link ResultSet#next()}.
	 */
	protected void jumpToItem(int itemIndex) throws Exception {
		if (driverSupportsAbsolute) {
			try {
				rs.absolute(itemIndex);
			}
			catch (SQLException e) {
				// Driver does not support rs.absolute(int) revert to
				// traversing ResultSet
				log.warn("The JDBC driver does not appear to support ResultSet.absolute(). Consider"
						+ " reverting to the default behavior setting the driverSupportsAbsolute to false", e);

				moveCursorToRow(itemIndex);
			}
		}
		else {
			moveCursorToRow(itemIndex);
		}
	}

}

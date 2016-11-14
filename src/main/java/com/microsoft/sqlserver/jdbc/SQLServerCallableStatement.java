//---------------------------------------------------------------------------------------------------------------------------------
// File: SQLServerCallableStatement.java
//
//
// Microsoft JDBC Driver for SQL Server
// Copyright(c) Microsoft Corporation
// All rights reserved.
// MIT License
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files(the ""Software""), 
//  to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, 
//  and / or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions :
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS 
//  IN THE SOFTWARE.
//---------------------------------------------------------------------------------------------------------------------------------

package com.microsoft.sqlserver.jdbc;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CallableStatement implements JDBC callable statements. CallableStatement
 * allows the caller to specify the procedure name to call along with input
 * parameter value and output parameter types. Callable statement also allows
 * the return of a return status with the ? = call( ?, ..) JDBC syntax
 * <li>The API javadoc for JDBC API methods that this class implements are not
 * repeated here. Please see Sun's JDBC API interfaces javadoc for those
 * details.
 */

public class SQLServerCallableStatement extends SQLServerPreparedStatement implements ISQLServerCallableStatement {

	/** the call param names */
	private ArrayList<String> paramNames;

	/** Number of registered OUT parameters */
	int nOutParams = 0;

	/** number of out params assigned already */
	int nOutParamsAssigned = 0;
	/** The index of the out params indexed - internal index */
	private int outParamIndex = -1;

	// The last out param accessed.
	private Parameter lastParamAccessed;

	/** Currently active Stream Note only one stream can be active at a time */
	private Closeable activeStream;

	// Internal function used in tracing
	String getClassNameInternal() {
		return "SQLServerCallableStatement";
	}

	/**
	 * Create a new callable statement.
	 * 
	 * @param connection
	 *            the connection
	 * @param sql
	 *            the users call syntax
	 * @param nRSType
	 *            the result set type
	 * @param nRSConcur
	 *            the result set concurrency
	 * @param stmtColEncSetting
	 *            the statement column encryption setting
	 * @throws SQLServerException
	 */
	SQLServerCallableStatement(SQLServerConnection connection, String sql, int nRSType, int nRSConcur,
			SQLServerStatementColumnEncryptionSetting stmtColEncSetting) throws SQLServerException {
		super(connection, sql, nRSType, nRSConcur, stmtColEncSetting);
	}

	public void registerOutParameter(int index, int sqlType) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "registerOutParameter",
					new Object[] { new Integer(index), new Integer(sqlType) });
		checkClosed();
		if (index < 1 || index > inOutParam.length) {
			MessageFormat form = new MessageFormat(SQLServerException.getErrString("R_indexOutOfRange"));
			Object[] msgArgs = { new Integer(index) };
			SQLServerException.makeFromDriverError(connection, this, form.format(msgArgs), "7009", false);
		}

		// REF_CURSOR 2012 is a special type - should throw
		// SQLFeatureNotSupportedException as per spec
		// but this will require changing API to throw SQLException.
		// This should be reviewed in 4199060
		if (2012 == sqlType) {
			MessageFormat form = new MessageFormat(SQLServerException.getErrString("R_featureNotSupported"));
			Object[] msgArgs = { new String("REF_CURSOR") };
			SQLServerException.makeFromDriverError(connection, this, form.format(msgArgs), null, false);
		}

		JDBCType jdbcType = JDBCType.of(sqlType);

		// Registering an OUT parameter is an indication that the app is done
		// with the results from any previous execution
		discardLastExecutionResults();

		// OUT parameters registered as unsupported JDBC types map to BINARY
		// so that they are minimally supported.
		if (jdbcType.isUnsupported())
			jdbcType = JDBCType.BINARY;

		Parameter param = inOutParam[index - 1];
		assert null != param;

		// If the parameter was not previously registered for OUTPUT then
		// it is added to the set of OUTPUT parameters now.
		if (!param.isOutput())
			++nOutParams;

		// (Re)register the parameter for OUTPUT with the specified SQL type
		// overriding any previous registration with another SQL type.
		param.registerForOutput(jdbcType, connection);
		switch (sqlType) {
		case microsoft.sql.Types.DATETIME:
			param.setOutScale(3);
			break;
		case java.sql.Types.TIME:
		case java.sql.Types.TIMESTAMP:
		case microsoft.sql.Types.DATETIMEOFFSET:
			param.setOutScale(7);
			break;
		default:
			break;
		}

		loggerExternal.exiting(getClassNameLogging(), "registerOutParameter");
	}

	/**
	 * Locate any output parameter values returned from the procedure call
	 */
	final private Parameter getOutParameter(int i) throws SQLServerException {
		// Process any remaining result sets and update counts. This positions
		// us for retrieving the OUT parameters. Note that after retrieving
		// an OUT parameter, an SQLException is thrown if the application tries
		// to go back and process the results.
		processResults();

		// if this item has been indexed already leave!
		if (inOutParam[i - 1] == lastParamAccessed || inOutParam[i - 1].isValueGotten())
			return inOutParam[i - 1];

		// Skip OUT parameters (buffering them as we go) until we
		// reach the one we're looking for.
		while (outParamIndex != i - 1)
			skipOutParameters(1, false);

		return inOutParam[i - 1];
	}

	void startResults() {
		super.startResults();
		outParamIndex = -1;
		nOutParamsAssigned = 0;
		lastParamAccessed = null;
		assert null == activeStream;
	}

	void processBatch() throws SQLServerException {
		processResults();

		// If there were any OUT parameters, then process them
		// and the rest of the batch that follows them. If there were
		// no OUT parameters, than the entire batch was already processed
		// in the processResults call above.
		assert nOutParams >= 0;
		if (nOutParams > 0) {
			processOutParameters();
			processBatchRemainder();
		}
	}

	final void processOutParameters() throws SQLServerException {
		assert nOutParams > 0;
		assert null != inOutParam;

		// make sure if we have active streams they are closed out.
		closeActiveStream();

		// First, discard all of the previously indexed OUT parameters up to,
		// but not including, the last-indexed parameter.
		if (outParamIndex >= 0) {
			// Note: It doesn't matter that they're not cleared in the order
			// they
			// appear in the response stream. What counts is that at the end
			// none of them has any TDSReaderMarks holding onto any portion of
			// the response stream.
			for (int index = 0; index < inOutParam.length; ++index) {
				if (index != outParamIndex && inOutParam[index].isValueGotten()) {
					assert inOutParam[index].isOutput();
					inOutParam[index].resetOutputValue();
				}
			}
		}

		// Next, if there are any unindexed parameters left then discard them
		// too.
		assert nOutParamsAssigned <= nOutParams;
		if (nOutParamsAssigned < nOutParams)
			skipOutParameters(nOutParams - nOutParamsAssigned, true);

		// Finally, skip the last-indexed parameter. If there were no unindexed
		// parameters
		// in the previous step, then this is the last-indexed parameter left
		// from the first
		// step. If we skipped unindexed parameters in the previous step, then
		// this is the
		// last-indexed parameter left at the end of that step.
		if (outParamIndex >= 0) {
			inOutParam[outParamIndex].skipValue(resultsReader(), true);
			inOutParam[outParamIndex].resetOutputValue();
			outParamIndex = -1;
		}
	}

	/**
	 * Processes the remainder of the batch up to the final or batch-terminating
	 * DONE token that marks the end of a sp_[cursor][prep]exec stored procedure
	 * call.
	 */
	private void processBatchRemainder() throws SQLServerException {
		final class ExecDoneHandler extends TDSTokenHandler {
			ExecDoneHandler() {
				super("ExecDoneHandler");
			}

			boolean onDone(TDSReader tdsReader) throws SQLServerException {
				// Consume the done token and decide what to do with it...
				StreamDone doneToken = new StreamDone();
				doneToken.setFromTDS(tdsReader);

				// If this is a non-final batch-terminating DONE token,
				// then stop parsing the response now and set up for
				// the next batch.
				if (doneToken.wasRPCInBatch()) {
					startResults();
					return false;
				}

				// Continue processing so that we pick up ENVCHANGE tokens.
				// Parsing stops automatically on response EOF.
				return true;
			}
		}

		ExecDoneHandler execDoneHandler = new ExecDoneHandler();
		TDSParser.parse(resultsReader(), execDoneHandler);
	}

	private void skipOutParameters(int numParamsToSkip, boolean discardValues) throws SQLServerException {
		/**
		 * TDS token handler for locating OUT parameters (RETURN_VALUE tokens)
		 * in the response token stream
		 */
		final class OutParamHandler extends TDSTokenHandler {
			final StreamRetValue srv = new StreamRetValue();

			private boolean foundParam;

			final boolean foundParam() {
				return foundParam;
			}

			OutParamHandler() {
				super("OutParamHandler");
			}

			final void reset() {
				foundParam = false;
			}

			boolean onRetValue(TDSReader tdsReader) throws SQLServerException {
				srv.setFromTDS(tdsReader);
				foundParam = true;
				return false;
			}
		}

		OutParamHandler outParamHandler = new OutParamHandler();

		// Index the application OUT parameters
		assert numParamsToSkip <= nOutParams - nOutParamsAssigned;
		for (int paramsSkipped = 0; paramsSkipped < numParamsToSkip; ++paramsSkipped) {
			// Discard the last-indexed parameter by skipping over it and
			// discarding the value if it is no longer needed.
			if (-1 != outParamIndex) {
				inOutParam[outParamIndex].skipValue(resultsReader(), discardValues);
				if (discardValues)
					inOutParam[outParamIndex].resetOutputValue();
			}

			// Look for the next parameter value in the response.
			outParamHandler.reset();
			TDSParser.parse(resultsReader(), outParamHandler);

			// If we don't find it, then most likely the server encountered some
			// error that
			// was bad enough to halt statement execution before returning OUT
			// params, but
			// not necessarily bad enough to close the connection.
			if (!outParamHandler.foundParam()) {
				// If we were just going to discard the OUT parameters we found
				// anyway,
				// then it's no problem that we didn't find any of them. For
				// exmaple,
				// when we are closing or reexecuting this CallableStatement
				// (that is,
				// calling in through processResponse), we don't care that
				// execution
				// failed to return the OUT parameters.
				if (discardValues)
					break;

				// If we were asked to retain the OUT parameters as we skip past
				// them,
				// then report an error if we did not find any.
				MessageFormat form = new MessageFormat(SQLServerException.getErrString("R_valueNotSetForParameter"));
				Object[] msgArgs = { new Integer(outParamIndex + 1) };
				SQLServerException.makeFromDriverError(connection, this, form.format(msgArgs), null, false);
			}

			// In Yukon and later, large Object output parameters are reordered
			// to appear at
			// the end of the stream. First group of small parameters is sent,
			// followed by
			// group of large output parameters. There is no reordering within
			// the groups.

			// Note that parameter ordinals are 0-indexed and that the return
			// status is not
			// considered to be an output parameter.
			outParamIndex = outParamHandler.srv.getOrdinalOrLength();

			// Statements need to have their out param indices adjusted by the
			// number
			// of sp_[cursor][prep]exec params.
			outParamIndex -= outParamIndexAdjustment;
			if ((outParamIndex < 0 || outParamIndex >= inOutParam.length) || (!inOutParam[outParamIndex].isOutput())) {
				getStatementLogger().info(toString() + " Unexpected outParamIndex: " + outParamIndex + "; adjustment: "
						+ outParamIndexAdjustment);
				connection.throwInvalidTDS();
			}

			++nOutParamsAssigned;
		}
	}

	/* L0 */ public void registerOutParameter(int index, int sqlType, String typeName) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "registerOutParameter",
					new Object[] { new Integer(index), new Integer(sqlType), typeName });

		checkClosed();

		registerOutParameter(index, sqlType);

		loggerExternal.exiting(getClassNameLogging(), "registerOutParameter");
	}

	/* L0 */ public void registerOutParameter(int index, int sqlType, int scale) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "registerOutParameter",
					new Object[] { new Integer(index), new Integer(sqlType), new Integer(scale) });

		checkClosed();

		registerOutParameter(index, sqlType);
		inOutParam[index - 1].setOutScale(scale);

		loggerExternal.exiting(getClassNameLogging(), "registerOutParameter");
	}

	public void registerOutParameter(int index, int sqlType, int precision, int scale) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "registerOutParameter", new Object[] { new Integer(index),
					new Integer(sqlType), new Integer(scale), new Integer(precision) });

		checkClosed();

		registerOutParameter(index, sqlType);
		inOutParam[index - 1].setValueLength(precision);
		inOutParam[index - 1].setOutScale(scale);

		loggerExternal.exiting(getClassNameLogging(), "registerOutParameter");
	}

	/*
	 * ---------------------- JDBC API: Get Output Params
	 * --------------------------
	 */

	private Parameter getterGetParam(int index) throws SQLServerException {
		checkClosed();

		// Check for valid index
		if (index < 1 || index > inOutParam.length) {
			MessageFormat form = new MessageFormat(SQLServerException.getErrString("R_invalidOutputParameter"));
			Object[] msgArgs = { new Integer(index) };
			SQLServerException.makeFromDriverError(connection, this, form.format(msgArgs), "07009", false);
		}

		// Check index refers to a registered OUT parameter
		if (!inOutParam[index - 1].isOutput()) {
			MessageFormat form = new MessageFormat(
					SQLServerException.getErrString("R_outputParameterNotRegisteredForOutput"));
			Object[] msgArgs = { new Integer(index) };
			SQLServerException.makeFromDriverError(connection, this, form.format(msgArgs), "07009", true);
		}

		// If we haven't executed the statement yet then throw a nice friendly
		// exception.
		if (!wasExecuted())
			SQLServerException.makeFromDriverError(connection, this,
					SQLServerException.getErrString("R_statementMustBeExecuted"), "07009", false);

		resultsReader().getCommand().checkForInterrupt();

		closeActiveStream();
		if (getStatementLogger().isLoggable(java.util.logging.Level.FINER))
			getStatementLogger().finer(toString() + " Getting Param:" + index);

		// Dynamically load OUT params from TDS response buffer
		lastParamAccessed = getOutParameter(index);
		return lastParamAccessed;
	}

	private Object getValue(int parameterIndex, JDBCType jdbcType) throws SQLServerException {
		return getterGetParam(parameterIndex).getValue(jdbcType, null, null, resultsReader());
	}

	private Object getValue(int parameterIndex, JDBCType jdbcType, Calendar cal) throws SQLServerException {
		return getterGetParam(parameterIndex).getValue(jdbcType, null, cal, resultsReader());
	}

	private Object getStream(int parameterIndex, StreamType streamType) throws SQLServerException {
		Object value = getterGetParam(parameterIndex).getValue(streamType.getJDBCType(),
				new InputStreamGetterArgs(streamType, getIsResponseBufferingAdaptive(),
						getIsResponseBufferingAdaptive(), toString()),
				null, // calendar
				resultsReader());

		activeStream = (Closeable) value;
		return value;
	}

	private Object getSQLXMLInternal(int parameterIndex) throws SQLServerException {
		SQLServerSQLXML value = (SQLServerSQLXML) getterGetParam(parameterIndex).getValue(JDBCType.SQLXML,
				new InputStreamGetterArgs(StreamType.SQLXML, getIsResponseBufferingAdaptive(),
						getIsResponseBufferingAdaptive(), toString()),
				null, // calendar
				resultsReader());

		if (null != value)
			activeStream = (Closeable) value.getStream();
		return value;
	}

	public int getInt(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getInt", index);
		checkClosed();
		Integer value = (Integer) getValue(index, JDBCType.INTEGER);
		loggerExternal.exiting(getClassNameLogging(), "getInt", value);
		return null != value ? value.intValue() : 0;
	}

	public int getInt(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getInt", sCol);
		checkClosed();
		Integer value = (Integer) getValue(findColumn(sCol), JDBCType.INTEGER);
		loggerExternal.exiting(getClassNameLogging(), "getInt", value);
		return null != value ? value.intValue() : 0;
	}

	public String getString(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getString", index);
		checkClosed();
		String value = (String) getValue(index, JDBCType.CHAR);
		loggerExternal.exiting(getClassNameLogging(), "getString", value);
		return value;
	}

	public String getString(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getString", sCol);
		checkClosed();
		String value = (String) getValue(findColumn(sCol), JDBCType.CHAR);
		loggerExternal.exiting(getClassNameLogging(), "getString", value);
		return value;
	}

	public final String getNString(int parameterIndex) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();

		loggerExternal.entering(getClassNameLogging(), "getNString", parameterIndex);
		checkClosed();
		String value = (String) getValue(parameterIndex, JDBCType.NCHAR);
		loggerExternal.exiting(getClassNameLogging(), "getNString", value);
		return value;
	}

	public final String getNString(String parameterName) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();

		loggerExternal.entering(getClassNameLogging(), "getNString", parameterName);
		checkClosed();
		String value = (String) getValue(findColumn(parameterName), JDBCType.NCHAR);
		loggerExternal.exiting(getClassNameLogging(), "getNString", value);
		return value;
	}

	@Deprecated
	public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getBigDecimal",
					new Object[] { Integer.valueOf(parameterIndex), Integer.valueOf(scale) });
		checkClosed();
		BigDecimal value = (BigDecimal) getValue(parameterIndex, JDBCType.DECIMAL);
		if (null != value)
			value = value.setScale(scale, BigDecimal.ROUND_DOWN);
		loggerExternal.exiting(getClassNameLogging(), "getBigDecimal", value);
		return value;
	}

	@Deprecated
	public BigDecimal getBigDecimal(String parameterName, int scale) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getBigDecimal",
					new Object[] { parameterName, Integer.valueOf(scale) });
		checkClosed();
		BigDecimal value = (BigDecimal) getValue(findColumn(parameterName), JDBCType.DECIMAL);
		if (null != value)
			value = value.setScale(scale, BigDecimal.ROUND_DOWN);
		loggerExternal.exiting(getClassNameLogging(), "getBigDecimal", value);
		return value;
	}

	public boolean getBoolean(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBoolean", index);
		checkClosed();
		Boolean value = (Boolean) getValue(index, JDBCType.BIT);
		loggerExternal.exiting(getClassNameLogging(), "getBoolean", value);
		return null != value ? value.booleanValue() : false;
	}

	public boolean getBoolean(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBoolean", sCol);
		checkClosed();
		Boolean value = (Boolean) getValue(findColumn(sCol), JDBCType.BIT);
		loggerExternal.exiting(getClassNameLogging(), "getBoolean", value);
		return null != value ? value.booleanValue() : false;
	}

	public byte getByte(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getByte", index);
		checkClosed();
		Short shortValue = (Short) getValue(index, JDBCType.TINYINT);
		byte byteValue = (null != shortValue) ? shortValue.byteValue() : 0;
		loggerExternal.exiting(getClassNameLogging(), "getByte", byteValue);
		return byteValue;
	}

	public byte getByte(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getByte", sCol);
		checkClosed();
		Short shortValue = (Short) getValue(findColumn(sCol), JDBCType.TINYINT);
		byte byteValue = (null != shortValue) ? shortValue.byteValue() : 0;
		loggerExternal.exiting(getClassNameLogging(), "getByte", byteValue);
		return byteValue;
	}

	public byte[] getBytes(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBytes", index);
		checkClosed();
		byte[] value = (byte[]) getValue(index, JDBCType.BINARY);
		loggerExternal.exiting(getClassNameLogging(), "getBytes", value);
		return value;
	}

	public byte[] getBytes(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBytes", sCol);
		checkClosed();
		byte[] value = (byte[]) getValue(findColumn(sCol), JDBCType.BINARY);
		loggerExternal.exiting(getClassNameLogging(), "getBytes", value);
		return value;
	}

	public Date getDate(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getDate", index);
		checkClosed();
		java.sql.Date value = (java.sql.Date) getValue(index, JDBCType.DATE);
		loggerExternal.exiting(getClassNameLogging(), "getDate", value);
		return value;
	}

	public Date getDate(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getDate", sCol);
		checkClosed();
		java.sql.Date value = (java.sql.Date) getValue(findColumn(sCol), JDBCType.DATE);
		loggerExternal.exiting(getClassNameLogging(), "getDate", value);
		return value;
	}

	public Date getDate(int index, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getDate", new Object[] { index, cal });
		checkClosed();
		java.sql.Date value = (java.sql.Date) getValue(index, JDBCType.DATE, cal);
		loggerExternal.exiting(getClassNameLogging(), "getDate", value);
		return value;
	}

	public Date getDate(String sCol, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getDate", new Object[] { sCol, cal });
		checkClosed();
		java.sql.Date value = (java.sql.Date) getValue(findColumn(sCol), JDBCType.DATE, cal);
		loggerExternal.exiting(getClassNameLogging(), "getDate", value);
		return value;
	}

	public double getDouble(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getDouble", index);
		checkClosed();
		Double value = (Double) getValue(index, JDBCType.DOUBLE);
		loggerExternal.exiting(getClassNameLogging(), "getDouble", value);
		return null != value ? value.doubleValue() : 0;
	}

	public double getDouble(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getDouble", sCol);
		checkClosed();
		Double value = (Double) getValue(findColumn(sCol), JDBCType.DOUBLE);
		loggerExternal.exiting(getClassNameLogging(), "getDouble", value);
		return null != value ? value.doubleValue() : 0;
	}

	public float getFloat(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getFloat", index);
		checkClosed();
		Float value = (Float) getValue(index, JDBCType.REAL);
		loggerExternal.exiting(getClassNameLogging(), "getFloat", value);
		return null != value ? value.floatValue() : 0;
	}

	public float getFloat(String sCol) throws SQLServerException {

		loggerExternal.entering(getClassNameLogging(), "getFloat", sCol);
		checkClosed();
		Float value = (Float) getValue(findColumn(sCol), JDBCType.REAL);
		loggerExternal.exiting(getClassNameLogging(), "getFloat", value);
		return null != value ? value.floatValue() : 0;
	}

	public long getLong(int index) throws SQLServerException {

		loggerExternal.entering(getClassNameLogging(), "getLong", index);
		checkClosed();
		Long value = (Long) getValue(index, JDBCType.BIGINT);
		loggerExternal.exiting(getClassNameLogging(), "getLong", value);
		return null != value ? value.longValue() : 0;
	}

	public long getLong(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getLong", sCol);
		checkClosed();
		Long value = (Long) getValue(findColumn(sCol), JDBCType.BIGINT);
		loggerExternal.exiting(getClassNameLogging(), "getLong", value);
		return null != value ? value.longValue() : 0;
	}

	public Object getObject(int index) throws SQLServerException {

		loggerExternal.entering(getClassNameLogging(), "getObject", index);
		checkClosed();
		Object value = getValue(index, getterGetParam(index).getJdbcTypeSetByUser() != null
				? getterGetParam(index).getJdbcTypeSetByUser() : getterGetParam(index).getJdbcType());
		loggerExternal.exiting(getClassNameLogging(), "getObject", value);
		return value;
	}

	public <T> T getObject(int index, Class<T> type) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC41();

		// The driver currently does not implement JDDBC 4.1 APIs
		throw new SQLFeatureNotSupportedException(SQLServerException.getErrString("R_notSupported"));
	}

	public Object getObject(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getObject", sCol);
		checkClosed();
		int parameterIndex = findColumn(sCol);
		Object value = getValue(parameterIndex, getterGetParam(parameterIndex).getJdbcTypeSetByUser() != null
				? getterGetParam(parameterIndex).getJdbcTypeSetByUser() : getterGetParam(parameterIndex).getJdbcType());
		loggerExternal.exiting(getClassNameLogging(), "getObject", value);
		return value;
	}

	public <T> T getObject(String sCol, Class<T> type) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC41();

		// The driver currently does not implement JDDBC 4.1 APIs
		throw new SQLFeatureNotSupportedException(SQLServerException.getErrString("R_notSupported"));
	}

	public short getShort(int index) throws SQLServerException {

		loggerExternal.entering(getClassNameLogging(), "getShort", index);
		checkClosed();
		Short value = (Short) getValue(index, JDBCType.SMALLINT);
		loggerExternal.exiting(getClassNameLogging(), "getShort", value);
		return null != value ? value.shortValue() : 0;
	}

	public short getShort(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getShort", sCol);
		checkClosed();
		Short value = (Short) getValue(findColumn(sCol), JDBCType.SMALLINT);
		loggerExternal.exiting(getClassNameLogging(), "getShort", value);
		return null != value ? value.shortValue() : 0;
	}

	public Time getTime(int index) throws SQLServerException {

		loggerExternal.entering(getClassNameLogging(), "getTime", index);
		checkClosed();
		java.sql.Time value = (java.sql.Time) getValue(index, JDBCType.TIME);
		loggerExternal.exiting(getClassNameLogging(), "getTime", value);
		return value;
	}

	public Time getTime(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getTime", sCol);
		checkClosed();
		java.sql.Time value = (java.sql.Time) getValue(findColumn(sCol), JDBCType.TIME);
		loggerExternal.exiting(getClassNameLogging(), "getTime", value);
		return value;
	}

	public Time getTime(int index, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getTime", new Object[] { index, cal });
		checkClosed();
		java.sql.Time value = (java.sql.Time) getValue(index, JDBCType.TIME, cal);
		loggerExternal.exiting(getClassNameLogging(), "getTime", value);
		return value;
	}

	public Time getTime(String sCol, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getTime", new Object[] { sCol, cal });
		checkClosed();
		java.sql.Time value = (java.sql.Time) getValue(findColumn(sCol), JDBCType.TIME, cal);
		loggerExternal.exiting(getClassNameLogging(), "getTime", value);
		return value;
	}

	public Timestamp getTimestamp(int index) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getTimestamp", index);
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(index, JDBCType.TIMESTAMP);
		loggerExternal.exiting(getClassNameLogging(), "getTimestamp", value);
		return value;
	}

	public Timestamp getTimestamp(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getTimestamp", sCol);
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(findColumn(sCol), JDBCType.TIMESTAMP);
		loggerExternal.exiting(getClassNameLogging(), "getTimestamp", value);
		return value;
	}

	public Timestamp getTimestamp(int index, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getTimestamp", new Object[] { index, cal });
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(index, JDBCType.TIMESTAMP, cal);
		loggerExternal.exiting(getClassNameLogging(), "getTimestamp", value);
		return value;
	}

	public Timestamp getTimestamp(String name, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getTimestamp", new Object[] { name, cal });
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(findColumn(name), JDBCType.TIMESTAMP, cal);
		loggerExternal.exiting(getClassNameLogging(), "getTimestamp", value);
		return value;
	}

	public Timestamp getDateTime(int index) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getDateTime", index);
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(index, JDBCType.DATETIME);
		loggerExternal.exiting(getClassNameLogging(), "getDateTime", value);
		return value;
	}

	public Timestamp getDateTime(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getDateTime", sCol);
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(findColumn(sCol), JDBCType.DATETIME);
		loggerExternal.exiting(getClassNameLogging(), "getDateTime", value);
		return value;
	}

	public Timestamp getDateTime(int index, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getDateTime", new Object[] { index, cal });
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(index, JDBCType.DATETIME, cal);
		loggerExternal.exiting(getClassNameLogging(), "getDateTime", value);
		return value;
	}

	public Timestamp getDateTime(String name, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getDateTime", new Object[] { name, cal });
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(findColumn(name), JDBCType.DATETIME, cal);
		loggerExternal.exiting(getClassNameLogging(), "getDateTime", value);
		return value;
	}

	public Timestamp getSmallDateTime(int index) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getSmallDateTime", index);
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(index, JDBCType.SMALLDATETIME);
		loggerExternal.exiting(getClassNameLogging(), "getSmallDateTime", value);
		return value;
	}

	public Timestamp getSmallDateTime(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getSmallDateTime", sCol);
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(findColumn(sCol), JDBCType.SMALLDATETIME);
		loggerExternal.exiting(getClassNameLogging(), "getSmallDateTime", value);
		return value;
	}

	public Timestamp getSmallDateTime(int index, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getSmallDateTime", new Object[] { index, cal });
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(index, JDBCType.SMALLDATETIME, cal);
		loggerExternal.exiting(getClassNameLogging(), "getSmallDateTime", value);
		return value;
	}

	public Timestamp getSmallDateTime(String name, Calendar cal) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getSmallDateTime", new Object[] { name, cal });
		checkClosed();
		java.sql.Timestamp value = (java.sql.Timestamp) getValue(findColumn(name), JDBCType.SMALLDATETIME, cal);
		loggerExternal.exiting(getClassNameLogging(), "getSmallDateTime", value);
		return value;
	}

	public microsoft.sql.DateTimeOffset getDateTimeOffset(int index) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "getDateTimeOffset", index);
		checkClosed();

		// DateTimeOffset is not supported with SQL Server versions earlier than
		// Katmai
		if (!connection.isKatmaiOrLater())
			throw new SQLServerException(SQLServerException.getErrString("R_notSupported"),
					SQLState.DATA_EXCEPTION_NOT_SPECIFIC, DriverError.NOT_SET, null);

		microsoft.sql.DateTimeOffset value = (microsoft.sql.DateTimeOffset) getValue(index, JDBCType.DATETIMEOFFSET);
		loggerExternal.exiting(getClassNameLogging(), "getDateTimeOffset", value);
		return value;
	}

	public microsoft.sql.DateTimeOffset getDateTimeOffset(String sCol) throws SQLException {
		loggerExternal.entering(getClassNameLogging(), "getDateTimeOffset", sCol);
		checkClosed();

		// DateTimeOffset is not supported with SQL Server versions earlier than
		// Katmai
		if (!connection.isKatmaiOrLater())
			throw new SQLServerException(SQLServerException.getErrString("R_notSupported"),
					SQLState.DATA_EXCEPTION_NOT_SPECIFIC, DriverError.NOT_SET, null);

		microsoft.sql.DateTimeOffset value = (microsoft.sql.DateTimeOffset) getValue(findColumn(sCol),
				JDBCType.DATETIMEOFFSET);
		loggerExternal.exiting(getClassNameLogging(), "getDateTimeOffset", value);
		return value;
	}

	/* L0 */ public boolean wasNull() throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "wasNull");
		checkClosed();
		boolean bWasNull = false;
		if (null != lastParamAccessed) {
			bWasNull = lastParamAccessed.isNull();
		}
		loggerExternal.exiting(getClassNameLogging(), "wasNull", bWasNull);
		return bWasNull;
	}

	public final java.io.InputStream getAsciiStream(int paramIndex) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getAsciiStream", paramIndex);
		checkClosed();
		InputStream value = (InputStream) getStream(paramIndex, StreamType.ASCII);
		loggerExternal.exiting(getClassNameLogging(), "getAsciiStream", value);
		return value;
	}

	public final java.io.InputStream getAsciiStream(String paramName) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getAsciiStream", paramName);
		checkClosed();
		InputStream value = (InputStream) getStream(findColumn(paramName), StreamType.ASCII);
		loggerExternal.exiting(getClassNameLogging(), "getAsciiStream", value);
		return value;
	}

	public BigDecimal getBigDecimal(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBigDecimal", index);
		checkClosed();
		BigDecimal value = (BigDecimal) getValue(index, JDBCType.DECIMAL);
		loggerExternal.exiting(getClassNameLogging(), "getBigDecimal", value);
		return value;
	}

	public BigDecimal getBigDecimal(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBigDecimal", sCol);
		checkClosed();
		BigDecimal value = (BigDecimal) getValue(findColumn(sCol), JDBCType.DECIMAL);
		loggerExternal.exiting(getClassNameLogging(), "getBigDecimal", value);
		return value;
	}

	public BigDecimal getMoney(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getMoney", index);
		checkClosed();
		BigDecimal value = (BigDecimal) getValue(index, JDBCType.MONEY);
		loggerExternal.exiting(getClassNameLogging(), "getMoney", value);
		return value;
	}

	public BigDecimal getMoney(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getMoney", sCol);
		checkClosed();
		BigDecimal value = (BigDecimal) getValue(findColumn(sCol), JDBCType.MONEY);
		loggerExternal.exiting(getClassNameLogging(), "getMoney", value);
		return value;
	}

	public BigDecimal getSmallMoney(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getSmallMoney", index);
		checkClosed();
		BigDecimal value = (BigDecimal) getValue(index, JDBCType.SMALLMONEY);
		loggerExternal.exiting(getClassNameLogging(), "getSmallMoney", value);
		return value;
	}

	public BigDecimal getSmallMoney(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getSmallMoney", sCol);
		checkClosed();
		BigDecimal value = (BigDecimal) getValue(findColumn(sCol), JDBCType.SMALLMONEY);
		loggerExternal.exiting(getClassNameLogging(), "getSmallMoney", value);
		return value;
	}

	public final java.io.InputStream getBinaryStream(int paramIndex) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBinaryStream", paramIndex);
		checkClosed();
		InputStream value = (InputStream) getStream(paramIndex, StreamType.BINARY);
		loggerExternal.exiting(getClassNameLogging(), "getBinaryStream", value);
		return value;
	}

	public final java.io.InputStream getBinaryStream(String paramName) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBinaryStream", paramName);
		checkClosed();
		InputStream value = (InputStream) getStream(findColumn(paramName), StreamType.BINARY);
		loggerExternal.exiting(getClassNameLogging(), "getBinaryStream", value);
		return value;
	}

	public Blob getBlob(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBlob", index);
		checkClosed();
		Blob value = (Blob) getValue(index, JDBCType.BLOB);
		loggerExternal.exiting(getClassNameLogging(), "getBlob", value);
		return value;
	}

	public Blob getBlob(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getBlob", sCol);
		checkClosed();
		Blob value = (Blob) getValue(findColumn(sCol), JDBCType.BLOB);
		loggerExternal.exiting(getClassNameLogging(), "getBlob", value);
		return value;
	}

	public final java.io.Reader getCharacterStream(int paramIndex) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getCharacterStream", paramIndex);
		checkClosed();
		Reader reader = (Reader) getStream(paramIndex, StreamType.CHARACTER);
		loggerExternal.exiting(getClassNameLogging(), "getCharacterStream", reader);
		return reader;
	}

	public final java.io.Reader getCharacterStream(String parameterName) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();

		loggerExternal.entering(getClassNameLogging(), "getCharacterStream", parameterName);
		checkClosed();
		Reader reader = (Reader) getStream(findColumn(parameterName), StreamType.CHARACTER);
		loggerExternal.exiting(getClassNameLogging(), "getCharacterSream", reader);
		return reader;
	}

	public final java.io.Reader getNCharacterStream(int parameterIndex) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		loggerExternal.entering(getClassNameLogging(), "getNCharacterStream", parameterIndex);
		checkClosed();
		Reader reader = (Reader) getStream(parameterIndex, StreamType.NCHARACTER);
		loggerExternal.exiting(getClassNameLogging(), "getNCharacterStream", reader);
		return reader;
	}

	public final java.io.Reader getNCharacterStream(String parameterName) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();

		loggerExternal.entering(getClassNameLogging(), "getNCharacterStream", parameterName);
		checkClosed();
		Reader reader = (Reader) getStream(findColumn(parameterName), StreamType.NCHARACTER);
		loggerExternal.exiting(getClassNameLogging(), "getNCharacterStream", reader);
		return reader;
	}

	void closeActiveStream() throws SQLServerException {
		if (null != activeStream) {
			try {
				activeStream.close();
			} catch (IOException e) {
				SQLServerException.makeFromDriverError(null, null, e.getMessage(), null, true);
			} finally {
				activeStream = null;
			}
		}
	}

	public Clob getClob(int index) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getClob", index);
		checkClosed();
		Clob clob = (Clob) getValue(index, JDBCType.CLOB);
		loggerExternal.exiting(getClassNameLogging(), "getClob", clob);
		return clob;
	}

	public Clob getClob(String sCol) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "getClob", sCol);
		checkClosed();
		Clob clob = (Clob) getValue(findColumn(sCol), JDBCType.CLOB);
		loggerExternal.exiting(getClassNameLogging(), "getClob", clob);
		return clob;
	}

	public NClob getNClob(int parameterIndex) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		loggerExternal.entering(getClassNameLogging(), "getNClob", parameterIndex);
		checkClosed();
		NClob nClob = (NClob) getValue(parameterIndex, JDBCType.NCLOB);
		loggerExternal.exiting(getClassNameLogging(), "getNClob", nClob);
		return nClob;
	}

	public NClob getNClob(String parameterName) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		loggerExternal.entering(getClassNameLogging(), "getNClob", parameterName);
		checkClosed();
		NClob nClob = (NClob) getValue(findColumn(parameterName), JDBCType.NCLOB);
		loggerExternal.exiting(getClassNameLogging(), "getNClob", nClob);
		return nClob;
	}

	/* L0 */ public Object getObject(int index, java.util.Map<String, Class<?>> map) throws SQLServerException {
		NotImplemented();
		return null;
	}

	/* L3 */ public Object getObject(String sCol, java.util.Map<String, Class<?>> m) throws SQLServerException {
		checkClosed();
		return getObject(findColumn(sCol), m);
	}

	/* L0 */ public Ref getRef(int i) throws SQLServerException {
		NotImplemented();
		return null;
	}

	/* L3 */ public Ref getRef(String sCol) throws SQLServerException {
		checkClosed();
		return getRef(findColumn(sCol));
	}

	/* L0 */ public java.sql.Array getArray(int i) throws SQLServerException {
		NotImplemented();
		return null;
	}

	/* L3 */ public java.sql.Array getArray(String sCol) throws SQLServerException {
		checkClosed();
		return getArray(findColumn(sCol));
	}

	/* JDBC 3.0 */

	/**
	 * Find a column's index given its name.
	 * 
	 * @param columnName
	 *            the name
	 * @throws SQLServerException
	 * @return the index
	 */
	/* L3 */ private int findColumn(String columnName) throws SQLServerException {

		final class ThreePartNamesParser {

			private String procedurePart = null;
			private String ownerPart = null;
			private String databasePart = null;

			String getProcedurePart() {
				return procedurePart;
			}

			String getOwnerPart() {
				return ownerPart;
			}

			String getDatabasePart() {
				return databasePart;
			}

			/*
			 * Three part names parsing For metdata calls we parse the procedure
			 * name into parts so we can use it in sp_sproc_columns
			 * sp_sproc_columns [[@procedure_name =] 'name'] [,[@procedure_owner
			 * =] 'owner'] [,[@procedure_qualifier =] 'qualifier']
			 * 
			 */
			private final Pattern threePartName = Pattern.compile(JDBCSyntaxTranslator.getSQLIdentifierWithGroups());

			final void parseProcedureNameIntoParts(String theProcName) {
				Matcher matcher;
				if (null != theProcName) {
					matcher = threePartName.matcher(theProcName);
					if (matcher.matches()) {
						if (matcher.group(2) != null) {
							databasePart = matcher.group(1);

							// if we have two parts look to see if the last part
							// can be broken even more
							matcher = threePartName.matcher(matcher.group(2));
							if (matcher.matches()) {
								if (null != matcher.group(2)) {
									ownerPart = matcher.group(1);
									procedurePart = matcher.group(2);
								} else {
									ownerPart = databasePart;
									databasePart = null;
									procedurePart = matcher.group(1);
								}
							}

						} else
							procedurePart = matcher.group(1);

					} else {
						procedurePart = theProcName;
					}
				}

			}

		}

		if (paramNames == null) {
			try {
				// Note we are concatenating the information from the passed in
				// sql, not any arguments provided by the user
				// if the user can execute the sql, any fragments of it is
				// potentially executed via the meta data call through injection
				// is not a security issue.
				SQLServerStatement s = (SQLServerStatement) connection.createStatement();
				ThreePartNamesParser translator = new ThreePartNamesParser();
				translator.parseProcedureNameIntoParts(procedureName);
				StringBuilder metaQuery = new StringBuilder("exec sp_sproc_columns ");
				if (null != translator.getDatabasePart()) {
					metaQuery.append("@procedure_qualifier=");
					metaQuery.append(translator.getDatabasePart());
					metaQuery.append(", ");
				}
				if (null != translator.getOwnerPart()) {
					metaQuery.append("@procedure_owner=");
					metaQuery.append(translator.getOwnerPart());
					metaQuery.append(", ");
				}
				if (null != translator.getProcedurePart()) {
					// we should always have a procedure name part
					metaQuery.append("@procedure_name=");
					metaQuery.append(translator.getProcedurePart());
					metaQuery.append(" , @ODBCVer=3");
				} else {
					// This should rarely happen, this will only happen if we
					// cant find the stored procedure name
					// invalidly formatted call syntax.
					MessageFormat form = new MessageFormat(
							SQLServerException.getErrString("R_parameterNotDefinedForProcedure"));
					Object[] msgArgs = { columnName, "" };
					SQLServerException.makeFromDriverError(connection, this, form.format(msgArgs), "07009", false);
				}

				ResultSet rs = s.executeQueryInternal(metaQuery.toString());
				paramNames = new ArrayList<String>();
				while (rs.next()) {
					String sCol = rs.getString(4);
					paramNames.add(sCol.trim());
				}
			} catch (SQLException e) {
				SQLServerException.makeFromDriverError(connection, this, e.toString(), null, false);
			}
		}

		int l = 0;
		if (paramNames != null)
			l = paramNames.size();

		// In order to be as accurate as possible when locating parameter name
		// indexes, as well as be deterministic when running on various client
		// locales, we search for parameter names using the following scheme:

		// 1. Search using case-sensitive non-locale specific (binary) compare
		// first.
		// 2. Search using case-insensitive, non-locale specific (binary)
		// compare last.

		int i = 0;
		int matchPos = -1;
		// Search using case-sensitive, non-locale specific (binary) compare.
		// If the user supplies a true match for the parameter name, we will
		// find it here.
		for (i = 0; i < l; i++) {
			String sParam = paramNames.get(i);
			sParam = sParam.substring(1, sParam.length());
			if (sParam.equals(columnName)) {
				matchPos = i;
				break;
			}
		}

		if (-1 == matchPos) {
			// Check for case-insensitive match using a non-locale aware method.
			// Use VM supplied String.equalsIgnoreCase to do the
			// "case-insensitive search".
			for (i = 0; i < l; i++) {
				String sParam = paramNames.get(i);
				sParam = sParam.substring(1, sParam.length());
				if (sParam.equalsIgnoreCase(columnName)) {
					matchPos = i;
					break;
				}
			}
		}

		if (-1 == matchPos) {
			MessageFormat form = new MessageFormat(
					SQLServerException.getErrString("R_parameterNotDefinedForProcedure"));
			Object[] msgArgs = { columnName, procedureName };
			SQLServerException.makeFromDriverError(connection, this, form.format(msgArgs), "07009", false);
		}
		// @RETURN_VALUE is always in the list. If the user uses return value
		// ?=call(@p1) syntax then
		// @p1 is index 2 otherwise its index 1.
		if (bReturnValueSyntax) // 3.2717
			return matchPos + 1;
		else
			return matchPos;
	}

	public void setTimestamp(String sCol, java.sql.Timestamp x, Calendar c) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTimeStamp", new Object[] { sCol, x, c });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIMESTAMP, x, JavaType.TIMESTAMP, c, false);
		loggerExternal.exiting(getClassNameLogging(), "setTimeStamp");
	}

	public void setTimestamp(String sCol, java.sql.Timestamp x, Calendar c, boolean forceEncrypt)
			throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTimeStamp", new Object[] { sCol, x, c, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIMESTAMP, x, JavaType.TIMESTAMP, c, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setTimeStamp");
	}

	public void setTime(String sCol, java.sql.Time x, Calendar c) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTime", new Object[] { sCol, x, c });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIME, x, JavaType.TIME, c, false);
		loggerExternal.exiting(getClassNameLogging(), "setTime");
	}

	public void setTime(String sCol, java.sql.Time x, Calendar c, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTime", new Object[] { sCol, x, c, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIME, x, JavaType.TIME, c, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setTime");
	}

	public void setDate(String sCol, java.sql.Date x, Calendar c) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDate", new Object[] { sCol, x, c });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DATE, x, JavaType.DATE, c, false);
		loggerExternal.exiting(getClassNameLogging(), "setDate");
	}

	public void setDate(String sCol, java.sql.Date x, Calendar c, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDate", new Object[] { sCol, x, c, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DATE, x, JavaType.DATE, c, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setDate");
	}

	public final void setCharacterStream(String parameterName, Reader reader) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setCharacterStream",
					new Object[] { parameterName, reader });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.CHARACTER, reader, JavaType.READER,
				DataTypes.UNKNOWN_STREAM_LENGTH);
		loggerExternal.exiting(getClassNameLogging(), "setCharacterStream");
	}

	public final void setCharacterStream(String parameterName, Reader value, int length) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setCharacterStream",
					new Object[] { parameterName, value, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.CHARACTER, value, JavaType.READER, length);
		loggerExternal.exiting(getClassNameLogging(), "setCharacterStream");
	}

	public final void setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {

		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setCharacterStream",
					new Object[] { parameterName, reader, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.CHARACTER, reader, JavaType.READER, length);
		loggerExternal.exiting(getClassNameLogging(), "setCharacterStream");
	}

	public final void setNCharacterStream(String parameterName, Reader value) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNCharacterStream",
					new Object[] { parameterName, value });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.NCHARACTER, value, JavaType.READER,
				DataTypes.UNKNOWN_STREAM_LENGTH);
		loggerExternal.exiting(getClassNameLogging(), "setNCharacterStream");
	}

	public final void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNCharacterStream",
					new Object[] { parameterName, value, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.NCHARACTER, value, JavaType.READER, length);
		loggerExternal.exiting(getClassNameLogging(), "setNCharacterStream");
	}

	public final void setClob(String parameterName, Clob x) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setClob", new Object[] { parameterName, x });
		checkClosed();
		setValue(findColumn(parameterName), JDBCType.CLOB, x, JavaType.CLOB, false);
		loggerExternal.exiting(getClassNameLogging(), "setClob");
	}

	public final void setClob(String parameterName, Reader reader) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setClob", new Object[] { parameterName, reader });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.CHARACTER, reader, JavaType.READER,
				DataTypes.UNKNOWN_STREAM_LENGTH);
		loggerExternal.exiting(getClassNameLogging(), "setClob");
	}

	public final void setClob(String parameterName, Reader value, long length) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setClob", new Object[] { parameterName, value, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.CHARACTER, value, JavaType.READER, length);
		loggerExternal.exiting(getClassNameLogging(), "setClob");
	}

	public final void setNClob(String parameterName, NClob value) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNClob", new Object[] { parameterName, value });
		checkClosed();
		setValue(findColumn(parameterName), JDBCType.NCLOB, value, JavaType.NCLOB, false);
		loggerExternal.exiting(getClassNameLogging(), "setNClob");
	}

	public final void setNClob(String parameterName, Reader reader) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNClob", new Object[] { parameterName, reader });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.NCHARACTER, reader, JavaType.READER,
				DataTypes.UNKNOWN_STREAM_LENGTH);
		loggerExternal.exiting(getClassNameLogging(), "setNClob");
	}

	public final void setNClob(String parameterName, Reader reader, long length) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNClob", new Object[] { parameterName, reader, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.NCHARACTER, reader, JavaType.READER, length);
		loggerExternal.exiting(getClassNameLogging(), "setNClob");
	}

	public final void setNString(String parameterName, String value) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNString", new Object[] { parameterName, value });
		checkClosed();
		setValue(findColumn(parameterName), JDBCType.NVARCHAR, value, JavaType.STRING, false);
		loggerExternal.exiting(getClassNameLogging(), "setNString");
	}

	public final void setNString(String parameterName, String value, boolean forceEncrypt) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNString",
					new Object[] { parameterName, value, forceEncrypt });
		checkClosed();
		setValue(findColumn(parameterName), JDBCType.NVARCHAR, value, JavaType.STRING, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setNString");
	}

	public void setObject(String sCol, Object o) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setObject", new Object[] { sCol, o });
		checkClosed();
		setObjectNoType(findColumn(sCol), o, false);
		loggerExternal.exiting(getClassNameLogging(), "setObject");
	}

	public void setObject(String sCol, Object o, int n) throws SQLServerException {
		String tvpName = null;
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setObject", new Object[] { sCol, o, n });
		checkClosed();
		if (microsoft.sql.Types.STRUCTURED == n) {
			tvpName = getTVPNameIfNull(findColumn(sCol), null);
			setObject(setterGetParam(findColumn(sCol)), o, JavaType.TVP, JDBCType.TVP, null, null, false,
					findColumn(sCol), tvpName);
		} else
			setObject(setterGetParam(findColumn(sCol)), o, JavaType.of(o), JDBCType.of(n), null, null, false,
					findColumn(sCol), tvpName);
		loggerExternal.exiting(getClassNameLogging(), "setObject");
	}

	public void setObject(String sCol, Object o, int n, int m) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setObject", new Object[] { sCol, o, n, m });
		checkClosed();

		setObject(setterGetParam(findColumn(sCol)), o, JavaType.of(o), JDBCType.of(n), m, null, false, findColumn(sCol),
				null);

		loggerExternal.exiting(getClassNameLogging(), "setObject");
	}

	public void setObject(String sCol, Object o, int n, int m, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setObject", new Object[] { sCol, o, n, m, forceEncrypt });
		checkClosed();

		// scale - for java.sql.Types.DECIMAL or java.sql.Types.NUMERIC types,
		// this is the number of digits after the decimal point.
		// For all other types, this value will be ignored.

		setObject(setterGetParam(findColumn(sCol)), o, JavaType.of(o), JDBCType.of(n),
				(java.sql.Types.NUMERIC == n || java.sql.Types.DECIMAL == n) ? Integer.valueOf(m) : null, null,
				forceEncrypt, findColumn(sCol), null);

		loggerExternal.exiting(getClassNameLogging(), "setObject");
	}

	public final void setObject(String sCol, Object x, int targetSqlType, Integer precision, int scale)
			throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setObject",
					new Object[] { sCol, x, targetSqlType, precision, scale });
		checkClosed();

		// scale - for java.sql.Types.DECIMAL or java.sql.Types.NUMERIC types,
		// this is the number of digits after the decimal point. For Java Object
		// types
		// InputStream and Reader, this is the length of the data in the stream
		// or reader.
		// For all other types, this value will be ignored.

		setObject(setterGetParam(findColumn(sCol)), x, JavaType.of(x), JDBCType.of(targetSqlType),
				(java.sql.Types.NUMERIC == targetSqlType || java.sql.Types.DECIMAL == targetSqlType
						|| InputStream.class.isInstance(x) || Reader.class.isInstance(x)) ? Integer.valueOf(scale)
								: null,
				precision, false, findColumn(sCol), null);

		loggerExternal.exiting(getClassNameLogging(), "setObject");
	}

	public final void setAsciiStream(String parameterName, InputStream x) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setAsciiStream", new Object[] { parameterName, x });
		DriverJDBCVersion.checkSupportsJDBC4();
		checkClosed();
		setStream(findColumn(parameterName), StreamType.ASCII, x, JavaType.INPUTSTREAM,
				DataTypes.UNKNOWN_STREAM_LENGTH);
		loggerExternal.exiting(getClassNameLogging(), "setAsciiStream");
	}

	public final void setAsciiStream(String parameterName, InputStream value, int length) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setAsciiStream",
					new Object[] { parameterName, value, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.ASCII, value, JavaType.INPUTSTREAM, length);
		loggerExternal.exiting(getClassNameLogging(), "setAsciiStream");
	}

	public final void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setAsciiStream", new Object[] { parameterName, x, length });
		DriverJDBCVersion.checkSupportsJDBC4();
		checkClosed();
		setStream(findColumn(parameterName), StreamType.ASCII, x, JavaType.INPUTSTREAM, length);
		loggerExternal.exiting(getClassNameLogging(), "setAsciiStream");
	}

	public final void setBinaryStream(String parameterName, InputStream x) throws SQLException {

		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBinaryStream", new Object[] { parameterName, x });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.BINARY, x, JavaType.INPUTSTREAM,
				DataTypes.UNKNOWN_STREAM_LENGTH);
		loggerExternal.exiting(getClassNameLogging(), "setBinaryStream");
	}

	public final void setBinaryStream(String parameterName, InputStream value, int length) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBinaryStream",
					new Object[] { parameterName, value, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.BINARY, value, JavaType.INPUTSTREAM, length);
		loggerExternal.exiting(getClassNameLogging(), "setBinaryStream");
	}

	public final void setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBinaryStream",
					new Object[] { parameterName, x, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.BINARY, x, JavaType.INPUTSTREAM, length);
		loggerExternal.exiting(getClassNameLogging(), "setBinaryStream");
	}

	public final void setBlob(String parameterName, Blob inputStream) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBlob", new Object[] { parameterName, inputStream });
		checkClosed();
		setValue(findColumn(parameterName), JDBCType.BLOB, inputStream, JavaType.BLOB, false);
		loggerExternal.exiting(getClassNameLogging(), "setBlob");
	}

	public final void setBlob(String parameterName, InputStream value) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();

		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBlob", new Object[] { parameterName, value });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.BINARY, value, JavaType.INPUTSTREAM,
				DataTypes.UNKNOWN_STREAM_LENGTH);
		loggerExternal.exiting(getClassNameLogging(), "setBlob");
	}

	public final void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBlob",
					new Object[] { parameterName, inputStream, length });
		checkClosed();
		setStream(findColumn(parameterName), StreamType.BINARY, inputStream, JavaType.INPUTSTREAM, length);
		loggerExternal.exiting(getClassNameLogging(), "setBlob");
	}

	public void setTimestamp(String sCol, java.sql.Timestamp t) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTimestamp", new Object[] { sCol, t });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIMESTAMP, t, JavaType.TIMESTAMP, false);
		loggerExternal.exiting(getClassNameLogging(), "setTimestamp");
	}

	public void setTimestamp(String sCol, java.sql.Timestamp t, int scale) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTimestamp", new Object[] { sCol, t });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIMESTAMP, t, JavaType.TIMESTAMP, null, scale, false);
		loggerExternal.exiting(getClassNameLogging(), "setTimestamp");
	}

	public void setTimestamp(String sCol, java.sql.Timestamp t, int scale, boolean forceEncrypt)
			throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTimestamp", new Object[] { sCol, t, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIMESTAMP, t, JavaType.TIMESTAMP, null, scale, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setTimestamp");
	}

	public void setDateTimeOffset(String sCol, microsoft.sql.DateTimeOffset t) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDateTimeOffset", new Object[] { sCol, t });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DATETIMEOFFSET, t, JavaType.DATETIMEOFFSET, false);
		loggerExternal.exiting(getClassNameLogging(), "setDateTimeOffset");
	}

	public void setDateTimeOffset(String sCol, microsoft.sql.DateTimeOffset t, int scale) throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDateTimeOffset", new Object[] { sCol, t });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DATETIMEOFFSET, t, JavaType.DATETIMEOFFSET, null, scale, false);
		loggerExternal.exiting(getClassNameLogging(), "setDateTimeOffset");
	}

	public void setDateTimeOffset(String sCol, microsoft.sql.DateTimeOffset t, int scale, boolean forceEncrypt)
			throws SQLException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDateTimeOffset", new Object[] { sCol, t, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DATETIMEOFFSET, t, JavaType.DATETIMEOFFSET, null, scale, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setDateTimeOffset");
	}

	public void setDate(String sCol, java.sql.Date d) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDate", new Object[] { sCol, d });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DATE, d, JavaType.DATE, false);
		loggerExternal.exiting(getClassNameLogging(), "setDate");
	}

	public void setTime(String sCol, java.sql.Time t) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTime", new Object[] { sCol, t });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIME, t, JavaType.TIME, false);
		loggerExternal.exiting(getClassNameLogging(), "setTime");
	}

	public void setTime(String sCol, java.sql.Time t, int scale) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTime", new Object[] { sCol, t });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIME, t, JavaType.TIME, null, scale, false);
		loggerExternal.exiting(getClassNameLogging(), "setTime");
	}

	public void setTime(String sCol, java.sql.Time t, int scale, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setTime", new Object[] { sCol, t, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TIME, t, JavaType.TIME, null, scale, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setTime");
	}

	public void setDateTime(String sCol, java.sql.Timestamp x) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDateTime", new Object[] { sCol, x });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DATETIME, x, JavaType.TIMESTAMP, false);
		loggerExternal.exiting(getClassNameLogging(), "setDateTime");
	}

	public void setDateTime(String sCol, java.sql.Timestamp x, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDateTime", new Object[] { sCol, x, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DATETIME, x, JavaType.TIMESTAMP, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setDateTime");
	}

	public void setSmallDateTime(String sCol, java.sql.Timestamp x) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setSmallDateTime", new Object[] { sCol, x });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.SMALLDATETIME, x, JavaType.TIMESTAMP, false);
		loggerExternal.exiting(getClassNameLogging(), "setSmallDateTime");
	}

	public void setSmallDateTime(String sCol, java.sql.Timestamp x, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setSmallDateTime", new Object[] { sCol, x, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.SMALLDATETIME, x, JavaType.TIMESTAMP, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setSmallDateTime");
	}

	public void setUniqueIdentifier(String sCol, String guid) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setUniqueIdentifier", new Object[] { sCol, guid });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.GUID, guid, JavaType.STRING, false);
		loggerExternal.exiting(getClassNameLogging(), "setUniqueIdentifier");
	}

	public void setUniqueIdentifier(String sCol, String guid, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setUniqueIdentifier",
					new Object[] { sCol, guid, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.GUID, guid, JavaType.STRING, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setUniqueIdentifier");
	}

	public void setBytes(String sCol, byte[] b) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBytes", new Object[] { sCol, b });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.BINARY, b, JavaType.BYTEARRAY, false);
		loggerExternal.exiting(getClassNameLogging(), "setBytes");
	}

	public void setBytes(String sCol, byte[] b, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBytes", new Object[] { sCol, b, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.BINARY, b, JavaType.BYTEARRAY, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setBytes");
	}

	public void setByte(String sCol, byte b) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setByte", new Object[] { sCol, b });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TINYINT, Byte.valueOf(b), JavaType.BYTE, false);
		loggerExternal.exiting(getClassNameLogging(), "setByte");
	}

	public void setByte(String sCol, byte b, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setByte", new Object[] { sCol, b, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TINYINT, Byte.valueOf(b), JavaType.BYTE, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setByte");
	}

	public void setString(String sCol, String s) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setString", new Object[] { sCol, s });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.VARCHAR, s, JavaType.STRING, false);
		loggerExternal.exiting(getClassNameLogging(), "setString");
	}

	public void setString(String sCol, String s, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setString", new Object[] { sCol, s, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.VARCHAR, s, JavaType.STRING, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setString");
	}

	public void setMoney(String sCol, BigDecimal bd) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setMoney", new Object[] { sCol, bd });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.MONEY, bd, JavaType.BIGDECIMAL, false);
		loggerExternal.exiting(getClassNameLogging(), "setMoney");
	}

	public void setMoney(String sCol, BigDecimal bd, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setMoney", new Object[] { sCol, bd, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.MONEY, bd, JavaType.BIGDECIMAL, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setMoney");
	}

	public void setSmallMoney(String sCol, BigDecimal bd) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setSmallMoney", new Object[] { sCol, bd });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.SMALLMONEY, bd, JavaType.BIGDECIMAL, false);
		loggerExternal.exiting(getClassNameLogging(), "setSmallMoney");
	}

	public void setSmallMoney(String sCol, BigDecimal bd, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setSmallMoney", new Object[] { sCol, bd, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.SMALLMONEY, bd, JavaType.BIGDECIMAL, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setSmallMoney");
	}

	public void setBigDecimal(String sCol, BigDecimal bd) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBigDecimal", new Object[] { sCol, bd });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DECIMAL, bd, JavaType.BIGDECIMAL, false);
		loggerExternal.exiting(getClassNameLogging(), "setBigDecimal");
	}

	public void setBigDecimal(String sCol, BigDecimal bd, int precision, int scale) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBigDecimal",
					new Object[] { sCol, bd, precision, scale });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DECIMAL, bd, JavaType.BIGDECIMAL, precision, scale, false);
		loggerExternal.exiting(getClassNameLogging(), "setBigDecimal");
	}

	public void setBigDecimal(String sCol, BigDecimal bd, int precision, int scale, boolean forceEncrypt)
			throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBigDecimal",
					new Object[] { sCol, bd, precision, scale, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DECIMAL, bd, JavaType.BIGDECIMAL, precision, scale, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setBigDecimal");
	}

	public void setDouble(String sCol, double d) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDouble", new Object[] { sCol, d });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DOUBLE, Double.valueOf(d), JavaType.DOUBLE, false);
		loggerExternal.exiting(getClassNameLogging(), "setDouble");
	}

	public void setDouble(String sCol, double d, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setDouble", new Object[] { sCol, d, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.DOUBLE, Double.valueOf(d), JavaType.DOUBLE, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setDouble");
	}

	public void setFloat(String sCol, float f) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setFloat", new Object[] { sCol, f });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.REAL, Float.valueOf(f), JavaType.FLOAT, false);
		loggerExternal.exiting(getClassNameLogging(), "setFloat");
	}

	public void setFloat(String sCol, float f, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setFloat", new Object[] { sCol, f, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.REAL, Float.valueOf(f), JavaType.FLOAT, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setFloat");
	}

	public void setInt(String sCol, int i) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setInt", new Object[] { sCol, i });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.INTEGER, Integer.valueOf(i), JavaType.INTEGER, false);
		loggerExternal.exiting(getClassNameLogging(), "setInt");
	}

	public void setInt(String sCol, int i, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setInt", new Object[] { sCol, i, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.INTEGER, Integer.valueOf(i), JavaType.INTEGER, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setInt");
	}

	public void setLong(String sCol, long l) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setLong", new Object[] { sCol, l });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.BIGINT, Long.valueOf(l), JavaType.LONG, false);
		loggerExternal.exiting(getClassNameLogging(), "setLong");
	}

	public void setLong(String sCol, long l, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setLong", new Object[] { sCol, l, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.BIGINT, Long.valueOf(l), JavaType.LONG, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setLong");
	}

	public void setShort(String sCol, short s) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setShort", new Object[] { sCol, s });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.SMALLINT, Short.valueOf(s), JavaType.SHORT, false);
		loggerExternal.exiting(getClassNameLogging(), "setShort");
	}

	public void setShort(String sCol, short s, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setShort", new Object[] { sCol, s, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.SMALLINT, Short.valueOf(s), JavaType.SHORT, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setShort");
	}

	public void setBoolean(String sCol, boolean b) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBoolean", new Object[] { sCol, b });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.BIT, Boolean.valueOf(b), JavaType.BOOLEAN, false);
		loggerExternal.exiting(getClassNameLogging(), "setBoolean");
	}

	public void setBoolean(String sCol, boolean b, boolean forceEncrypt) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setBoolean", new Object[] { sCol, b, forceEncrypt });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.BIT, Boolean.valueOf(b), JavaType.BOOLEAN, forceEncrypt);
		loggerExternal.exiting(getClassNameLogging(), "setBoolean");
	}

	public void setNull(String sCol, int nType) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNull", new Object[] { sCol, nType });
		checkClosed();
		setObject(setterGetParam(findColumn(sCol)), null, JavaType.OBJECT, JDBCType.of(nType), null, null, false,
				findColumn(sCol), null);
		loggerExternal.exiting(getClassNameLogging(), "setNull");
	}

	public void setNull(String sCol, int nType, String sTypeName) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setNull", new Object[] { sCol, nType, sTypeName });
		checkClosed();
		setObject(setterGetParam(findColumn(sCol)), null, JavaType.OBJECT, JDBCType.of(nType), null, null, false,
				findColumn(sCol), sTypeName);
		loggerExternal.exiting(getClassNameLogging(), "setNull");
	}

	public void setURL(String sCol, URL u) throws SQLServerException {
		loggerExternal.entering(getClassNameLogging(), "setURL", sCol);
		checkClosed();
		setURL(findColumn(sCol), u);
		loggerExternal.exiting(getClassNameLogging(), "setURL");
	}

	public final void setStructured(String sCol, String tvpName, SQLServerDataTable tvpDataTable)
			throws SQLServerException {
		tvpName = getTVPNameIfNull(findColumn(sCol), tvpName);
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setStructured",
					new Object[] { sCol, tvpName, tvpDataTable });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TVP, tvpDataTable, JavaType.TVP, tvpName);
		loggerExternal.exiting(getClassNameLogging(), "setStructured");
	}

	public final void setStructured(String sCol, String tvpName, ResultSet tvpResultSet) throws SQLServerException {
		tvpName = getTVPNameIfNull(findColumn(sCol), tvpName);
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setStructured",
					new Object[] { sCol, tvpName, tvpResultSet });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TVP, tvpResultSet, JavaType.TVP, tvpName);
		loggerExternal.exiting(getClassNameLogging(), "setStructured");
	}

	public final void setStructured(String sCol, String tvpName, ISQLServerDataRecord tvpDataRecord)
			throws SQLServerException {
		tvpName = getTVPNameIfNull(findColumn(sCol), tvpName);
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setStructured",
					new Object[] { sCol, tvpName, tvpDataRecord });
		checkClosed();
		setValue(findColumn(sCol), JDBCType.TVP, tvpDataRecord, JavaType.TVP, tvpName);
		loggerExternal.exiting(getClassNameLogging(), "setStructured");
	}

	public URL getURL(int n) throws SQLServerException {
		NotImplemented();
		return null;
	}

	public URL getURL(String s) throws SQLServerException {
		NotImplemented();
		return null;
	}

	public final void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "setSQLXML", new Object[] { parameterName, xmlObject });
		checkClosed();
		setSQLXMLInternal(findColumn(parameterName), xmlObject);
		loggerExternal.exiting(getClassNameLogging(), "setSQLXML");
	}

	public final SQLXML getSQLXML(int parameterIndex) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		loggerExternal.entering(getClassNameLogging(), "getSQLXML", parameterIndex);
		checkClosed();
		SQLServerSQLXML value = (SQLServerSQLXML) getSQLXMLInternal(parameterIndex);
		loggerExternal.exiting(getClassNameLogging(), "getSQLXML", value);
		return value;
	}

	public final SQLXML getSQLXML(String parameterName) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();
		loggerExternal.entering(getClassNameLogging(), "getSQLXML", parameterName);
		checkClosed();
		SQLServerSQLXML value = (SQLServerSQLXML) getSQLXMLInternal(findColumn(parameterName));
		loggerExternal.exiting(getClassNameLogging(), "getSQLXML", value);
		return value;
	}

	public final void setRowId(String parameterName, RowId x) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();

		// Not implemented
		throw new SQLFeatureNotSupportedException(SQLServerException.getErrString("R_notSupported"));
	}

	public final RowId getRowId(int parameterIndex) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();

		// Not implemented
		throw new SQLFeatureNotSupportedException(SQLServerException.getErrString("R_notSupported"));
	}

	public final RowId getRowId(String parameterName) throws SQLException {
		DriverJDBCVersion.checkSupportsJDBC4();

		// Not implemented
		throw new SQLFeatureNotSupportedException(SQLServerException.getErrString("R_notSupported"));
	}

	public void registerOutParameter(String s, int n, String s1) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "registerOutParameter",
					new Object[] { s, new Integer(n), s1 });
		checkClosed();
		registerOutParameter(findColumn(s), n, s1);
		loggerExternal.exiting(getClassNameLogging(), "registerOutParameter");
	}

	public void registerOutParameter(String parameterName, int sqlType, int scale) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "registerOutParameter",
					new Object[] { parameterName, new Integer(sqlType), new Integer(scale) });
		checkClosed();
		registerOutParameter(findColumn(parameterName), sqlType, scale);
		loggerExternal.exiting(getClassNameLogging(), "registerOutParameter");
	}

	public void registerOutParameter(String parameterName, int sqlType, int precision, int scale)
			throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "registerOutParameter",
					new Object[] { parameterName, new Integer(sqlType), new Integer(scale) });
		checkClosed();
		registerOutParameter(findColumn(parameterName), sqlType, precision, scale);
		loggerExternal.exiting(getClassNameLogging(), "registerOutParameter");
	}

	public void registerOutParameter(String s, int n) throws SQLServerException {
		if (loggerExternal.isLoggable(java.util.logging.Level.FINER))
			loggerExternal.entering(getClassNameLogging(), "registerOutParameter", new Object[] { s, new Integer(n) });
		checkClosed();
		registerOutParameter(findColumn(s), n);
		loggerExternal.exiting(getClassNameLogging(), "registerOutParameter");
	}

}

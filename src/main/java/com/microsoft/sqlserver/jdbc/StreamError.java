//---------------------------------------------------------------------------------------------------------------------------------
// File: StreamError.java
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

/**
 * StreamError represents a TDS error or message event.
 *
 * NOTE: Insure that this class is kept serializable because it is held by the
 * SQLServerException object which is required to be serializable.
 */

final class StreamError extends StreamPacket {
	/** the error message */
	String errorMessage = "";
	/** the error number */
	int errorNumber;
	/** the tds error state */
	int errorState;
	/** the tds error severity */
	int errorSeverity;

	String serverName;
	String procName;
	long lineNumber;

	final String getMessage() {
		return errorMessage;
	}

	final int getErrorNumber() {
		return errorNumber;
	}

	final int getErrorState() {
		return errorState;
	}

	final int getErrorSeverity() {
		return errorSeverity;
	}

	StreamError() {
		super(TDS.TDS_ERR);
	}

	void setFromTDS(TDSReader tdsReader) throws SQLServerException {
		if (TDS.TDS_ERR != tdsReader.readUnsignedByte())
			assert false;
		setContentsFromTDS(tdsReader);
	}

	void setContentsFromTDS(TDSReader tdsReader) throws SQLServerException {
		tdsReader.readUnsignedShort(); // token length (ignored)
		errorNumber = tdsReader.readInt();
		errorState = tdsReader.readUnsignedByte();
		errorSeverity = tdsReader.readUnsignedByte(); // matches
														// master.dbo.sysmessages
		errorMessage = tdsReader.readUnicodeString(tdsReader.readUnsignedShort());
		serverName = tdsReader.readUnicodeString(tdsReader.readUnsignedByte());
		procName = tdsReader.readUnicodeString(tdsReader.readUnsignedByte());
		lineNumber = tdsReader.readUnsignedInt();

	}
}

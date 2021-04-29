/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.streampipes.sinks.databases.jvm.jdbcclient;

import org.apache.streampipes.commons.exceptions.SpRuntimeException;
import org.apache.streampipes.logging.api.Logger;
import org.apache.streampipes.model.runtime.Event;
import org.apache.streampipes.model.schema.EventProperty;
import org.apache.streampipes.model.schema.EventPropertyNested;
import org.apache.streampipes.model.schema.EventPropertyPrimitive;
import org.apache.streampipes.model.schema.EventSchema;
import org.apache.streampipes.sinks.databases.jvm.jdbcclient.model.DbDataTypeFactory;
import org.apache.streampipes.sinks.databases.jvm.jdbcclient.model.ParameterInformation;
import org.apache.streampipes.sinks.databases.jvm.jdbcclient.model.SupportedDbEngines;
import org.apache.streampipes.sinks.databases.jvm.jdbcclient.utils.StatementUtils;
import org.apache.streampipes.vocabulary.XSD;

import java.sql.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class JdbcClient {
    private String allowedRegEx;

    protected String tableName;
    protected String user;
    protected String password;
    protected SupportedDbEngines dbEngine;

    protected boolean tableExists = false;

    protected Logger logger;

    protected Connection c = null;
    protected Statement st = null;
    protected PreparedStatement ps = null;

    /**
     * The list of properties extracted from the graph
     */
    protected EventSchema eventSchema;
    /**
     * The parameters in the prepared statement {@code ps} together with their index and data type
     */
    protected HashMap<String, ParameterInformation> parameters = new HashMap<>();

    /**
     * A wrapper class for all supported SQL data types (INT, BIGINT, FLOAT, DOUBLE, VARCHAR(255)).
     * If no matching type is found, it is interpreted as a String (VARCHAR(255))
     */


    public JdbcClient() {
    }

    protected void initializeJdbc(EventSchema eventSchema,
                                  String host,
                                  Integer port,
                                  String databaseName,
                                  String tableName,
                                  String user,
                                  String password,
                                  SupportedDbEngines dbEngine,
                                  boolean useSSL,
                                  Logger logger) throws SpRuntimeException {
        this.tableName = tableName;
        this.user = user;
        this.password = password;
        this.dbEngine = dbEngine;
        this.allowedRegEx = dbEngine.getAllowedRegex();
        this.logger = logger;
        this.eventSchema = eventSchema;
        try {
            Class.forName(this.dbEngine.getDriverName());
        } catch (ClassNotFoundException e) {
            throw new SpRuntimeException("Driver '" + this.dbEngine.getDriverName() + "' not found.");
        }

        if (useSSL) {
            connectWithSSL(host, port, databaseName);
        } else {
            connect(host, port, databaseName);
        }
    }


    /**
     * Connects to the HadoopFileSystem Server and initilizes {@link JdbcClient#c} and
     * {@link JdbcClient#st}
     *
     * @throws SpRuntimeException When the connection could not be established (because of a
     *                            wrong identification, missing database etc.)
     */
    private void connect(String host, int port, String databaseName) throws SpRuntimeException {
		String url = "jdbc:" + this.dbEngine.getUrlName() + "://" + host + ":" + port + "/";
        try {
            c = DriverManager.getConnection(url, user, password);
            ensureDatabaseExists(url, databaseName);
            ensureTableExists(url, databaseName);
        } catch (SQLException e) {
            throw new SpRuntimeException("Could not establish a connection with the server: " + e.getMessage());
        }
    }

    /**
     * WIP
     * @param host
     * @param port
     * @param databaseName
     * @throws SpRuntimeException
     */
    private void connectWithSSL(String host, int port, String databaseName) throws SpRuntimeException {
        String url = "jdbc:" + this.dbEngine.getUrlName() + "://" + host + ":" + port + "/" + databaseName + "?user=" + user + "&password=" + password + "&ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory&sslmode=require" ;
        try{
            c = DriverManager.getConnection(url);
            ensureDatabaseExists(url, databaseName);
            ensureTableExists(url, "");
        } catch (SQLException e ) {
            throw new SpRuntimeException("Could not establish a connection with the server: " + e.getMessage());
        }
    }


	/**
	 * If this method returns successfully a database with the given name exists on the server, specified by the url.
	 *
	 * @param url The JDBC url containing the needed information (e.g. "jdbc:iotdb://127.0.0.1:6667/")
	 * @param databaseName The name of the database that should exist
	 * @throws SpRuntimeException If the database does not exists and could not be created
	 */
	protected void ensureDatabaseExists(String url, String databaseName) throws SpRuntimeException {
		checkRegEx(databaseName, "databasename");

		try {
			// Checks whether the database already exists (using catalogs has not worked with postgres)
			st = c.createStatement();
			st.executeUpdate("CREATE DATABASE " + databaseName + ";");
			logger.info("Created new database '" + databaseName + "'");
		} catch (SQLException e1) {
			if (!e1.getSQLState().substring(0, 2).equals("42")) {
				throw new SpRuntimeException("Error while creating database: " + e1.getMessage());
			}
		}
		closeAll();
	}

	/**
	 * If this method returns successfully a table with the name in {@link JdbcClient#tableName} exists in the database
	 * with the given database name exists on the server, specified by the url.
	 *
	 * @param url The JDBC url containing the needed information (e.g. "jdbc:iotdb://127.0.0.1:6667/")
	 * @param databaseName The database in which the table should exist
	 * @throws SpRuntimeException If the table does not exist and could not be created
	 */
	protected void ensureTableExists(String url, String databaseName) throws SpRuntimeException {
		try {
			// Database should exist by now so we can establish a connection
			c = DriverManager.getConnection(url + databaseName, user, password);
			st = c.createStatement();
			ResultSet rs = c.getMetaData().getTables(null, null, tableName, null);
			if (rs.next()) {
				validateTable();
			} else {
				createTable();
			}
			tableExists = true;
			rs.close();
		} catch (SQLException e) {
			closeAll();
			throw new SpRuntimeException(e.getMessage());
		}
	}

    /**
     * Clears, fills and executes the saved prepared statement {@code ps} with the data found in
     * event. To fill in the values it calls {@link JdbcClient#fillPreparedStatement(Map)}.
     *
     * @param event Data to be saved in the SQL table
     * @throws SQLException       When the statement cannot be executed
     * @throws SpRuntimeException When the table name is not allowed or it is thrown
     *                            by {@link org.apache.streampipes.sinks.databases.jvm.jdbcclient.utils.StatementUtils#setValue(ParameterInformation, Object, PreparedStatement)}
     */
    private void executePreparedStatement(final Map<String, Object> event)
            throws SQLException, SpRuntimeException {
        checkConnected();
        if (ps != null) {
            ps.clearParameters();
        }
        fillPreparedStatement(event);
        ps.executeUpdate();
    }

	/**
	 * Prepares a statement for the insertion of values or the
	 *
	 * @param event The event which should be saved to the Postgres table
	 * @throws SpRuntimeException When there was an error in the saving process
	 */
	protected void save(final Event event) throws SpRuntimeException {
		//TODO: Add batch support (https://stackoverflow.com/questions/3784197/efficient-way-to-do-batch-inserts-with-jdbc)
		checkConnected();
		Map<String, Object> eventMap = event.getRaw();
		if (event == null) {
			throw new SpRuntimeException("event is null");
		}
		if (!tableExists) {
			// Creates the table
			createTable();
			tableExists = true;
		}
		try {
			executePreparedStatement(eventMap);
		} catch (SQLException e) {
			if (e.getSQLState().substring(0, 2).equals("42")) {
				// If the table does not exists (because it got deleted or something, will cause the error
				// code "42") we will try to create a new one. Otherwise we do not handle the exception.
				logger.warn("Table '" + tableName + "' was unexpectedly not found and gets recreated.");
				tableExists = false;
				createTable();
				tableExists = true;

				try {
					executePreparedStatement(eventMap);
				} catch (SQLException e1) {
					throw new SpRuntimeException(e1.getMessage());
				}
			} else {
				throw new SpRuntimeException(e.getMessage());
			}
		}
	}

    private void fillPreparedStatement(final Map<String, Object> event)
            throws SQLException, SpRuntimeException {
        fillPreparedStatement(event, "");
    }

    /**
     * Fills a prepared statement with the actual values base on {@link JdbcClient#parameters}. If
     * {@link JdbcClient#parameters} is empty or not complete (which should only happen once in the
     * begining), it calls {@link JdbcClient#generatePreparedStatement(Map)} to generate a new one.
     *
     * @param event
     * @param pre
     * @throws SQLException
     * @throws SpRuntimeException
     */
    private void fillPreparedStatement(final Map<String, Object> event, String pre)
            throws SQLException, SpRuntimeException {
        // checkConnected();
        //TODO: Possible error: when the event does not contain all objects of the parameter list
        for (Map.Entry<String, Object> pair : event.entrySet()) {
            String newKey = pre + pair.getKey();
            if (pair.getValue() instanceof Map) {
                // recursively extracts nested values
                fillPreparedStatement((Map<String, Object>) pair.getValue(), newKey + "_");
            } else {
                if (!parameters.containsKey(newKey)) {
                    //TODO: start the for loop all over again
                    generatePreparedStatement(event);
                }
                ParameterInformation p = parameters.get(newKey);
                StatementUtils.setValue(p, pair.getValue(), ps);
            }
        }
    }

    /**
     * Initializes the variables {@link JdbcClient#parameters} and {@link JdbcClient#ps}
     * according to the parameter event.
     *
     * @param event The event which is getting analyzed
     * @throws SpRuntimeException When the tablename is not allowed
     * @throws SQLException       When the prepareStatment cannot be evaluated
     */
    private void generatePreparedStatement(final Map<String, Object> event)
            throws SQLException, SpRuntimeException {
        // input: event
        // wanted: INSERT INTO test4321 ( randomString, randomValue ) VALUES ( ?,? );
        checkConnected();
        parameters.clear();
        StringBuilder statement1 = new StringBuilder("INSERT INTO ");
        StringBuilder statement2 = new StringBuilder("VALUES ( ");
        checkRegEx(tableName, "Tablename");
        statement1.append(tableName).append(" ( ");

        // Starts index at 1, since the parameterIndex in the PreparedStatement starts at 1 as well
        extendPreparedStatement(event, statement1, statement2, 1);

        statement1.append(" ) ");
        statement2.append(" );");
        String finalStatement = statement1.append(statement2).toString();
        ps = c.prepareStatement(finalStatement);
    }

    private int extendPreparedStatement(final Map<String, Object> event,
                                        StringBuilder s1, StringBuilder s2, int index) throws SpRuntimeException {
        return extendPreparedStatement(event, s1, s2, index, "", "");
    }

    /**
     * @param event
     * @param s1
     * @param s2
     * @param index
     * @param preProperty
     * @param pre
     * @return
     * @throws SpRuntimeException
     */
    private int extendPreparedStatement(final Map<String, Object> event,
                                        StringBuilder s1, StringBuilder s2, int index, String preProperty, String pre)
            throws SpRuntimeException {
        checkConnected();
        for (Map.Entry<String, Object> pair : event.entrySet()) {
            if (pair.getValue() instanceof Map) {
                index = extendPreparedStatement((Map<String, Object>) pair.getValue(), s1, s2, index,
                        pair.getKey() + "_", pre);
            } else {
                checkRegEx(pair.getKey(), "Columnname");
                parameters.put(pair.getKey(), new ParameterInformation(index, DbDataTypeFactory.getFromObject(pair.getValue(), dbEngine)));
                s1.append(pre).append("\"").append(preProperty).append(pair.getKey()).append("\"");
                s2.append(pre).append("?");
                index++;
            }
            pre = ", ";
        }
        return index;
    }

    /**
     * Creates a table with the name {@link JdbcClient#tableName} and the
     * properties {@link JdbcClient#eventSchema}. Calls
     * {@link JdbcClient#extractEventProperties(List)} internally with the
     * {@link JdbcClient#eventSchema} to extract all possible columns.
     *
     * @throws SpRuntimeException If the {@link JdbcClient#tableName}  is not allowed, if
     *                            executeUpdate throws an SQLException or if {@link JdbcClient#extractEventProperties(List)}
     *                            throws an exception
     */
	protected void createTable() throws SpRuntimeException {
        checkConnected();
        checkRegEx(tableName, "Tablename");

        StringBuilder statement = new StringBuilder("CREATE TABLE \"");
        statement.append(tableName).append("\" ( ");
        statement.append(extractEventProperties(eventSchema.getEventProperties())).append(" );");

        try {
            st.executeUpdate(statement.toString());
        } catch (SQLException e) {
            throw new SpRuntimeException(e.getMessage());
        }
    }

    /**
     * Creates a SQL-Query with the given Properties (SQL-Injection safe). Calls
     * {@link JdbcClient#extractEventProperties(List, String)} with an empty string
     *
     * @param properties The list of properties which should be included in the query
     * @return A StringBuilder with the query which needs to be executed in order to create the table
     * @throws SpRuntimeException See {@link JdbcClient#extractEventProperties(List)} for details
     */
    private StringBuilder extractEventProperties(List<EventProperty> properties)
            throws SpRuntimeException {
        return extractEventProperties(properties, "");
    }

    /**
     * Creates a SQL-Query with the given Properties (SQL-Injection safe). For nested properties it
     * recursively extracts the information. EventPropertyList are getting converted to a string (so
     * in SQL to a VARCHAR(255)). For each type it uses {@link DbDataTypeFactory#getFromUri(String, SupportedDbEngines)}
     * internally to identify the SQL-type from the runtimeType.
     *
     * @param properties  The list of properties which should be included in the query
     * @param preProperty A string which gets prepended to all property runtimeNames
     * @return A StringBuilder with the query which needs to be executed in order to create the table
     * @throws SpRuntimeException If the runtimeName of any property is not allowed
     */
    private StringBuilder extractEventProperties(List<EventProperty> properties, String preProperty)
            throws SpRuntimeException {
        // output: "randomString VARCHAR(255), randomValue INT"
        StringBuilder s = new StringBuilder();
        String pre = "";
        for (EventProperty property : properties) {
            // Protection against SqlInjection

            checkRegEx(property.getRuntimeName(), "Column name");
            if (property instanceof EventPropertyNested) {
                // if it is a nested property, recursively extract the needed properties
                StringBuilder tmp = extractEventProperties(((EventPropertyNested) property).getEventProperties(),
                        preProperty + property.getRuntimeName() + "_");
                if (tmp.length() > 0) {
                    s.append(pre).append(tmp);
                }
            } else {
                // Adding the name of the property (e.g. "randomString")
                // Or for properties in a nested structure: input1_randomValue
                // "pre" is there for the ", " part
                s.append(pre).append("\"").append(preProperty).append(property.getRuntimeName()).append("\" ");

                // adding the type of the property (e.g. "VARCHAR(255)")
                if (property instanceof EventPropertyPrimitive) {
                    s.append(DbDataTypeFactory.getFromUri(((EventPropertyPrimitive) property).getRuntimeType(), dbEngine));
                } else {
                    // Must be an EventPropertyList then
                    s.append(DbDataTypeFactory.getFromUri(XSD._string.toString(), dbEngine));
                }
            }
            pre = ", ";
        }

        return s;
    }

    /**
     * Checks if the input string is allowed (regEx match and length > 0)
     *
     * @param input String which is getting matched with the regEx
     * @param regExIdentifier Information about the use of the input. Gets included in the exception message
     * @throws SpRuntimeException If {@code input} does not match with {@link JdbcClient#allowedRegEx}
     *                            or if the length of {@code input} is 0
     */
    protected final void checkRegEx(String input, String regExIdentifier) throws SpRuntimeException {
        if (!input.matches(allowedRegEx) || input.length() == 0) {
            throw new SpRuntimeException(regExIdentifier + " '" + input
                    + "' not allowed (allowed: '" + allowedRegEx + "') with a min length of 1");
        }
    }

    protected void validateTable() throws SpRuntimeException {
        //TODO: Add validation of an existing table
        if (false) {
            throw new SpRuntimeException("Table '" + tableName + "' does not match the eventproperties");
        }
    }

    /**
     * Closes all open connections and statements of JDBC
     */
    protected void closeAll() {
        boolean error = false;
        try {
            if (st != null) {
                st.close();
                st = null;
            }
        } catch (SQLException e) {
            error = true;
            logger.warn("Exception when closing the statement: " + e.getMessage());
        }
        try {
            if (c != null) {
                c.close();
                c = null;
            }
        } catch (SQLException e) {
            error = true;
            logger.warn("Exception when closing the connection: " + e.getMessage());
        }
        try {
            if (ps != null) {
                ps.close();
                ps = null;
            }
        } catch (SQLException e) {
            error = true;
            logger.warn("Exception when closing the prepared statement: " + e.getMessage());
        }
        if (!error) {
            logger.info("Shutdown all connections successfully.");
        }
    }

    protected void checkConnected() throws SpRuntimeException {
        if (c == null) {
            throw new SpRuntimeException("Connection is not established.");
        }
    }
}

package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_OPERATION;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JDBCDecorator extends DatabaseClientDecorator<DBInfo> {

  private static final Logger log = LoggerFactory.getLogger(JDBCDecorator.class);

  public static final JDBCDecorator DECORATE = new JDBCDecorator();
  private static final String UTF8 = StandardCharsets.UTF_8.toString();
  public static final String W3C_CONTEXT_VERSION = "00";
  public static final CharSequence JAVA_JDBC = UTF8BytesString.create("java-jdbc");
  public static final CharSequence DATABASE_QUERY = UTF8BytesString.create("database.query");
  private static final UTF8BytesString DB_QUERY = UTF8BytesString.create("DB Query");
  private static final UTF8BytesString JDBC_STATEMENT =
      UTF8BytesString.create("java-jdbc-statement");
  private static final UTF8BytesString JDBC_PREPARED_STATEMENT =
      UTF8BytesString.create("java-jdbc-prepared_statement");

  public static final String SQL_COMMENT_INJECTION_STATIC = "service";
  public static final String SQL_COMMENT_INJECTION_FULL = "full";

  public static final String SQL_COMMENT_INJECTION_MODE = Config.get().getSqlCommentInjectionMode();

  public static void logMissingQueryInfo(Statement statement) throws SQLException {
    if (log.isDebugEnabled()) {
      log.debug(
          "No query info in {} with {}",
          statement.getClass(),
          statement.getConnection().getClass());
    }
  }

  public static void logQueryInfoInjection(
      Connection connection, Statement statement, DBQueryInfo info) {
    if (log.isDebugEnabled()) {
      log.debug(
          "injected {} into {} from {}",
          info.getSql(),
          statement.getClass(),
          connection.getClass());
    }
  }

  public static void logSQLException(SQLException ex) {
    if (log.isDebugEnabled()) {
      log.debug("JDBC instrumentation error", ex);
    }
  }

  public static void logException(Exception ex) {
    if (log.isDebugEnabled()) {
      log.debug("JDBC instrumentation error", ex);
    }
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[]{"jdbc"};
  }

  @Override
  protected String service() {
    return "jdbc"; // Overridden by onConnection
  }

  @Override
  protected CharSequence component() {
    return JAVA_JDBC; // Overridden by onStatement and onPreparedStatement
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SQL;
  }

  @Override
  protected String dbType() {
    return "jdbc";
  }

  @Override
  protected String dbUser(final DBInfo info) {
    return info.getUser();
  }

  @Override
  protected String dbInstance(final DBInfo info) {
    if (info.getInstance() != null) {
      return info.getInstance();
    } else {
      return info.getDb();
    }
  }

  @Override
  protected String dbHostname(final DBInfo info) {
    return info.getHost();
  }

  public AgentSpan onConnection(
      final AgentSpan span,
      final Connection connection,
      ContextStore<Connection, DBInfo> contextStore) {
    DBInfo dbInfo = contextStore.get(connection);
    /*
     * Logic to get the DBInfo from a JDBC Connection, if the connection was not created via
     * Driver.connect, or it has never seen before, the connectionInfo map will return null and will
     * attempt to extract DBInfo from the connection. If the DBInfo can't be extracted, then the
     * connection will be stored with the DEFAULT DBInfo as the value in the connectionInfo map to
     * avoid retry overhead.
     */
    {
      if (dbInfo == null) {
        // first look for injected DBInfo in wrapped delegates
        Connection conn = connection;
        Set<Connection> connections = new HashSet<>();
        connections.add(conn);
        try {
          while (dbInfo == null) {
            Connection delegate = conn.unwrap(Connection.class);
            if (delegate == null || !connections.add(delegate)) {
              // cycle detected, stop looking
              break;
            }
            dbInfo = contextStore.get(delegate);
            conn = delegate;
          }
        } catch (Throwable ignore) {
        }
        if (dbInfo == null) {
          // couldn't find DBInfo anywhere, so fall back to default
          try {
            final DatabaseMetaData metaData = connection.getMetaData();
            final String url = metaData.getURL();
            if (url != null) {
              try {
                dbInfo = JDBCConnectionUrlParser.extractDBInfo(url, connection.getClientInfo());
              } catch (final Throwable ex) {
                // getClientInfo is likely not allowed.
                dbInfo = JDBCConnectionUrlParser.extractDBInfo(url, null);
              }
            } else {
              dbInfo = DBInfo.DEFAULT;
            }
          } catch (final SQLException se) {
            dbInfo = DBInfo.DEFAULT;
          }
        }
        // store the DBInfo on the outermost connection instance to avoid future searches
        contextStore.put(connection, dbInfo);
      }
    }

    if (dbInfo != null) {
      processDatabaseType(span, dbInfo.getType());
    }
    return super.onConnection(span, dbInfo);
  }

  public AgentSpan onStatement(AgentSpan span, DBQueryInfo dbQueryInfo) {
    log.debug("DB query info stmt sql: " + dbQueryInfo.getSql().toString());
    return withQueryInfo(span, dbQueryInfo, JDBC_STATEMENT);
  }

  public AgentSpan onPreparedStatement(AgentSpan span, DBQueryInfo dbQueryInfo) {
    log.debug("DB query info on prepared sql: " + dbQueryInfo.getSql().toString());
    return withQueryInfo(span, dbQueryInfo, JDBC_PREPARED_STATEMENT);
  }

  private AgentSpan withQueryInfo(AgentSpan span, DBQueryInfo info, CharSequence component) {
    if (null != info) {
      span.setResourceName(info.getSql());
      span.setTag(DB_OPERATION, info.getOperation());
    } else {
      span.setResourceName(DB_QUERY);
    }
    return span.setTag(Tags.COMPONENT, component);
  }

  /**
   * For customers who elect to enable SQL comment injection
   */
  public boolean injectSQLComment() {
    return SQL_COMMENT_INJECTION_MODE.equals(SQL_COMMENT_INJECTION_FULL)
        || SQL_COMMENT_INJECTION_MODE.equals(SQL_COMMENT_INJECTION_STATIC);
  }

  /**
   * toComment takes a map of tags and creates a new sql comment using the sqlcommenter spec. This
   * is used to inject APM tags into sql statements for APM<->DBM linking
   *
   * @param tags
   * @return String
   */
  public String toComment(final SortedMap<String, Object> tags) {
    final List<String> keyValuePairsList =
        tags.entrySet().stream()
            .filter(entry -> !isBlank(entry.getValue()))
            .map(
                entry -> {
                  try {
                    return String.format(
                        "%s='%s'",
                        urlEncode(entry.getKey()),
                        urlEncode(String.format("%s", entry.getValue())));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.toList());

    return String.join(",", keyValuePairsList);
  }

  public String augmentSQLStatement(final String sqlStmt, final SortedMap<String, Object> tags) {
    if (sqlStmt == null || sqlStmt.isEmpty() || tags.isEmpty()) {
      return sqlStmt;
    }

    // If the SQL already has a comment, just return it.
    if (hasSQLComment(sqlStmt)) {
      return sqlStmt;
    }
    String commentStr = toComment(tags);
    if (commentStr.isEmpty()) {
      return sqlStmt;
    }
    // Otherwise, now insert the fields and format.
    return String.format("%s /*%s*/", sqlStmt, commentStr);
  }

  private boolean isBlank(Object obj) {
    if (obj == null) {
      return true;
    }
    if (obj instanceof String) {
      return obj == "";
    }
    if (obj instanceof Number) {
      Number number = (Number) obj;
      return number.doubleValue() == 0.0;
    }
    return false;
  }

  private static String urlEncode(String s) throws Exception {
    return URLEncoder.encode(s, UTF8);
  }

  public SortedMap<String, Object> sortedKeyValuePairs(AgentSpan span, boolean withTraceContext) {
    SortedMap<String, Object> sortedMap = new TreeMap<>();
    sortedMap.put("ddps", span.getServiceName());
    sortedMap.put("dddbs", span.getTag(Tags.DB_INSTANCE));
    sortedMap.put("dde", span.getTag(Tags.DD_ENV));
    sortedMap.put("ddpv", span.getTag(Tags.DD_VERSION));
    if (withTraceContext) {
      sortedMap.put("traceparent", traceParent(span));
    }
    return sortedMap;
  }

  private String traceParent(AgentSpan span) {
    return String.format(
        "%s-%s-%s-%02X",
        W3C_CONTEXT_VERSION, span.getTraceId(), span.getSpanId(), span.getSamplingPriority());
  }

  private boolean hasSQLComment(String stmt) {
    return stmt != null && !stmt.isEmpty() && (stmt.contains("--") || stmt.contains("/*"));
  }
}

import java.sql.*;
/** Read-only JDBC helper used by the verification scripts (no psql needed):
 *  java -cp postgresql.jar SqlQuery.java PORT DATABASE USER PASSWORD "SQL" ro|rw */
public class SqlQuery {
  public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:"+a[0]+"/"+a[1], a[2], a[3]);
         Statement s = c.createStatement()) {
      c.setReadOnly(a[5].equals("ro"));
      boolean rs = s.execute(a[4]);
      if (!rs) { System.out.println("updated=" + s.getUpdateCount()); return; }
      ResultSet r = s.getResultSet(); int n = r.getMetaData().getColumnCount();
      while (r.next()) { StringBuilder b = new StringBuilder(); for (int i=1;i<=n;i++) b.append(r.getString(i)).append(i<n?" | ":""); System.out.println(b); }
    }
  }
}

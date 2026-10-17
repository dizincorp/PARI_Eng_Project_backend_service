package pl.pollub.pari.support;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for an in-memory H2 database. It is deliberately separate from the Spring context: the real schema needs
 * PostgreSQL + PostGIS (see {@link DbTestBase}) and cannot run on H2.
 */
class H2ConnectionTest {

    private static final String URL = "jdbc:h2:mem:pari_test;DB_CLOSE_DELAY=-1";

    @Test
    void createsTestTableAndReadsRowBackOverConnection() throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table connection_check (id int primary key, name varchar(50) not null)");

            try (PreparedStatement insert = connection.prepareStatement("insert into connection_check values (?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "hello h2");
                insert.executeUpdate();
            }

            try (ResultSet rs = statement.executeQuery("select name from connection_check where id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("hello h2");
            }
        }
    }
}

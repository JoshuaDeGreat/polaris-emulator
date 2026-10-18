package com.eu.habbo.habbohotel.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.eu.habbo.database.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigatorCategoryRoomQueryIT {
    @Test
    void categoryBrowsingIncludesColdEmptyRoomsAndPreservesVisibilityAndLimits() throws Exception {
        assumeTrue(TestDatabase.dockerAvailable(), "MariaDB integration test requires Docker");
        try (HikariDataSource database = TestDatabase.freshDatabase("navigator_saved_categories");
                Connection connection = database.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE rooms (id INT PRIMARY KEY, category INT NOT NULL, "
                        + "is_public ENUM('0','1') NOT NULL, state VARCHAR(16) NOT NULL, users INT NOT NULL)");
                statement.execute("INSERT INTO rooms VALUES "
                        + "(1,9,'0','open',0),"       // empty room, never loaded in the server cache
                        + "(2,9,'0','open',3),"       // occupied room is still ranked first
                        + "(3,9,'0','invisible',50)," // excluded before applying the limit
                        + "(4,9,'1','open',50),"      // public rooms belong in Public
                        + "(5,8,'0','open',0),"      // another category
                        + "(6,9,'0','locked',0),"    // doorbell/password rooms remain discoverable
                        + "(7,9,'0','password',0)");
            }
            assertEquals(List.of(2,7,6,1), NavigatorCategoryRoomQuery.findIds(connection,9,10,false));
            assertEquals(List.of(2,7), NavigatorCategoryRoomQuery.findIds(connection,9,2,false));
            assertEquals(List.of(3,2,7,6,1), NavigatorCategoryRoomQuery.findIds(connection,9,10,true));
            assertEquals(List.of(5), NavigatorCategoryRoomQuery.findIds(connection,8,10,false));
            assertEquals(List.of(), NavigatorCategoryRoomQuery.findIds(connection,10,10,false));
            assertEquals(List.of(), NavigatorCategoryRoomQuery.findIds(connection,9,0,false));
            assertEquals(List.of(), NavigatorCategoryRoomQuery.findIds(connection,9,-1,false));
        }
    }
}

package org.thingai.app.meo.util;

import org.thingai.base.dao.Dao;
import org.thingai.base.dao.annotations.DaoColumn;
import org.thingai.base.dao.annotations.DaoTable;

@DaoTable(name = "dao_kv", version = 1)
public class DaoKvUtil {
    @DaoColumn(primaryKey = true, nullable = false)
    private String key;
    @DaoColumn
    private String value;

    public static String get(Dao dao, String key) {
        DaoKvUtil[] rows = dao.query(DaoKvUtil.class, "key", key);
        return rows != null && rows.length > 0 ? rows[0].value : null;
    }

    public static void put(Dao dao, String key, String value) {
        DaoKvUtil row = new DaoKvUtil();
        row.key = key;
        row.value = value;
        dao.insertOrUpdate(row);
    }

    public static void remove(Dao dao, String key) {
        // Not dao.delete(Class, id): DaoSqlite hardcodes the column name "id" there.
        dao.deleteByColumn(DaoKvUtil.class, "key", key);
    }
}

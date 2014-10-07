package com.orm;

import android.content.Context;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;
import com.orm.dsl.Column;
import com.orm.dsl.NotNull;
import com.orm.dsl.Unique;
import com.orm.util.NamingHelper;
import com.orm.util.NumberComparator;
import com.orm.util.QueryBuilder;
import com.orm.util.ReflectionUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SchemaGenerator {

    protected Context context;
    private List<Class> domainClasses;

    public SchemaGenerator(Context context) {
        this.context = context;
    }

    private List<Class> getDomainClasses() {
      if (domainClasses == null) {
        domainClasses = ReflectionUtil.getDomainClasses(context);
      }
      return domainClasses;
    }

    public void addDomainClass(Class<? extends SugarRecord> domainClass) {
        getDomainClasses().add(domainClass);
    }

    public void createDatabase(SQLiteDatabase sqLiteDatabase) {
        for (Class domain : getDomainClasses()) {
            createTable(domain, sqLiteDatabase);
        }
    }

    public void doUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        for (Class domain : getDomainClasses()) {
            try {  // we try to do a select, if fails then (?) there isn't the table
                sqLiteDatabase.query(NamingHelper.toSQLName(domain), null, null, null, null, null, null);
            } catch (SQLiteException e) {
                Log.i("Sugar", String.format("Creating table on update (error was '%s')",
                        e.getMessage()));
                createTable(domain, sqLiteDatabase);
            }
        }
        executeSugarUpgrade(sqLiteDatabase, oldVersion, newVersion);
    }

    public void deleteTables(SQLiteDatabase sqLiteDatabase) {
        for (Class table : getDomainClasses()) {
            sqLiteDatabase.execSQL("DROP TABLE IF EXISTS " + NamingHelper.toSQLName(table));
        }
    }

    private boolean executeSugarUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        boolean isSuccess = false;

        List<String> files = getMigrationFiles();
        Collections.sort(files, new NumberComparator());
        for (String file : files) {
            Log.i("Sugar", "filename : " + file);

            try {
                int version = Integer.valueOf(file.replace(".sql", ""));

                if ((version > oldVersion) && (version <= newVersion)) {
                    executeScript(db, file);
                    isSuccess = true;
                }
            } catch (NumberFormatException e) {
                Log.i("Sugar", "not a sugar script. ignored." + file);
            }
        }

        return isSuccess;
    }

    protected List<String> getMigrationFiles() {
        try {
            return Arrays.asList(this.context.getAssets().list("sugar_upgrades"));
        } catch (IOException e) {
            Log.e("Sugar", e.getMessage());
            return new ArrayList<String>();
        }
    }

    private void executeScript(SQLiteDatabase db, String file) {
        try {
            InputStream is = getMigrationFileInputStream(file);
            if (is == null) {
                Log.i("Sugar", "not a sugar script. ignored." + file);
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i("Sugar script", line);
                db.execSQL(line.toString());
            }
        } catch (IOException e) {
            Log.e("Sugar", e.getMessage());
        }

        Log.i("Sugar", "Script executed");
    }

    protected InputStream getMigrationFileInputStream(String file) {
        try {
            return this.context.getAssets().open("sugar_upgrades/" + file);
        } catch (IOException e) {
            Log.e("Sugar", e.getMessage());
            return null;
        }
      }

    private void createTable(Class<?> table, SQLiteDatabase sqLiteDatabase) {
        Log.i("Sugar", "Create table");
        List<Field> fields = ReflectionUtil.getTableFields(table);
        String tableName = NamingHelper.toSQLName(table);
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        sb.append(tableName).append(" ( ID INTEGER PRIMARY KEY AUTOINCREMENT ");

        for (Field column : fields) {
            String columnName = NamingHelper.toSQLName(column);
            String columnType = QueryBuilder.getColumnType(column.getType());

            if (columnType != null) {
                if (columnName.equalsIgnoreCase("Id")) {
                    continue;
                }

                if (column.isAnnotationPresent(Column.class)) {
                    Column columnAnnotation = column.getAnnotation(Column.class);
                    columnName = columnAnnotation.name();

                    sb.append(", ").append(columnName).append(" ").append(columnType);

                    if (columnAnnotation.notNull()) {
                        if (columnType.endsWith(" NULL")) {
                            sb.delete(sb.length() - 5, sb.length());
                        }
                        sb.append(" NOT NULL");
                    }

                    if (columnAnnotation.unique()) {
                        sb.append(" UNIQUE");
                    }

                } else {
                    sb.append(", ").append(columnName).append(" ").append(columnType);

                    if (column.isAnnotationPresent(NotNull.class)) {
                        if (columnType.endsWith(" NULL")) {
                            sb.delete(sb.length() - 5, sb.length());
                        }
                        sb.append(" NOT NULL");
                    }

                    if (column.isAnnotationPresent(Unique.class)) {
                        sb.append(" UNIQUE");
                    }
                }
            }
        }

        sb.append(" ) ");
        Log.i("Sugar", "Creating table " + tableName);

        if (!"".equals(sb.toString())) {
            try {
                sqLiteDatabase.execSQL(sb.toString());
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

}

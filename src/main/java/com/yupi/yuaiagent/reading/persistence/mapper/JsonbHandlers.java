package com.yupi.yuaiagent.reading.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.*;
import java.util.*;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

/**
 * Bind PostgreSQL jsonb explicitly rather than sending JSON as varchar.
 */
public final class JsonbHandlers {
    private JsonbHandlers() {
    }

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public abstract static class Jsonb<T> extends BaseTypeHandler<T> {
        private final TypeReference<T> type;

        protected Jsonb(TypeReference<T> type) {
            this.type = type;
        }

        @Override
        public void setNonNullParameter(PreparedStatement ps, int index, T value, JdbcType jdbcType) throws SQLException {
            try {
                var object = new PGobject();
                object.setType("jsonb");
                object.setValue(JSON.writeValueAsString(value));
                ps.setObject(index, object);
            } catch (Exception e) {
                throw new SQLException("数据库JSON写入失败", e);
            }
        }

        private T read(String raw) throws SQLException {
            if (raw == null) return null;
            try {
                return JSON.readValue(raw, type);
            } catch (Exception e) {
                throw new SQLException("数据库JSON无效", e);
            }
        }

        @Override
        public T getNullableResult(ResultSet rs, String column) throws SQLException {
            return read(rs.getString(column));
        }

        @Override
        public T getNullableResult(ResultSet rs, int column) throws SQLException {
            return read(rs.getString(column));
        }

        @Override
        public T getNullableResult(CallableStatement cs, int column) throws SQLException {
            return read(cs.getString(column));
        }
    }

    public static class SettingsHandler extends Jsonb<Settings> {
        public SettingsHandler() {
            super(new TypeReference<>() {
            });
        }
    }

    public static class SourcesHandler extends Jsonb<List<Source>> {
        public SourcesHandler() {
            super(new TypeReference<>() {
            });
        }
    }

    public static class EventsHandler extends Jsonb<List<Event>> {
        public EventsHandler() {
            super(new TypeReference<>() {
            });
        }
    }

}

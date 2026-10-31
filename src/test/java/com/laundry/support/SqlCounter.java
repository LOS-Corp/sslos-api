package com.laundry.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the SQL statements the application prepares, by wrapping the DataSource (tests only).
 * Used to measure N+1 patterns: statements per API call, not per row. Only statements of the thread
 * that called {@link #reset()} are counted, so scheduled jobs running meanwhile do not distort it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SqlCounter {

    private static final AtomicLong STATEMENTS = new AtomicLong();
    private static final List<String> SQL = new CopyOnWriteArrayList<>();
    private static volatile Thread owner;
    private static final Set<String> COUNTED = Set.of("prepareStatement", "prepareCall", "createStatement");

    public static void reset() {
        owner = Thread.currentThread();
        STATEMENTS.set(0);
        SQL.clear();
    }

    /** The statements prepared since the last {@link #reset()}, first line only. */
    public static List<String> statements() {
        return List.copyOf(SQL);
    }

    public static long count() {
        return STATEMENTS.get();
    }

    @Bean
    static BeanPostProcessor countingDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource && !(bean instanceof Counting)
                        ? new Counting(dataSource) : bean;
            }
        };
    }

    private static final class Counting extends DelegatingDataSource {

        Counting(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return counted(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return counted(super.getConnection(username, password));
        }

        private static Connection counted(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (COUNTED.contains(method.getName()) && Thread.currentThread() == owner) {
                            STATEMENTS.incrementAndGet();
                            if (args != null && args.length > 0 && args[0] instanceof String sql) {
                                SQL.add(sql.strip().replaceAll("\s+", " "));
                            }
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}

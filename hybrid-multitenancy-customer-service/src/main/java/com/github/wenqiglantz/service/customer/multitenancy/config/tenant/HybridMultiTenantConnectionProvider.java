package com.github.wenqiglantz.service.customer.multitenancy.config.tenant;

import com.github.wenqiglantz.service.customer.multitenancy.Tenant;
import com.github.wenqiglantz.service.customer.multitenancy.TenantContext;
import com.github.wenqiglantz.service.customer.multitenancy.TenantRepository;
import com.github.wenqiglantz.service.customer.util.EncryptionService;
import com.github.wenqiglantz.service.customer.multitenancy.TenantConstants;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.hibernate.engine.jdbc.connections.spi.AbstractDataSourceBasedMultiTenantConnectionProviderImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Slf4j
@Component("hybridMultiTenantConnectionProvider")
public class HybridMultiTenantConnectionProvider
        extends AbstractDataSourceBasedMultiTenantConnectionProviderImpl {

    private static final String TENANT_POOL_NAME_SUFFIX = "DataSource";

    private final EncryptionService encryptionService;

    @Qualifier("masterDataSource")
    private final DataSource masterDataSource;

    @Qualifier("masterDataSourceProperties")
    private final DataSourceProperties dataSourceProperties;

    private final TenantRepository masterTenantRepository;

    @Value("${multitenancy.tenant.datasource.url-prefix}")
    private String urlPrefix;

    @Value("${multitenancy.datasource-cache.maximumSize:100}")
    private Long maximumSize;

    @Value("${multitenancy.datasource-cache.expireAfterAccess:10}")
    private Integer expireAfterAccess;

    @Value("${encryption.secret}")
    private String secret;

    @Value("${encryption.salt}")
    private String salt;

    private LoadingCache<String, HikariDataSource> tenantDataSources;

    @PostConstruct
    private void createCache() {
        tenantDataSources = CacheBuilder.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterAccess(expireAfterAccess, TimeUnit.MINUTES)
                .removalListener((RemovalListener<String, DataSource>) removal -> {
                    HikariDataSource ds = (HikariDataSource) removal.getValue();
                    ds.close(); // tear down properly
                    log.info("Closed datasource: {}", ds.getPoolName());
                })
                .build(new CacheLoader<String, HikariDataSource>() {
                    public HikariDataSource load(String key) {
                        Tenant tenant = masterTenantRepository.findByTenantId(key)
                                .orElseThrow(() -> new RuntimeException("No such tenant: " + key));
                        return createAndConfigureDataSource(tenant);
                    }
                });
    }

    @Override
    protected DataSource selectAnyDataSource() {
        return masterDataSource;
    }

    @Override
    protected DataSource selectDataSource(String tenantIdentifier) {
        try {
            return tenantDataSources.get(tenantIdentifier);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    //tap into connection to customize for hierarchy support
    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        HikariDataSource dataSource = (HikariDataSource)selectDataSource(tenantIdentifier);
        final Connection connection = dataSource.getConnection();
        String userName = dataSource.getUsername();
        //schema-per-tenant scenario, trying to check if it's hierarchical
        if (dataSource.getJdbcUrl().contains("?currentSchema=")) {
            setTenantId(connection, dataSource, userName);
        }
        return getTenancyAwareConnectionProxy(connection);
    }

    // Every time the app asks the data source for a connection, set the PostgreSQL session
    // variable to the tenant id to enforce data isolation.
    private void setTenantId(Connection connection, HikariDataSource dataSource, String userName) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            Map<String, String> tenancyMap = TenantContext.getTenantId();
            //for valid hierarchy support, set row level security
            if (Strings.isNotBlank(tenancyMap.get(TenantConstants.TENANT)) && TenantConstants.MINUS_ONE != tenancyMap.get(TenantConstants.TENANT)
                && Strings.isNotBlank(tenancyMap.get(TenantConstants.PARENT_TENANT)) && TenantConstants.MINUS_ONE != tenancyMap.get(TenantConstants.PARENT_TENANT)) {
                sql.execute("SET app.tenantid TO '" + tenancyMap.get(TenantConstants.TENANT) + "'");
                //need to use app db user, not table owner, as RLS policies are not applied for table owner by default
                if (!userName.endsWith("user")) {
                    dataSource.setUsername(userName + "user"); //RLS user for hierarchical support
                }
            }
        }
    }

    private HikariDataSource createAndConfigureDataSource(Tenant tenant) {
        String decryptedPassword = encryptionService.decrypt(tenant.getPassword(), secret, salt);
        HikariDataSource ds = dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setUsername(tenant.getUserName());
        ds.setPassword(decryptedPassword);
        ds.setJdbcUrl(tenant.getUrl());
        ds.setPoolName(tenant.getTenantId() + TENANT_POOL_NAME_SUFFIX);

        log.info("Configured datasource: {}", ds.getPoolName());
        log.info("ds url " + ds.getJdbcUrl() + ", user " + ds.getUsername() + ", isolation " + tenant.getIsolationType());
        return ds;
    }

    // Connection Proxy that intercepts close() to reset the tenant_id
    protected Connection getTenancyAwareConnectionProxy(Connection connection) {
        return (Connection)
                Proxy.newProxyInstance(
                        ConnectionProxy.class.getClassLoader(),
                        new Class[]{ConnectionProxy.class},
                        new TenancyAwareInvocationHandler(connection));
    }

    // Connection Proxy invocation handler that intercepts close() to reset the tenant_id
    private class TenancyAwareInvocationHandler implements InvocationHandler {
        private final Connection target;

        TenancyAwareInvocationHandler(Connection target) {
            this.target = target;
        }

        @Nullable
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Tenant-aware proxy for target Connection [" + this.target.toString() + "]";
                case "unwrap":
                    if (((Class) args[0]).isInstance(proxy)) {
                        return proxy;
                    } else {
                        return method.invoke(target, args);
                    }
                case "isWrapperFor":
                    if (((Class) args[0]).isInstance(proxy)) {
                        return true;
                    } else {
                        return method.invoke(target, args);
                    }
                case "getTargetConnection":
                    return target;
                default:
                    if (method.getName().equals("close")) {
                        clearTenantId(target);
                    }
                    return method.invoke(target, args);
            }
        }
    }

    // When the connection is closed, clear tenant id from PostgreSQL session
    private void clearTenantId(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("RESET app.tenantid");
        }
    }
}
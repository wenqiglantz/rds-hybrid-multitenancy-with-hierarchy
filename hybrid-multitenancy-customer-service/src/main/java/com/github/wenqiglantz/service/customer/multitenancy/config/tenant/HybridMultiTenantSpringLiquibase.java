package com.github.wenqiglantz.service.customer.multitenancy.config.tenant;

import com.github.wenqiglantz.service.customer.multitenancy.Tenant;
import com.github.wenqiglantz.service.customer.multitenancy.TenantRepository;
import com.github.wenqiglantz.service.customer.util.EncryptionService;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collection;

/**
 * This class provides Liquibase support for multitenancy based on a hybrid collection of DataSources.
 */
@Getter
@Setter
@Slf4j
public class HybridMultiTenantSpringLiquibase implements InitializingBean, ResourceLoaderAware {

    private static final String LIQUIBASE_CONTEXT_NON_HIERARCHY = "main";
    private static final String LIQUIBASE_CONTEXT_HIERARCHY = "main,hierarchy"; //comma separated means both main and hierarchy contexts

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private TenantRepository masterTenantRepository;

    @Autowired
    @Qualifier("tenantLiquibaseProperties")
    private LiquibaseProperties liquibaseProperties;

    @Value("${multitenancy.tenant.datasource.url-prefix}")
    private String urlPrefix;

    @Value("${encryption.secret}")
    private String secret;

    @Value("${encryption.salt}")
    private String salt;

    private ResourceLoader resourceLoader;

    @Value("${multitenancy.tenant.liquibase.changeLog}")
    private String changeLog;
    private boolean dropFirst = false;
    private boolean shouldRun = true;

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("Hybrid multitenancy enabled");
        this.runOnAllTenants(masterTenantRepository.findAll());
    }

    protected void runOnAllTenants(Collection<Tenant> tenants) throws LiquibaseException {
        for(Tenant tenant : tenants) {
            String decryptedPassword = encryptionService.decrypt(tenant.getPassword(), secret, salt);
            log.info("Initializing Liquibase for tenant " + tenant.getTenantId() + " and password " + decryptedPassword);
            switch (tenant.getIsolationType()) {
                case DATABASE:
                    try (Connection connection = DriverManager.getConnection(urlPrefix + tenant.getDbOrSchema(),
                            tenant.getDbOrSchema(), decryptedPassword)) {
                        DataSource tenantDataSource = new SingleConnectionDataSource(connection, false);
                        SpringLiquibase liquibase = this.getSpringLiquibase(tenantDataSource);
                        liquibase.afterPropertiesSet();
                    } catch (SQLException | LiquibaseException e) {
                        log.error("Failed to run Liquibase for tenant " + tenant.getTenantId(), e);
                    }
                    break;

                case SCHEMA:
                    try (Connection connection = DriverManager.getConnection(tenant.getUrl(), tenant.getDbOrSchema(),
                            decryptedPassword)) {
                        DataSource tenantDataSource = new SingleConnectionDataSource(connection, false);
                        SpringLiquibase liquibase = this.getSpringLiquibase(
                                tenantDataSource, tenant.getDbOrSchema(), false);
                        liquibase.afterPropertiesSet();
                    } catch (SQLException | LiquibaseException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e.getMessage());
                    }
                    break;

                case SCHEMADISCRIMINATOR:
                    boolean hierarchySupport = true;
                    try (Connection connection = DriverManager.getConnection(tenant.getUrl(), tenant.getDbOrSchema(),
                            decryptedPassword)) {
                        DataSource tenantDataSource = new SingleConnectionDataSource(connection, false);
                        SpringLiquibase liquibase = this.getSpringLiquibase(
                                tenantDataSource, tenant.getDbOrSchema(), hierarchySupport);
                        liquibase.afterPropertiesSet();
                    } catch (SQLException | LiquibaseException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e.getMessage());
                    }
                    break;
            }
            log.info("Liquibase ran for tenant " + tenant.getTenantId());
        }
    }

    protected SpringLiquibase getSpringLiquibase(DataSource dataSource, String schema, boolean hierarchySupport) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setResourceLoader(getResourceLoader());
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(getChangeLog());
        //crucial in applying changeSets WITHOUT hierarchy support for schema per tenant
        liquibase.setContexts(LIQUIBASE_CONTEXT_NON_HIERARCHY);
        //for hierarchy support, need to apply changeSets specified with both contexts "main" and "hierarchy"
        if (hierarchySupport) {
            liquibase.setContexts(LIQUIBASE_CONTEXT_HIERARCHY);
        }
        liquibase.setDefaultSchema(schema);
        liquibase.setDropFirst(isDropFirst());
        liquibase.setShouldRun(isShouldRun());
        return liquibase;
    }

    protected SpringLiquibase getSpringLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setResourceLoader(getResourceLoader());
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(liquibaseProperties.getChangeLog());
        //crucial in applying changeSets WITHOUT hierarchy support for database per tenant
        liquibase.setContexts(LIQUIBASE_CONTEXT_NON_HIERARCHY);
        liquibase.setLiquibaseSchema(liquibaseProperties.getLiquibaseSchema());
        liquibase.setLiquibaseTablespace(liquibaseProperties.getLiquibaseTablespace());
        liquibase.setDatabaseChangeLogTable(liquibaseProperties.getDatabaseChangeLogTable());
        liquibase.setDatabaseChangeLogLockTable(liquibaseProperties.getDatabaseChangeLogLockTable());
        liquibase.setDropFirst(liquibaseProperties.isDropFirst());
        liquibase.setShouldRun(liquibaseProperties.isEnabled());
        liquibase.setChangeLogParameters(liquibaseProperties.getParameters());
        liquibase.setRollbackFile(liquibaseProperties.getRollbackFile());
        liquibase.setTestRollbackOnUpdate(liquibaseProperties.isTestRollbackOnUpdate());
        return liquibase;
    }
}

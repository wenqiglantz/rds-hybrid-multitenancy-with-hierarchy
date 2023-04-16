package com.github.wenqiglantz.service.tenantadmin.service;

import com.github.wenqiglantz.service.tenantadmin.domain.entity.IsolationType;
import com.github.wenqiglantz.service.tenantadmin.util.EncryptionService;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import com.github.wenqiglantz.service.tenantadmin.domain.entity.Tenant;
import com.github.wenqiglantz.service.tenantadmin.repository.TenantRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@Slf4j
@Service
@EnableConfigurationProperties(LiquibaseProperties.class)
public class TenantAdminService {

    private static final String VALID_DB_SCHEMA_NAME_REGEXP = "[A-Za-z0-9_]*";
    private static final String LIQUIBASE_CONTEXT_NON_HIERARCHY = "main";
    private static final String LIQUIBASE_CONTEXT_HIERARCHY = "main,hierarchy"; //comma separated means both main and hierarchy contexts
    private final EncryptionService encryptionService;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final LiquibaseProperties tenantLiquibaseProperties;
    private final LiquibaseProperties liquibaseProperties;
    private final ResourceLoader resourceLoader;
    private final TenantRepository tenantRepository;
    private final String databaseName;
    private final String urlPrefix;
    private final String liquibaseChangeLog;
    private final String secret;
    private final String salt;

    @Autowired
    public TenantAdminService(EncryptionService encryptionService,
                              DataSource dataSource,
                              JdbcTemplate jdbcTemplate,
                              @Qualifier("masterLiquibaseProperties")
                                       LiquibaseProperties liquibaseProperties,
                              @Qualifier("tenantLiquibaseProperties")
                                       LiquibaseProperties tenantLiquibaseProperties,
                              ResourceLoader resourceLoader,
                              TenantRepository tenantRepository,
                              @Value("${databaseName:}") String databaseName,
                              @Value("${multitenancy.tenant.datasource.url-prefix}") String urlPrefix,
                              @Value("${multitenancy.tenant.liquibase.changeLog}") String liquibaseChangeLog,
                              @Value("${encryption.secret}") String secret,
                              @Value("${encryption.salt}") String salt
    ) {
        this.encryptionService = encryptionService;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.liquibaseProperties = liquibaseProperties;
        this.tenantLiquibaseProperties = tenantLiquibaseProperties;
        this.resourceLoader = resourceLoader;
        this.tenantRepository = tenantRepository;
        this.databaseName = databaseName;
        this.urlPrefix = urlPrefix;
        this.liquibaseChangeLog = liquibaseChangeLog;
        this.secret = secret;
        this.salt = salt;
    }

    public void createTenant(String tenantId, IsolationType isolationType, String dbOrSchema, String userName, String password) {

        // Verify db or schema string to prevent SQL injection
        if (!dbOrSchema.matches(VALID_DB_SCHEMA_NAME_REGEXP)) {
            throw new TenantCreationException("Invalid database or schema name: " + dbOrSchema);
        }

        String url = null;
        String encryptedPassword = encryptionService.encrypt(password, secret, salt);

        switch (isolationType) {
            case DATABASE:
                url = urlPrefix + dbOrSchema;
                try {
                    createDatabase(dbOrSchema, password);
                } catch (DataAccessException e) {
                    throw new TenantCreationException("Error when creating db: " + dbOrSchema, e);
                }
                try (Connection connection = DriverManager.getConnection(url, dbOrSchema, password)) {
                    DataSource tenantDataSource = new SingleConnectionDataSource(connection, false);
                    runLiquibase(tenantDataSource);
                } catch (SQLException | LiquibaseException e) {
                    throw new TenantCreationException("Error when populating db: ", e);
                }
                break;

            case SCHEMA:
                url = urlPrefix + databaseName + "?currentSchema=" + dbOrSchema;
                try {
                    createSchema(dbOrSchema, password);
                    runLiquibase(dataSource, dbOrSchema);
                } catch (DataAccessException e) {
                    throw new TenantCreationException("Error when creating schema: " + dbOrSchema, e);
                } catch (LiquibaseException e) {
                    throw new TenantCreationException("Error when populating schema: ", e);
                }
                break;

            case DISCRIMINATOR:
                url = urlPrefix + databaseName;
                try {
                    runLiquibase(dataSource);
                } catch (DataAccessException e) {
                    throw new TenantCreationException("Error when creating schema: " + dbOrSchema, e);
                } catch (LiquibaseException e) {
                    throw new TenantCreationException("Error when populating schema: ", e);
                }
                break;

            case SCHEMADISCRIMINATOR: //hierarchy support
                boolean hierarchySupport = true;
                url = urlPrefix + databaseName + "?currentSchema=" + dbOrSchema;
                try {
                    createSchemaDiscriminator(dbOrSchema, password);
                    runLiquibase(dataSource, dbOrSchema, hierarchySupport);
                } catch (DataAccessException e) {
                    throw new TenantCreationException("Error when creating schema: " + dbOrSchema, e);
                } catch (LiquibaseException e) {
                    throw new TenantCreationException("Error when populating schema: ", e);
                }
                break;
        }

        Tenant tenant = Tenant.builder()
                .tenantId(tenantId)
                .isolationType(isolationType)
                .dbOrSchema(dbOrSchema)
                .url(url)
                .username(userName)
                .password(encryptedPassword)
                .build();
        tenantRepository.save(tenant);
    }

    private void createDatabase(String db, String password) {
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("CREATE DATABASE " + db));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("CREATE USER " + db + " WITH ENCRYPTED PASSWORD '" + password + "'"));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("GRANT ALL PRIVILEGES ON DATABASE " + db + " TO " + db));
    }

    private void runLiquibase(DataSource dataSource) throws LiquibaseException {
        SpringLiquibase liquibase = getSpringLiquibase(dataSource);
        liquibase.afterPropertiesSet();
    }

    private void createSchema(String schema, String password) {
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("CREATE USER " + schema+ " WITH ENCRYPTED PASSWORD '" + password + "'"));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("GRANT CONNECT ON DATABASE " + databaseName + " TO " + schema));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("CREATE SCHEMA " + schema + " AUTHORIZATION " + schema));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT ALL PRIVILEGES ON TABLES TO " + schema));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT USAGE ON SEQUENCES TO " + schema));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT EXECUTE ON FUNCTIONS TO " + schema));
    }

    //hierarchy support, PostgreSQL row level security. RLS policies are by default not applied for the table owner,
    //as table owner must be able to access all rows for administrative purposes. So we need to add an app level database user
    private void createSchemaDiscriminator(String schema, String password) {
        //schema and table owner user creation
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("CREATE USER " + schema+ " WITH ENCRYPTED PASSWORD '" + password + "'"));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("GRANT CONNECT ON DATABASE " + databaseName + " TO " + schema));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("CREATE SCHEMA " + schema + " AUTHORIZATION " + schema));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT ALL PRIVILEGES ON TABLES TO " + schema));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT USAGE ON SEQUENCES TO " + schema));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT EXECUTE ON FUNCTIONS TO " + schema));

        //add app level db user
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("CREATE USER " + schema + "user" + " WITH ENCRYPTED PASSWORD '" + password + "'"));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("GRANT CONNECT ON DATABASE " + databaseName + " TO " + schema + "user"));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES ON TABLES TO " + schema+ "user"));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT USAGE ON SEQUENCES TO " + schema + "user"));
        jdbcTemplate.execute((StatementCallback<Boolean>) stmt -> stmt.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT EXECUTE ON FUNCTIONS TO " + schema + "user"));
    }

    private void runLiquibase(DataSource dataSource, String schema) throws LiquibaseException {
        SpringLiquibase liquibase = getSpringLiquibase(dataSource, schema, false);
        liquibase.afterPropertiesSet();
    }

    private void runLiquibase(DataSource dataSource, String schema, boolean hierarchySupport) throws LiquibaseException {
        SpringLiquibase liquibase = getSpringLiquibase(dataSource, schema, hierarchySupport);
        liquibase.afterPropertiesSet();
    }

    protected SpringLiquibase getSpringLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setResourceLoader(resourceLoader);
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(tenantLiquibaseProperties.getChangeLog());
        //crucial in applying changeSets WITHOUT hierarchy support for database per tenant
        liquibase.setContexts(LIQUIBASE_CONTEXT_NON_HIERARCHY);
        liquibase.setDefaultSchema(tenantLiquibaseProperties.getDefaultSchema());
        liquibase.setLiquibaseSchema(tenantLiquibaseProperties.getLiquibaseSchema());
        liquibase.setLiquibaseTablespace(tenantLiquibaseProperties.getLiquibaseTablespace());
        liquibase.setDatabaseChangeLogTable(tenantLiquibaseProperties.getDatabaseChangeLogTable());
        liquibase.setDatabaseChangeLogLockTable(tenantLiquibaseProperties.getDatabaseChangeLogLockTable());
        liquibase.setDropFirst(tenantLiquibaseProperties.isDropFirst());
        liquibase.setShouldRun(tenantLiquibaseProperties.isEnabled());
        liquibase.setChangeLogParameters(tenantLiquibaseProperties.getParameters());
        liquibase.setRollbackFile(tenantLiquibaseProperties.getRollbackFile());
        liquibase.setTestRollbackOnUpdate(tenantLiquibaseProperties.isTestRollbackOnUpdate());
        return liquibase;
    }

    protected SpringLiquibase getSpringLiquibase(DataSource dataSource, String schema, boolean hierarchySupport) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setResourceLoader(resourceLoader);
        liquibase.setDataSource(dataSource);
        liquibase.setDefaultSchema(schema);
        liquibase.setChangeLog(liquibaseChangeLog);
        //crucial in applying changeSets WITHOUT hierarchy support for schema per tenant
        liquibase.setContexts(LIQUIBASE_CONTEXT_NON_HIERARCHY);
        //for hierarchy support, need to apply changeSets specified with both contexts "main" and "hierarchy"
        if (hierarchySupport) {
            liquibase.setContexts(LIQUIBASE_CONTEXT_HIERARCHY);
        }
        liquibase.setDropFirst(liquibaseProperties.isDropFirst());
        liquibase.setShouldRun(liquibaseProperties.isEnabled());
        return liquibase;
    }
}

# Tenant Admin Service

## Overview  

This app provides a simple rest interface for dynamically adding tenants.

## Running the Tenant Admin Service

Build the Tenant Admin Service executable:

```
mvn package
```

then start it via maven

```
mvn spring-boot:run
```

## Testing the Tenant Admin Service

Schema per tenant:
* schematenant1
* schematenant2

Database per tenant:
* dbtenant1
* dbtenant2

Hierarchical multitenancy:
* parenttenant1

```
curl -X POST "localhost:8088/tenants?tenantId=schematenant1&isolationType=SCHEMA&dbOrSchema=schematenant1&userName=schematenant1&password=postgre"

curl -X POST "localhost:8088/tenants?tenantId=schematenant2&isolationType=SCHEMA&dbOrSchema=schematenant2&userName=schematenant2&password=postgre"

curl -X POST "localhost:8088/tenants?tenantId=databasetenant1&isolationType=DATABASE&dbOrSchema=databasetenant1&userName=databasetenant1&password=postgre"

curl -X POST "localhost:8088/tenants?tenantId=databasetenant2&isolationType=DATABASE&dbOrSchema=databasetenant2&userName=databasetenant2&password=postgre"

curl -X POST "localhost:8088/tenants?tenantId=parenttenant1&isolationType=SCHEMADISCRIMINATOR&dbOrSchema=parenttenant1&userName=parenttenant1&password=postgre"

```


## Configuration

Change default port value and other settings in src/main/resources/application.yml.

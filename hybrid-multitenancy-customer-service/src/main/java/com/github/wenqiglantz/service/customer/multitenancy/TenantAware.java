package com.github.wenqiglantz.service.customer.multitenancy;

public interface TenantAware {

    String getTenantId();

    void setTenantId(String tenantId);
}

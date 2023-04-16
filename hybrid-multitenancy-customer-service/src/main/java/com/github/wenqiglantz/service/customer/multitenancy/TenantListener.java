package com.github.wenqiglantz.service.customer.multitenancy;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

import java.util.Map;

import static com.github.wenqiglantz.service.customer.multitenancy.TenantConstants.TENANT;

public class TenantListener {

    @PreUpdate
    @PreRemove
    @PrePersist
    public void setTenant(TenantAware entity) {
        final Map<String, String> map = TenantContext.getTenantId();
        entity.setTenantId(map.get(TENANT));
    }
}

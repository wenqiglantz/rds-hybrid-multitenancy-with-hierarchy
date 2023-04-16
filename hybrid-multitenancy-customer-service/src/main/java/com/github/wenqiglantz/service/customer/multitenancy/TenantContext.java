package com.github.wenqiglantz.service.customer.multitenancy;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import static com.github.wenqiglantz.service.customer.multitenancy.TenantConstants.PARENT_TENANT;
import static com.github.wenqiglantz.service.customer.multitenancy.TenantConstants.TENANT;

@Slf4j
public final class TenantContext {

    private TenantContext() {}

    private static final InheritableThreadLocal<Map<String, String>> CURRENT_TENANT =
            new InheritableThreadLocal<>();

    public static void setTenantId(String tenantId, String parentTenantId) {
        log.debug("Setting tenantId to " + tenantId + " and parentTenantId to " + parentTenantId);
        Map<String, String> tenancyMap = new HashMap<>();
        tenancyMap.put(TENANT, tenantId);
        tenancyMap.put(PARENT_TENANT, parentTenantId);
        CURRENT_TENANT.set(tenancyMap);
    }

    public static Map<String, String> getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear(){
        CURRENT_TENANT.remove();
    }
}
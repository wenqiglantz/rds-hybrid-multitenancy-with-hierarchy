package com.github.wenqiglantz.service.customer.multitenancy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.ui.ModelMap;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.WebRequestInterceptor;

@Component
public class TenantInterceptor implements WebRequestInterceptor {

    private final String defaultTenant;

    @Autowired
    public TenantInterceptor(
            @Value("${multitenancy.tenant.default-tenant:#{null}}") String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    @Override
    public void preHandle(WebRequest request) {
        String tenantId = TenantConstants.MINUS_ONE; //hierarchy support when managing parent tenant config data, which is non child tenant specific
        String parentTenantId = TenantConstants.MINUS_ONE;
        if (request.getHeader(TenantConstants.X_TENANT_ID) != null) {
            tenantId = request.getHeader(TenantConstants.X_TENANT_ID);
        } else if (this.defaultTenant != null) {
            tenantId = this.defaultTenant;
        }

        if (request.getHeader(TenantConstants.X_PARENT_TENANT_ID) != null) {
            parentTenantId = request.getHeader(TenantConstants.X_PARENT_TENANT_ID);
        }
        TenantContext.setTenantId(tenantId, parentTenantId);
    }

    @Override
    public void postHandle(@NonNull WebRequest request, ModelMap model) {
        TenantContext.clear();
    }

    @Override
    public void afterCompletion(@NonNull WebRequest request, Exception ex) {
        // NOOP
    }
}

package com.fyntrac.gateway.dto;

public class SelectTenantRequest {
    private String tenantCode;

    public SelectTenantRequest() {
    }

    public SelectTenantRequest(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }
}

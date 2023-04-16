package com.github.wenqiglantz.service.customer.multitenancy;

public enum IsolationType {
    DATABASE,
    SCHEMA,
    DISCRIMINATOR,
    SCHEMADISCRIMINATOR; //for hierarchy support

    public String value() {
        return name();
    }

    public static IsolationType fromValue(String v) {
        return valueOf(v);
    }
}

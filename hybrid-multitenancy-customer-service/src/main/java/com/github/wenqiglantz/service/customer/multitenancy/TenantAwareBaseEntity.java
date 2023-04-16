package com.github.wenqiglantz.service.customer.multitenancy;

import com.github.wenqiglantz.service.customer.util.Utils;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@MappedSuperclass
@EntityListeners(TenantListener.class)
public abstract class TenantAwareBaseEntity implements TenantAware, Serializable {

    @Id
    @Column(name = "ID")
    private String id;

    @Version
    @Column(name = "VERSION")
    private Long version;

    @Column(name = "INSERTED_AT")
    private LocalDateTime createdOn;

    @Column(name = "INSERTED_BY")
    private String createdBy;

    @Column(name = "UPDATED_AT")
    private LocalDateTime modifiedOn;

    @Column(name = "UPDATED_BY")
    private String modifiedBy;

    @Column(name = "TENANTID")
    private String tenantId;

    public TenantAwareBaseEntity(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public boolean equals(Object o) {
        throw new UnsupportedOperationException("Should be implemented by subclass.");
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException("Should be implemented by subclass.");
    }

    @PrePersist
    private void onPrePersist() {
        id = UUID.randomUUID().toString();
        createdOn = Utils.currentUtc();
        modifiedOn = Utils.currentUtc();
        modifiedBy = createdBy;
    }

    @PreUpdate
    private void onPreUpdate() {
        modifiedOn = Utils.currentUtc();
    }

}

package co.cuentaclara.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class User {

    private UUID id;
    private String email;
    private String passwordHash;
    private String fullName;
    private boolean aiConsentAccepted;
    private OffsetDateTime aiConsentAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public User() {
    }

    public User(String email, String passwordHash, String fullName, boolean aiConsentAccepted) {
        this.email = email.trim().toLowerCase();
        this.passwordHash = passwordHash;
        this.fullName = fullName.trim();
        this.aiConsentAccepted = aiConsentAccepted;
        if (aiConsentAccepted) {
            this.aiConsentAt = OffsetDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public boolean isAiConsentAccepted() {
        return aiConsentAccepted;
    }

    public void setAiConsentAccepted(boolean aiConsentAccepted) {
        this.aiConsentAccepted = aiConsentAccepted;
    }

    public OffsetDateTime getAiConsentAt() {
        return aiConsentAt;
    }

    public void setAiConsentAt(OffsetDateTime aiConsentAt) {
        this.aiConsentAt = aiConsentAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

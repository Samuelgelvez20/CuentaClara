package co.cuentaclara.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "ai_consent_accepted", nullable = false)
    private boolean aiConsentAccepted;

    @Column(name = "ai_consent_at")
    private OffsetDateTime aiConsentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

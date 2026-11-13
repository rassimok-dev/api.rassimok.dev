package dev.rassimok.api.klad;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "login_code")
public class LoginCode extends PanacheEntityBase {

    @Id
    public String email;

    @Column(name = "code_hash")
    public String codeHash;

    public int attempts;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Column(name = "created_at")
    public Instant createdAt;
}

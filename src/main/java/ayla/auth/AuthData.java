package ayla.auth;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class AuthData {
    private AuthData() {}
    public record Credentials(@NotBlank @Size(max=120) String username,
                              @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max=256) String password) {
        @Override public String toString(){return "Credentials[redacted]";}
    }
    public record Refresh(@NotBlank @Size(max=100) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String refreshToken) {
        @Override public String toString(){return "Refresh[redacted]";}
    }
    public record PasswordChange(@NotBlank @Size(max=256) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String currentPassword,
                                 @NotBlank @Size(max=256) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String newPassword) {
        @Override public String toString(){return "PasswordChange[redacted]";}
    }
    public record User(String id,String username,String role,String displayName,boolean onboardingRequired,boolean passwordChangeRequired) {
        public User(String id,String username,String role,String displayName) {this(id,username,role,displayName,false,false);}
        public User(String id,String username,String role) {this(id,username,role,null,false,false);}
    }
    public record Profile(@NotBlank @Size(max=80) String displayName) {}
    public record AccountState(@NotNull Boolean enabled) {}
    public record ManagedUser(String id,String username,String displayName,String role,boolean enabled,Instant createdAt) {}
    public record UserPage(java.util.List<ManagedUser> items,long total,int page,int size) {}
    public record AdminPasswordReset(@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min=12,max=256) String newPassword) {
        @Override public String toString(){return "AdminPasswordReset[redacted]";}
    }
    public record Forgot(@NotBlank @Size(max=120) String username) {}
    public record RecoveryChannel(@NotBlank @Size(max=256) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String currentPassword) {
        @Override public String toString(){return "RecoveryChannel[redacted]";}
    }
    public record Tokens(String accessToken,String refreshToken,String tokenType,long expiresIn,Instant accessExpiresAt,
                         Instant refreshExpiresAt,User user) {
        @Override public String toString(){return "Tokens[redacted]";}
    }
    public record Identity(String userId,String username,String role,UUID sessionId) implements Principal {
        @Override public String getName(){return userId;}
    }
}

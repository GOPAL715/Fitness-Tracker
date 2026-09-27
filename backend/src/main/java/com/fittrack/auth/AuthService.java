package com.fittrack.auth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import org.springframework.security.crypto.password.PasswordEncoder; import java.security.*; import java.nio.charset.StandardCharsets; import java.time.*; import java.util.*; import org.springframework.beans.factory.annotation.Value;
@Service public class AuthService {
 UserRepository users; RefreshTokenRepository tokens; PasswordEncoder encoder; JwtService jwt; Duration ttl; JdbcTemplate jdbc;
 public AuthService(UserRepository u,RefreshTokenRepository t,PasswordEncoder p,JwtService j,@Value("${app.refresh-ttl}") Duration d){users=u;tokens=t;encoder=p;jwt=j;ttl=d;}
 /** Set by the container. Field injection keeps the existing constructor signature intact. */
 @Autowired void setJdbc(JdbcTemplate jdbc){this.jdbc=jdbc;}

 /**
  * Creates the account and its starter fitness profile in one transaction.
  *
  * <p>Every account needs a profile row: the app reads it through {@code /app-data} and the whole
  * Profile screen is a no-op without one, so an account without a profile is an unusable account. The
  * values are the same defaults the Profile form itself falls back to, which is also what the
  * onboarding gate keys on, so a new account correctly lands on the onboarding screen.
  *
  * <p>Transactional on purpose: if the profile insert fails, the user insert rolls back with it, so a
  * half-created account is never persisted. {@code user_id} is taken from the freshly created entity
  * and is never read from the request.
  */
 @Transactional public Tokens register(Credentials c){
  validate(c);String email=c.email().toLowerCase();
  if(users.existsByEmail(email))throw new IllegalArgumentException("Email already registered");
  User u=new User();u.setEmail(email);u.setPasswordHash(encoder.encode(c.password()));users.save(u);
  users.flush();
  createStarterProfile(u.getId());
  return issue(u);
 }

 /**
  * Inserts the default profile for a new account.
  *
  * <p>Idempotent against a re-run: the unique constraint on {@code user_id} is the real guarantee, and
  * an existing row is left untouched rather than reset, so this can never wipe onboarding progress.
  */
 private void createStarterProfile(java.util.UUID userId){
  jdbc.update("insert into fitness_profile (user_id,display_name,goal,fitness_level,equipment,limitations,activity_target,weekly_minutes,sleep_target_hours,step_target,calorie_target,protein_target_g,water_target_oz,target_weight_lb) "
   +"select ?,?,?,?,?,?,?,?,?,?,?,?,?,? where not exists (select 1 from fitness_profile where user_id=?)",
   userId,"Alex Morgan","Build strength","Intermediate","Full gym","None",4,180,new java.math.BigDecimal("8"),10000,2400,150,100,new java.math.BigDecimal("175"),userId);
 }
 /**
  * Authenticates and issues a new session.
  *
  * <p>Transactional on purpose: {@code issue} reads the lazy {@code User.roles} collection to build
  * the access token and inserts the refresh token. Without an open session the roles collection
  * cannot be read, which surfaced as a 500 on every successful login. Keeping both steps in
  * one transaction also makes the refresh-token insert atomic with the login.
  */
 @Transactional public Tokens login(Credentials c){validate(c);User u=users.findByEmail(c.email().toLowerCase()).orElseThrow(()->new IllegalArgumentException("Invalid credentials"));if(!encoder.matches(c.password(),u.getPasswordHash()))throw new IllegalArgumentException("Invalid credentials");return issue(u);}
 @Transactional(noRollbackFor=IllegalArgumentException.class) public Tokens rotate(String raw){RefreshToken old=find(raw).orElseThrow(()->new IllegalArgumentException("Invalid refresh token"));if(old.isUsed()||old.isRevoked()){revokeFamily(old.getFamilyId());throw new IllegalArgumentException("Refresh token reuse detected");}if(!old.getExpiresAt().isAfter(Instant.now())){old.setRevoked(true);tokens.save(old);throw new IllegalArgumentException("Invalid refresh token");}User u=users.findById(old.getUserId()).orElseThrow();String rawNew=secret();RefreshToken replacement=new RefreshToken();replacement.setUserId(old.getUserId());replacement.setFamilyId(old.getFamilyId());replacement.setTokenHash(hash(rawNew));replacement.setExpiresAt(Instant.now().plus(ttl));old.setUsed(true);old.setRevoked(true);old.setReplacedByHash(replacement.getTokenHash());tokens.save(old);tokens.save(replacement);return new Tokens(jwt.access(u),rawNew,replacement.getExpiresAt(),safeUser(u));}
 @Transactional public void logout(String raw){if(raw==null||raw.isBlank())return;find(raw).ifPresent(t->{t.setRevoked(true);tokens.save(t);});}
 public java.util.Map<String,Object> me(String id){User u=users.findById(java.util.UUID.fromString(id)).orElseThrow();return java.util.Map.of("id",u.getId(),"email",u.getEmail(),"enabled",u.isEnabled());}
 private Optional<RefreshToken> find(String raw){return raw==null||raw.isBlank()?Optional.empty():tokens.findByTokenHash(hash(raw));} private void revokeFamily(UUID id){tokens.findAllByFamilyId(id).forEach(t->{t.setRevoked(true);tokens.save(t);});} private void validate(Credentials c){if(c==null||c.email()==null||c.email().isBlank()||c.password()==null||c.password().length()<8)throw new IllegalArgumentException("Valid email and password are required");}
 private Tokens issue(User u){String r=secret();RefreshToken t=new RefreshToken();t.setUserId(u.getId());t.setTokenHash(hash(r));t.setExpiresAt(Instant.now().plus(ttl));tokens.save(t);return new Tokens(jwt.access(u),r,t.getExpiresAt(),safeUser(u));} private Map<String,Object> safeUser(User u){return Map.of("id",u.getId(),"email",u.getEmail(),"enabled",u.isEnabled());} private static String secret(){return UUID.randomUUID().toString()+UUID.randomUUID();} static String hash(String v){return java.util.HexFormat.of().formatHex(sha(v));} private static byte[] sha(String v){try{return MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
}

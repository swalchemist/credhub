package io.pivotal.security.credential;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.codec.digest.Crypt;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY;

public class User implements CredentialValue {
  private String username;
  private String password;
  private String salt;

  public User() {}

  public User(String username, String password, String salt) {
    this.username = username;
    this.password = password;
    this.salt = salt;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  @JsonIgnore
  public String getSalt() {
    if (salt == null) {
      salt = new CryptSaltFactory().generateSalt(password);
    }

    return salt;
  }

  @JsonProperty(value = "password_hash", access = READ_ONLY)
  @SuppressWarnings("unused")
  public String getPasswordHash() {
    return Crypt.crypt(getPassword(), getSalt());
  }
}

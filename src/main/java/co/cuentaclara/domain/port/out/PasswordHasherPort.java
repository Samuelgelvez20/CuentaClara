package co.cuentaclara.domain.port.out;

public interface PasswordHasherPort {

    String encode(CharSequence rawPassword);

    boolean matches(CharSequence rawPassword, String encodedPassword);
}

package com.pawtrail.auth.infrastructure.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 토큰 서명기와 검증기를 만드는 곳입니다.
 *
 * 게이트웨이가 spring-security-oauth2-jose 로 검증하므로 발급도 같은 것을 씁니다.
 * 발급과 검증이 한 벌이면 알고리즘과 형식이 어긋날 여지가 줄어듭니다.
 *
 * 서명을 직접 구현하지 않는 이유는 검증 쪽과 같습니다.
 * 헤더 구성, 알고리즘 표기, 만료 계산까지 규격대로 맞춰야 하는데
 * 그것을 손으로 하면 틀려도 토큰이 만들어지고 검증 단계에서야 드러납니다.
 *
 * 이름이 Encoder 인데 검증기까지 만드는 것은 의도입니다.
 * 이 클래스는 아무도 이름으로 참조하지 않는 설정 클래스이고,
 * 검증기를 다른 곳에서 만들면 키를 읽는 코드가 두 군데로 갈립니다.
 * 그 둘이 어긋나면 서명은 되는데 검증만 실패하는, 원인이 드러나지 않는 상태가 됩니다.
 *
 * 이름과 무관한 프로퍼티까지 여기서 등록하고 있습니다.
 * ConfigurationProperties 는 붙이기만 해서는 빈이 되지 않아 어딘가에서 등록해야 하는데,
 * 이 서비스는 그 자리가 여기 하나입니다.
 * 새 프로퍼티를 만들면 아래 목록에 반드시 추가해야 하며,
 * 빠뜨리면 기동할 때 NoSuchBeanDefinitionException 이 나고
 * 메시지만으로는 등록 누락이라는 것이 드러나지 않습니다.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, AuthProperties.class,
        MailProperties.class, OAuthProperties.class})
public class JwtEncoderConfig {

    /**
     * 개인키 하나만 든 키 집합을 만들어 서명기에 넘깁니다.
     *
     * JWKSource 는 원래 키를 여러 개 두고 골라 쓰기 위한 것인데
     * 우리는 키가 하나뿐이라 고정된 집합으로 감쌉니다.
     * 나중에 키를 교체할 때 두 개를 함께 두는 형태가 필요해지면 이 자리가 바뀝니다.
     */
    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        RSAPrivateKey privateKey = toPrivateKey(properties.privateKeyB64());

        // 공개키를 함께 넣지 않음
        // 서명에는 개인키만 있으면 되고, 검증은 게이트웨이가 자기 공개키로 함
        RSAKey jwk = new RSAKey.Builder(toPublicKeyFrom(privateKey))
                .privateKey(privateKey)
                .build();

        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * 토큰 검증기를 만듭니다.
     *
     * 이 서비스도 자기가 발급한 토큰을 읽어야 합니다.
     * 갱신 요청으로 들어온 리프레시 토큰의 서명과 만료를 확인하고
     * 그 안의 jti 와 발급 시각을 꺼내야 하기 때문입니다.
     *
     * 공개키를 설정으로 받지 않고 개인키에서 뽑습니다.
     * 아래 toPublicKeyFrom 이 서명기에 쓰려고 이미 하고 있는 일이며,
     * 따로 받으면 두 값이 짝이 맞는지를 사람이 챙겨야 합니다.
     *
     * withPublicKey 로 만들면 RS256 이 고정되어 알고리즘 혼동 공격이 막힙니다.
     * 게이트웨이의 검증기도 같은 방식으로 만들어져 있습니다.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        RSAPrivateKey privateKey = toPrivateKey(properties.privateKeyB64());
        return NimbusJwtDecoder.withPublicKey(toPublicKeyFrom(privateKey)).build();
    }

    /**
     * Base64 로 감싼 PEM 을 개인키 객체로 바꿉니다.
     *
     * 두 겹을 벗깁니다.
     *   바깥  환경변수에 한 줄로 담기 위해 감싼 Base64
     *   안쪽  PEM 자체의 머리와 꼬리 줄, 그리고 그 안의 Base64
     *
     * 자바 표준 기능만 씁니다.
     * 라이브러리가 주는 변환기를 쓸 수도 있으나 그 클래스의 위치가 판올림마다 바뀌어 온 편이라
     * 오래 그대로인 표준 쪽을 택했습니다. 게이트웨이의 공개키 변환도 같은 판단입니다.
     *
     * 값이 잘못되면 기동할 때 예외가 나므로 조용히 지나가지 않습니다.
     */
    private RSAPrivateKey toPrivateKey(String base64Pem) {
        try {
            String pem = new String(Base64.getDecoder().decode(base64Pem), StandardCharsets.UTF_8);
            String body = pem
                    .replaceAll("-----[A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "app.jwt.private-key-b64 를 개인키로 읽지 못했습니다. "
                            + "openssl 이 만든 PKCS#8 형식 PEM 을 Base64 로 감싼 값이어야 합니다", e);
        }
    }

    /**
     * 개인키에서 공개키를 만들어 냅니다.
     *
     * RSAKey.Builder 가 공개 지수와 계수를 요구하는데 그 값들이 개인키 안에 들어 있습니다.
     * config 저장소의 공개키를 따로 읽어 올 수도 있지만,
     * 그러면 이 서비스가 쓰지도 않는 값을 설정으로 받아야 하고
     * 두 값이 짝이 맞는지를 사람이 챙겨야 합니다.
     * 개인키에서 뽑으면 짝이 어긋날 수 없습니다.
     */
    private java.security.interfaces.RSAPublicKey toPublicKeyFrom(RSAPrivateKey privateKey) {
        try {
            java.security.interfaces.RSAPrivateCrtKey crtKey =
                    (java.security.interfaces.RSAPrivateCrtKey) privateKey;
            return (java.security.interfaces.RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.RSAPublicKeySpec(
                            crtKey.getModulus(), crtKey.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "개인키에서 공개키를 만들지 못했습니다. "
                            + "openssl genpkey 가 만든 PKCS#8 개인키인지 확인하십시오", e);
        }
    }
}

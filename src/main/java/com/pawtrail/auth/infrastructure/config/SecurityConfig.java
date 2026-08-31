package com.pawtrail.auth.infrastructure.config;

import com.pawtrail.common.security.filter.HeaderAuthenticationFilter;
import com.pawtrail.common.security.handler.CustomSecurityExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 이 서비스의 보안 설정입니다.
 *
 * 여기에 SecurityFilterChain 을 정의하는 순간 공통 모듈의 체인이 물러납니다.
 * CommonSecurityAutoConfiguration 의 체인에 @ConditionalOnMissingBean 이 붙어 있기 때문입니다.
 *
 * 그래서 공통 체인이 넣어 주던 것을 여기서 전부 다시 지정합니다.
 * 아래 설정 중 permitAll 목록을 제외한 나머지는 공통 체인과 같은 내용입니다.
 * 한 줄이라도 빠지면 그 보호가 이 서비스에서만 사라지는데,
 * 대부분 오류가 아니라 "되어야 할 것이 안 되는" 형태로 나타납니다.
 *
 * 인증 서비스만 자기 체인이 필요한 이유는 로그인과 회원가입 때문입니다.
 * 공통 체인은 anyRequest().authenticated() 라 토큰이 없으면 통과하지 못하는데,
 * 로그인은 토큰을 받기 전에 불러야 하는 요청입니다.
 */
@Configuration
public class SecurityConfig {

    /**
     * 비밀번호 해싱기입니다.
     *
     * BCrypt 는 결과에 알고리즘과 강도, 소금을 함께 담아 60자 문자열로 만듭니다.
     * 소금을 따로 저장할 컬럼이 필요 없는 이유가 그것입니다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AuthProperties authProperties,
                                           CustomSecurityExceptionHandler exceptionHandler)
            throws Exception {

        // config 저장소에서 받은 목록을 배열로 바꿉니다.
        // 비어 있으면 AuthProperties 가 기동 시점에 이미 막았으므로 여기서는 확인하지 않습니다.
        String[] permitAllPaths = authProperties.permitAll().toArray(String[]::new);

        http
                // 아래 셋은 공통 체인과 같습니다.
                // 브라우저 폼 로그인도 세션도 쓰지 않으므로 끕니다.
                // csrf 를 끄는 것이 SameSite=Strict 쿠키와 짝입니다.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 401 과 403 을 공통 형식으로 내보냅니다.
                //
                // 이 빈은 공통 모듈이 만들어 준 것을 그대로 주입받습니다.
                // 체인만 @ConditionalOnMissingBean(SecurityFilterChain.class) 이고
                // 핸들러 빈은 별도 조건이라 우리가 체인을 정의해도 살아 있습니다.
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(exceptionHandler)
                        .accessDeniedHandler(exceptionHandler))

                // 게이트웨이가 넣어 준 헤더를 읽어 인증 객체를 만듭니다.
                //
                // 이 서비스가 토큰을 발급하는 쪽이라고 해서 자기 요청의 토큰을 직접 파싱하지 않습니다.
                // GET /auth/me 나 DELETE /auth/me 처럼 인증이 필요한 경로가 있고
                // 그 값은 다른 서비스와 똑같이 헤더에서 옵니다.
                .addFilterBefore(new HeaderAuthenticationFilter(),
                        UsernamePasswordAuthenticationFilter.class)

                .authorizeHttpRequests(auth -> auth
                        // 토큰을 받기 전에 불러야 하는 경로입니다.
                        //
                        // 게이트웨이의 app.gateway.permit-all 과 짝이어야 합니다.
                        // 게이트웨이는 이 경로들에 인증 헤더를 넣지 않고 통과시키므로
                        // 여기서 열어 두지 않으면 로그인과 회원가입이 401 로 막힙니다.
                        //
                        // 목록을 config 저장소에서 읽는 이유는 게이트웨이가 이미 그렇게 하고 있어서입니다.
                        // 한쪽만 코드에 박아 두면 경로를 고칠 때 한쪽을 빠뜨리기 쉽습니다.
                        .requestMatchers(permitAllPaths).permitAll()

                        // 아래 둘은 공통 체인과 같습니다.
                        .requestMatchers("/internal/**", "/actuator/**").permitAll()

                        // 관리자 경로는 게이트웨이도 막고 여기서도 막습니다.
                        //
                        // 겹치는 것이 낭비로 보이지만, 공통 체인이 물러나는 서비스가 바로 이 서비스라
                        // 여기에 없으면 이 서비스의 관리자 경로만 공통 보호를 잃습니다.
                        // 게이트웨이가 막고 있어 뚫리지는 않으나 이중화가 반쪽이 됩니다.
                        //
                        // HeaderAuthenticationFilter 가 권한에 ROLE_ 을 붙이므로 hasRole 이 그대로 맞습니다.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        // 나머지는 인증이 필요합니다.
                        //
                        // 전부 열어 두지 않는 이유는 방어보다 불변조건 확보에 가깝습니다.
                        // 열어 두면 principal 이 null 인 채로 컨트롤러에 들어와
                        // 모든 자리에서 null 을 확인해야 합니다.
                        .anyRequest().authenticated());

        return http.build();
    }
}

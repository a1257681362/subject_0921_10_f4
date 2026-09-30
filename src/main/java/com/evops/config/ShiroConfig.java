package com.evops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.Filter;

@Configuration
public class ShiroConfig {

    /** 覆盖 Shiro 内置 authcBasic：未认证 / 账密错误返回可读 JSON 401。 */
    @Bean("authcBasic")
    public Filter authcBasicFilter(ObjectMapper objectMapper) {
        return new JsonBasicHttpAuthenticationFilter(objectMapper);
    }


    /**
     * 顶替 Shiro 默认自动代理（JDK 动态代理，无法注入具体 Service 类）。
     * 配合排除 ShiroAnnotationProcessorAutoConfiguration，改用 CGLIB。
     */
    @Bean
    public DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
        creator.setProxyTargetClass(true);
        return creator;
    }

    /** 排除 Shiro 注解处理器自动配置后，自行注册授权顾问，保留注解鉴权能力。 */
    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(SecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        return advisor;
    }

    @Bean
    public Realm localRealm() {
        SimpleAccountRealm realm = new SimpleAccountRealm();
        realm.addAccount("bootstrap", "bootstrap", "system");
        return realm;
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/error", "anon");
        // API 走 HTTP Basic（bootstrap/bootstrap），未认证返回 401 与可读提示，而不是空白重定向
        chain.addPathDefinition("/api/**", "authcBasic");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}

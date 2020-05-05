package org.touchhome.app.config;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module;
import com.fasterxml.jackson.datatype.jsonorg.JsonOrgModule;
import com.pi4j.io.gpio.Pin;
import lombok.extern.log4j.Log4j2;
import net.rossillo.spring.web.mvc.CacheControlHandlerInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.commons.CommonsMultipartResolver;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.touchhome.app.jsog.JSOGGenerator;
import org.touchhome.app.jsog.JSOGResolver;
import org.touchhome.app.manager.CacheService;
import org.touchhome.app.manager.common.InternalManager;
import org.touchhome.app.model.entity.widget.impl.WidgetBaseEntity;
import org.touchhome.app.repository.crud.base.CrudRepositoryFactoryBean;
import org.touchhome.app.workspace.block.Scratch3Space;
import org.touchhome.bundle.api.model.BaseEntity;
import org.touchhome.bundle.api.model.DeviceBaseEntity;
import org.touchhome.bundle.api.repository.AbstractRepository;
import org.touchhome.bundle.api.repository.PureRepository;
import org.touchhome.bundle.api.scratch.Scratch3ExtensionBlocks;
import org.touchhome.bundle.api.util.ApplicationContextHolder;
import org.touchhome.bundle.api.util.ClassFinder;
import org.touchhome.bundle.cloud.impl.DispatcherServletService;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Log4j2
@Configuration
@EnableCaching
@EnableScheduling
@EntityScan(basePackages = {"org.touchhome"})
@ComponentScan({"org.touchhome"})
@Import(value = {
        WebSocketConfig.class
})
@EnableWebSecurity
@EnableJpaRepositories(basePackages = "org.touchhome.app.repository.crud", repositoryFactoryBeanClass = CrudRepositoryFactoryBean.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class TouchHomeConfig extends WebSecurityConfigurerAdapter implements WebMvcConfigurer, SchedulingConfigurer, ApplicationListener {

    @Autowired
    private ApplicationContext applicationContext;

    @Bean
    public Map<String, Scratch3ExtensionBlocks> scratch3Blocks(List<Scratch3ExtensionBlocks> scratch3Blocks) {
        return scratch3Blocks.stream().collect(Collectors.toMap(Scratch3ExtensionBlocks::getId, p -> p));
    }

    @Bean
    public List<WidgetBaseEntity> widgetBaseEntities(ClassFinder classFinder) {
        return ClassFinder.createClassesWithParent(WidgetBaseEntity.class, classFinder);
    }

    @Bean
    public Map<String, Class<? extends BaseEntity>> baseEntityClasses(ClassFinder classFinder) {
        return classFinder.getClassesWithParent(BaseEntity.class, null).stream().collect(Collectors.toMap(Class::getName, s -> s));
    }

    @Bean
    public Map<String, Class<? extends BaseEntity>> baseEntitySimpleClasses(ClassFinder classFinder) {
        return classFinder.getClassesWithParent(BaseEntity.class, null).stream().collect(Collectors.toMap(Class::getSimpleName, s -> s));
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.cors().and().csrf().disable();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Collections.singletonList("*"));
        configuration.setAllowedMethods(Collections.singletonList("*"));
        configuration.setAllowedHeaders(Collections.singletonList("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public CommonsMultipartResolver multipartResolver() {
        CommonsMultipartResolver resolver = new CommonsMultipartResolver();
        resolver.setDefaultEncoding("utf-8");
        resolver.setMaxUploadSize(20971520);
        return resolver;
    }

    @Bean
    public CacheManager cacheManager() {
        return CacheService.createCacheManager();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(Executors.newScheduledThreadPool(4));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedMethods("*")
                .allowedOrigins("http://localhost:8090");
    }

    @Bean
    public ObjectMapper objectMapper() {
        Hibernate5Module hibernate5Module = new Hibernate5Module();
        hibernate5Module.disable(Hibernate5Module.Feature.USE_TRANSIENT_ANNOTATION);

        SimpleModule simpleModule = new SimpleModule();
        simpleModule.addSerializer(Pin.class, new JsonSerializer<Pin>() {
            @Override
            public void serialize(Pin pin, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
                jsonGenerator.writeStartObject();
                jsonGenerator.writeStringField("name", pin.getName());
                jsonGenerator.writeEndObject();
            }
        });

        simpleModule.addSerializer(Scratch3ExtensionBlocks.class, new JsonSerializer<Scratch3ExtensionBlocks>() {
            @Override
            public void serialize(Scratch3ExtensionBlocks block, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeStartObject();
                gen.writeStringField("id", block.getId());
                if (block.getName() != null) {
                    gen.writeStringField("name", block.getName());
                }
                gen.writeStringField("blockIconURI", block.getBlockIconURI());
                gen.writeStringField("color1", block.getScratch3Color().getColor1());
                gen.writeStringField("color2", block.getScratch3Color().getColor2());
                gen.writeStringField("color3", block.getScratch3Color().getColor3());
                gen.writeObjectField("blocks", block.getBlocks());
                gen.writeObjectField("menus", block.getMenus());
                gen.writeEndObject();
            }
        });

        simpleModule.addSerializer(Scratch3Space.class, new JsonSerializer<Scratch3Space>() {
            @Override
            public void serialize(Scratch3Space extension, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString("---");
            }
        });

        simpleModule.addDeserializer(DeviceBaseEntity.class, new JsonDeserializer<DeviceBaseEntity>() {
            @Override
            // TODO: not optimized
            public DeviceBaseEntity deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return applicationContext.getBean(InternalManager.class).getEntity(p.getText(), DeviceBaseEntity.class);
            }
        });

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper
                .disable(DeserializationFeature.FAIL_ON_UNRESOLVED_OBJECT_IDS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .registerModule(hibernate5Module)
                .registerModule(new JsonOrgModule())
                .registerModule(simpleModule)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .addMixIn(BaseEntity.class, Bean2MixIn.class);
        return objectMapper;
    }

    // Add converters for convert String to desired class in rest controllers
    @Override
    public void addFormatters(final FormatterRegistry registry) {
        registry.addConverter(String.class, DeviceBaseEntity.class, source ->
                applicationContext.getBean(InternalManager.class).getEntity(source, DeviceBaseEntity.class));
    }

    @Bean
    public MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setPrefixJson(false);
        converter.setSupportedMediaTypes(Collections.singletonList(MediaType.APPLICATION_JSON));
        converter.setObjectMapper(objectMapper());
        return converter;
    }

    @Bean
    public Map<String, AbstractRepository> repositories() {
        return applicationContext.getBeansOfType(AbstractRepository.class);
    }

    @Bean
    public Map<String, AbstractRepository> repositoriesByPrefix() {
        return repositories().values().stream().collect(Collectors.toMap(AbstractRepository::getPrefix, r -> r));
    }

    @Bean
    public Map<String, PureRepository> pureRepositories() {
        Map<String, PureRepository> map = new HashMap<>();
        for (PureRepository repository : applicationContext.getBeansOfType(PureRepository.class).values()) {
            map.put(repository.getEntityClass().getSimpleName(), repository);
        }
        return map;
    }

    @Bean
    public ApplicationContextHolder applicationContextHolder() {
        return new ApplicationContextHolder();
    }

    /**
     * After spring context initialization
     */
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ContextRefreshedEvent) {
            ApplicationContext applicationContext = ((ContextRefreshedEvent) event).getApplicationContext();
            applicationContext.getBean(InternalManager.class).afterContextStart(applicationContext);
        }
    }

    /**
     * Force flush cache on request
     */
    @Bean
    public FilterRegistrationBean<Filter> saveDelayFilter() {
        FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
                applicationContext.getBean(CacheService.class).flushDelayedUpdates();
                filterChain.doFilter(request, response);
            }
        });
        registrationBean.addUrlPatterns("/map", "/dashboard", "/items/*", "/hardware*/", "/one_wire/*", "/admin/*");

        return registrationBean;
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.favorPathExtension(false);
    }

    @Bean
    public DispatcherServletService dispatcherServletService(ApplicationContext context) {
        return new DispatcherServletService(context);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CacheControlHandlerInterceptor());
    }

    @JsonIdentityInfo(generator = JSOGGenerator.class, property = "entityID", resolver = JSOGResolver.class)
    interface Bean2MixIn {
    }

    /*@Bean
    public TomcatServletWebServerFactory tomcatFactory() {
        // disable JAR scanning
        return new TomcatServletWebServerFactory() {
            @Override
            protected void postProcessContext(Context context) {
                ((StandardJarScanner) context.getJarScanner()).setScanManifest(false);
            }
        };
    }*/
}
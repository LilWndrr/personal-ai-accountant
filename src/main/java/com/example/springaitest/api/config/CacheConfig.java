package com.example.springaitest.api.config;

import com.example.springaitest.bot.model.UserSession;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// The correct Jackson 3 Imports!

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * GenericJacksonJsonRedisSerializer serializes/deserializes raw Object with
     * no compile-time type info, so without default typing enabled it never
     * embeds a type marker when writing - meaning on read, Jackson has no way
     * to know the value should become a UserSession and falls back to a plain
     * LinkedHashMap (ClassCastException at the call site). Scoped via the
     * validator, not unrestricted, to avoid an arbitrary polymorphic-
     * deserialization attack surface - but it must also cover the standard
     * JDK collection types (ArrayList, HashMap, etc.) that back List/Map-typed
     * fields like UserSession.pendingReview, since those interfaces are
     * themselves polymorphic from Jackson's point of view and need a type
     * marker of their own, not just the domain classes they contain.
     */
    private GenericJacksonJsonRedisSerializer buildSerializer() {
        BasicPolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.example.springaitest.")
                .allowIfSubType("java.util.")
                .build();

        return GenericJacksonJsonRedisSerializer.create(builder -> builder
                .enableDefaultTyping(typeValidator)
                .customize(mapperBuilder -> mapperBuilder
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)));
    }

    @Bean(name = "UserSessionTemplate")
    public RedisTemplate<String, UserSession> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, UserSession> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        GenericJacksonJsonRedisSerializer jsonSerializer = buildSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        return template;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        GenericJacksonJsonRedisSerializer jsonSerializer = buildSerializer();

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

}

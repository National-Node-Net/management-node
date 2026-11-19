/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;

/**
 * The certificate status cache. Spring Boot creates the cache manager chosen with {@code spring.cache.type}:
 * Caffeine, in the memory of one instance, or Redis, shared by several. This class only gives both the same
 * lifetime, {@code application.revocation.cache.ttl}, and decides what a failing cache means.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
@Slf4j
public class CertificateStatusCacheConfig implements CachingConfigurer {

    /** Cache of certificate statuses, keyed {@code <issuerKeyHash>:<serial>}. */
    public static final String CACHE_NAME = "certificate-status";

    /** What every key of the cache starts with in Redis. */
    public static final String REDIS_KEY_PREFIX = "dsi:cert-status:";

    /** Entries kept in memory at most; far above the number of certificates in a federation. */
    private static final long CAFFEINE_MAXIMUM_SIZE = 100_000;

    /** Picked up by Spring Boot's Caffeine cache manager. Statistics feed the {@code cache.gets} metric. */
    @Bean
    public Caffeine<Object, Object> certificateStatusCaffeine(RevocationProperties properties) {
        return Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().ttl())
                .maximumSize(CAFFEINE_MAXIMUM_SIZE)
                .recordStats();
    }

    /**
     * Redis, only when it is the cache. {@code application.yml} excludes Spring Boot's Redis auto-configuration,
     * and this brings it back for {@code spring.cache.type=redis}. Otherwise an instance that caches in memory
     * would still build a Redis connection factory at startup, with its TLS context, and report Redis in its
     * health although it never uses it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
    @Import(RedisAutoConfiguration.class)
    static class Redis {

        /**
         * Picked up by Spring Boot's Redis cache manager. Values are plain JSON of {@link CertificateStatusDTO},
         * with no type information in them, so nothing read from Redis can name a class to instantiate.
         */
        @Bean
        RedisCacheConfiguration certificateStatusRedisCacheConfiguration(RevocationProperties properties) {
            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(properties.cache().ttl())
                    .computePrefixWith(cacheName -> REDIS_KEY_PREFIX)
                    .disableCachingNullValues()
                    .serializeValuesWith(SerializationPair.fromSerializer(
                            new Jackson2JsonRedisSerializer<>(mapper, CertificateStatusDTO.class)));
        }
    }

    /**
     * A cache that cannot be read or written is treated as empty: the status is looked up at its source, so an
     * unreachable Redis slows requests down instead of failing them. A failed eviction is the one error that
     * matters, since the entry it leaves behind stays in use until it expires.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache {} could not be read for {}; looking the status up", cache.getName(), key, exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache {} could not store {}", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.error(
                        "Cache {} could not evict {}: a status cached before stays in use until it expires",
                        cache.getName(),
                        key,
                        exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("Cache {} could not be cleared", cache.getName(), exception);
            }
        };
    }
}

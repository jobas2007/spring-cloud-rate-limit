package com.aslearn.edgeservice;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.style.ToStringCreator;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;

import javax.validation.constraints.Min;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
//@Component
@ConfigurationProperties("spring.cloud.gateway.bucket-rate-limiter")
public class CustomerRateLimiter extends AbstractRateLimiter<CustomerRateLimiter.Config> implements ApplicationContextAware {

    public static String CONFIGURATION_PROPERTY_NAME = "bucket-rate-limiter";

    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();

    private Config defaultConfig;

    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
    public static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";
    public static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";
    private AtomicBoolean initialized;
    private boolean includeHeaders;
    private String remainingHeader;
    private String replenishRateHeader;
    private String burstCapacityHeader;
    private String requestedTokensHeader;

    public CustomerRateLimiter() {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, null);
        this.initialized = new AtomicBoolean(false);
        this.includeHeaders = true;
        this.remainingHeader = REMAINING_HEADER;
        this.replenishRateHeader = REPLENISH_RATE_HEADER;
        this.burstCapacityHeader = BURST_CAPACITY_HEADER;
        this.requestedTokensHeader = REQUESTED_TOKENS_HEADER;
        this.initialized.compareAndSet(false, true);
    }

    //Initialize Bucket4j
    public CustomerRateLimiter(ConfigurationService configurationService) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.initialized = new AtomicBoolean(false);
        this.includeHeaders = true;
        this.remainingHeader = REMAINING_HEADER;
        this.replenishRateHeader = REPLENISH_RATE_HEADER;
        this.burstCapacityHeader = BURST_CAPACITY_HEADER;
        this.requestedTokensHeader = REQUESTED_TOKENS_HEADER;
        this.initialized.compareAndSet(false, true);
    }

    public CustomerRateLimiter(int defaultReplenishRate, int defaultBurstCapacity) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, (ConfigurationService) null);
        this.initialized = new AtomicBoolean(false);
        this.includeHeaders = true;
        this.remainingHeader = REMAINING_HEADER;
        this.replenishRateHeader = REPLENISH_RATE_HEADER;
        this.burstCapacityHeader = BURST_CAPACITY_HEADER;
        this.requestedTokensHeader = REQUESTED_TOKENS_HEADER;
        this.defaultConfig = (new Config()).setReplenishRate(defaultReplenishRate).setBurstCapacity(defaultBurstCapacity);
    }

    public CustomerRateLimiter(int defaultReplenishRate, int defaultBurstCapacity, int defaultRequestedTokens) {
        this(defaultReplenishRate, defaultBurstCapacity);
        this.defaultConfig.setRequestedTokens(defaultRequestedTokens);
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String key) {
        Bucket requestBucket;

        if (!this.initialized.get()) {
            throw new IllegalStateException("RedisRateLimiter is not initialized");
        } else {
            try {
                Config routeConfig = this.loadConfiguration(routeId);
                //int replenishRate = routeConfig.getReplenishRate();
                //int burstCapacity = routeConfig.getBurstCapacity();
                //int requestedTokens = routeConfig.getRequestedTokens();
                requestBucket = this.resolveBucket(routeId);
                ConsumptionProbe probe = requestBucket.tryConsumeAndReturnRemaining(routeConfig.getRequestedTokens());
                if (probe.isConsumed()) {
                    log.debug("probe is consumed");
                    Response response = new Response(true, new HashMap<>());
                    return Mono.just(response);
                }
                log.debug("probe is not consumed");
                return Mono.just(new Response(false, getHeaders(routeConfig, probe.getRemainingTokens())));
            } catch (Exception e) {
                log.error("rate limiting failed: {}", e.getCause());
            }
            return Mono.just(new Response(true, new HashMap<>()));
        }
    }

    public Bucket resolveBucket(String routeId) {
        return cache.computeIfAbsent(routeId, this::newBucket);
    }

    private Bucket newBucket(String routeId) {
        log.info("started to create new bucket for routeId:{}", routeId);
        Config routeConfig = this.loadConfiguration(routeId);
        Refill refill = Refill.intervally(routeConfig.getReplenishRate(), Duration.ofMinutes(1));
        Bandwidth limit = Bandwidth.classic(routeConfig.getBurstCapacity(), refill);
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        if (this.initialized.compareAndSet(false, true)) {
            if (context.getBeanNamesForType(ConfigurationService.class).length > 0) {
                this.setConfigurationService((ConfigurationService) context.getBean(ConfigurationService.class));
            }
        }
    }

    CustomerRateLimiter.Config loadConfiguration(String routeId) {
        Config routeConfig = (CustomerRateLimiter.Config) this.getConfig().getOrDefault(routeId, this.defaultConfig);
        if (routeConfig == null) {
            routeConfig = (Config) this.getConfig().get("defaultFilters");
        }

        if (routeConfig == null) {
            throw new IllegalArgumentException("No Configuration found for route " + routeId + " or defaultFilters");
        } else {
            return routeConfig;
        }
    }

    public Map<String, String> getHeaders(Config config, Long tokensLeft) {
        Map<String, String> headers = new HashMap();
        if (this.isIncludeHeaders()) {
            headers.put(this.remainingHeader, tokensLeft.toString());
            headers.put(this.replenishRateHeader, String.valueOf(config.getReplenishRate()));
            headers.put(this.burstCapacityHeader, String.valueOf(config.getBurstCapacity()));
            headers.put(this.requestedTokensHeader, String.valueOf(config.getRequestedTokens()));
        }
        return headers;
    }

    public boolean isIncludeHeaders() {
        return this.includeHeaders;
    }

    public void setIncludeHeaders(boolean includeHeaders) {
        this.includeHeaders = includeHeaders;
    }

    public String getRemainingHeader() {
        return this.remainingHeader;
    }

    public void setRemainingHeader(String remainingHeader) {
        this.remainingHeader = remainingHeader;
    }

    public String getReplenishRateHeader() {
        return this.replenishRateHeader;
    }

    public void setReplenishRateHeader(String replenishRateHeader) {
        this.replenishRateHeader = replenishRateHeader;
    }

    public String getBurstCapacityHeader() {
        return this.burstCapacityHeader;
    }

    public void setBurstCapacityHeader(String burstCapacityHeader) {
        this.burstCapacityHeader = burstCapacityHeader;
    }

    public String getRequestedTokensHeader() {
        return this.requestedTokensHeader;
    }

    public void setRequestedTokensHeader(String requestedTokensHeader) {
        this.requestedTokensHeader = requestedTokensHeader;
    }

    @Validated
    public static class Config {
        private @Min(1L) int replenishRate;
        private @Min(0L) int burstCapacity = 1;
        private @Min(1L) int requestedTokens = 1;

        public Config() {
        }

        public int getReplenishRate() {
            return this.replenishRate;
        }

        public CustomerRateLimiter.Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public int getBurstCapacity() {
            return this.burstCapacity;
        }

        public CustomerRateLimiter.Config setBurstCapacity(int burstCapacity) {
            Assert.isTrue(burstCapacity >= this.replenishRate, "BurstCapacity(" + burstCapacity + ") must be greater than or equal than replenishRate(" + this.replenishRate + ")");
            this.burstCapacity = burstCapacity;
            return this;
        }

        public int getRequestedTokens() {
            return this.requestedTokens;
        }

        public CustomerRateLimiter.Config setRequestedTokens(int requestedTokens) {
            this.requestedTokens = requestedTokens;
            return this;
        }

        public String toString() {
            return (new ToStringCreator(this)).append("replenishRate", this.replenishRate).append("burstCapacity", this.burstCapacity).append("requestedTokens", this.requestedTokens).toString();
        }
    }
}

package org.touchhome.app.manager;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.touchhome.app.utils.CollectionUtils;
import org.touchhome.bundle.api.model.BaseEntity;
import org.touchhome.bundle.api.model.DeviceBaseEntity;
import org.touchhome.bundle.api.model.HasIdIdentifier;
import org.touchhome.bundle.api.repository.PureRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.touchhome.bundle.api.util.ClassFinder.CLASSES_WITH_PARENT_CLASS;
import static org.touchhome.bundle.api.util.ClassFinder.REPOSITORY_BY_CLAZZ;

@Log4j2
@Component
@RequiredArgsConstructor
public class CacheService {

    public static final String CACHE_CLASS_BY_TYPE = "CACHE_CLASS_BY_TYPE";
    public static final String CACHE_ALL_DEVICES = "CACHE_ALL_DEVICES";
    public static final String ENTITY_BY_ENTITY_ID = "ENTITY_BY_ENTITY_ID";
    public static final String ENTITY_WITH_FETCH_LAZY_IGNORE_NOT_UI = "ENTITY_WITH_FETCH_LAZY_IGNORE_NOT_UI";
    public static final String ENTITY_IDS_BY_CLASS_NAME = "ENTITY_IDS_BY_CLASS_NAME";
    public static final String REPOSITORY_BY_ENTITY_ID = "REPOSITORY_BY_ENTITY_ID";

    private final Map<String, UpdateStatement> entityCache = new ConcurrentHashMap<>();

    private final CacheManager cacheManager;

    public static CacheManager createCacheManager() {
        return new ConcurrentMapCacheManager(
                CLASSES_WITH_PARENT_CLASS,

                ENTITY_BY_ENTITY_ID,
                ENTITY_WITH_FETCH_LAZY_IGNORE_NOT_UI,
                ENTITY_IDS_BY_CLASS_NAME,

                REPOSITORY_BY_CLAZZ,
                CACHE_ALL_DEVICES,

                CACHE_CLASS_BY_TYPE,
                REPOSITORY_BY_ENTITY_ID);
    }

    public void clearCache() {
        log.info("Clear cache");
        for (String cache : cacheManager.getCacheNames()) {
            cacheManager.getCache(cache).clear();
        }

    }

    public void entityUpdated(BaseEntity entity) {
        Set<BaseEntity> relatedEntities = CollectionUtils.ignoreNullSet();
        entity.getAllRelatedEntities(relatedEntities);
        relatedEntities.add(entity);
        for (BaseEntity relatedEntity : relatedEntities) {
            if (relatedEntity != null) {
                singleEntityUpdated(relatedEntity);
            }
        }
    }

    private void singleEntityUpdated(BaseEntity entity) {
        Objects.requireNonNull(cacheManager.getCache(ENTITY_BY_ENTITY_ID)).evict(entity.getEntityID());
        Objects.requireNonNull(cacheManager.getCache(ENTITY_WITH_FETCH_LAZY_IGNORE_NOT_UI)).evict(entity.getEntityID());
        Objects.requireNonNull(cacheManager.getCache(ENTITY_IDS_BY_CLASS_NAME)).clear(); // need remove all because entity may create alos another entities

        if (DeviceBaseEntity.class.isAssignableFrom(entity.getClass())) {
            Objects.requireNonNull(cacheManager.getCache(CACHE_ALL_DEVICES)).clear();
        }
    }

    public <T extends HasIdIdentifier> void putToCache(PureRepository<T> repository, T entity) {
        String identifier = entity.getIdentifier();
        if (identifier == null) {
            throw new IllegalStateException("Unable update state without id" + entity);
        }
        synchronized (entityCache) {
            entityCache.put(identifier, new UpdateStatement(entity, repository));
        }
    }

    @Scheduled(fixedDelayString = "${flushDelayedUpdatesInterval:30000}")
    public void flushDelayedUpdates() {
        if (!entityCache.isEmpty()) {
            Map<String, UpdateStatement> entityCacheCopy;
            synchronized (entityCache) {
                entityCacheCopy = new HashMap<>(entityCache);
                entityCache.clear();
            }
            for (UpdateStatement updateStatement : entityCacheCopy.values()) {
                try {
                    updateStatement.pureRepository.flushCashedEntity(updateStatement.baseEntity);

                    if (updateStatement.baseEntity instanceof BaseEntity) {
                        entityUpdated((BaseEntity) updateStatement.baseEntity);
                    }
                } catch (Exception ex) {
                    log.error("Error delay update entity <{}>", updateStatement.baseEntity, ex);
                }
            }
        }
    }

    @AllArgsConstructor
    private static class UpdateStatement {
        HasIdIdentifier baseEntity;
        PureRepository pureRepository;
    }
}

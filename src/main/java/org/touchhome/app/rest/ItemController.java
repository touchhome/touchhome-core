package org.touchhome.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.log4j.Log4j2;
import net.rossillo.spring.web.mvc.CacheControl;
import net.rossillo.spring.web.mvc.CachePolicy;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.json.JSONObject;
import org.springframework.data.util.Pair;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;
import org.touchhome.app.manager.ImageService;
import org.touchhome.app.manager.common.ClassFinder;
import org.touchhome.app.manager.common.EntityContextImpl;
import org.touchhome.app.manager.common.EntityManager;
import org.touchhome.app.model.rest.EntityUIMetaData;
import org.touchhome.app.utils.InternalUtil;
import org.touchhome.app.utils.UIFieldUtils;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.entity.ImageEntity;
import org.touchhome.bundle.api.entity.dependency.DependencyExecutableInstaller;
import org.touchhome.bundle.api.entity.dependency.RequireExecutableDependency;
import org.touchhome.bundle.api.exception.NotFoundException;
import org.touchhome.bundle.api.exception.ServerException;
import org.touchhome.bundle.api.model.ActionResponseModel;
import org.touchhome.bundle.api.model.HasPosition;
import org.touchhome.bundle.api.model.OptionModel;
import org.touchhome.bundle.api.repository.AbstractRepository;
import org.touchhome.bundle.api.ui.UISidebarButton;
import org.touchhome.bundle.api.ui.UISidebarChildren;
import org.touchhome.bundle.api.ui.field.UIField;
import org.touchhome.bundle.api.ui.field.UIFieldType;
import org.touchhome.bundle.api.ui.field.UIFilterOptions;
import org.touchhome.bundle.api.ui.field.action.HasDynamicContextMenuActions;
import org.touchhome.bundle.api.ui.field.action.UIActionButton;
import org.touchhome.bundle.api.ui.field.action.UIActionResponse;
import org.touchhome.bundle.api.ui.field.action.UIContextMenuAction;
import org.touchhome.bundle.api.ui.field.action.impl.DynamicContextMenuAction;
import org.touchhome.bundle.api.util.TouchHomeUtils;

import javax.persistence.Entity;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import java.awt.image.BufferedImage;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.touchhome.bundle.api.util.Constants.ADMIN_ROLE;

@SuppressWarnings({"rawtypes", "unchecked"})
@Log4j2
@RestController
@RequestMapping("/rest/item")
@RequiredArgsConstructor
public class ItemController {

    private static final Map<String, List<Class<? extends BaseEntity>>> typeToEntityClassNames = new HashMap<>();
    private final Map<String, DependencyInstallersContext> typeToRequireDependencies = new HashMap<>();
    private final Map<String, List<ItemContext>> itemsBootstrapContextMap = new HashMap<>();

    private final ObjectMapper objectMapper;
    private final EntityContextImpl entityContext;
    private final EntityManager entityManager;
    private final ClassFinder classFinder;
    private final ImageService imageService;

    private Map<String, Class<? extends BaseEntity>> baseEntitySimpleClasses;

    @SneakyThrows
    static ActionResponseModel executeAction(EntityContext entityContext, ActionRequestModel actionRequestModel,
                                             Object actionHolder, BaseEntity actionEntity) {
        for (Method method : MethodUtils.getMethodsWithAnnotation(actionHolder.getClass(), UIContextMenuAction.class)) {
            UIContextMenuAction menuAction = method.getDeclaredAnnotation(UIContextMenuAction.class);
            if (menuAction.value().equals(actionRequestModel.getName())) {
                return executeMethodAction(method, actionHolder, entityContext, actionEntity, actionRequestModel.getParams());
            }
        }
        // in case when action attached to field or method
        if (actionRequestModel.metadata != null && actionRequestModel.metadata.has("field")) {
            String fieldName = actionRequestModel.metadata.getString("field");

            AccessibleObject field = Optional.ofNullable((AccessibleObject)
                    FieldUtils.getField(actionHolder.getClass(), fieldName, true))
                    .orElse(InternalUtil.findMethodByName(actionHolder.getClass(), fieldName));
            if (field != null) {
                for (UIActionButton actionButton : field.getDeclaredAnnotationsByType(UIActionButton.class)) {
                    if (actionButton.name().equals(actionRequestModel.name)) {
                        return TouchHomeUtils.newInstance(actionButton.actionHandler()).apply(entityContext, actionRequestModel.params);
                    }
                }
            }
        }
        if (actionHolder instanceof HasDynamicContextMenuActions) {
            Set<? extends DynamicContextMenuAction> actions = ((HasDynamicContextMenuActions) actionHolder).getActions(entityContext);
            DynamicContextMenuAction action = actions.stream().filter(a -> a.getName().equals(actionRequestModel.name)).findAny().orElse(null);
            if (action != null) {
                if (action.isDisabled()) {
                    throw new IllegalArgumentException("Unable to invoke disabled action");
                }
                action.getAction().accept(actionRequestModel.params);
                return null;
            }
        }
        throw new IllegalArgumentException("Execution method name: <" + actionRequestModel.getName() + "> not implemented");
    }

    @SneakyThrows
    static ActionResponseModel executeMethodAction(Method method, Object actionHolder,
                                                   EntityContext entityContext,
                                                   BaseEntity actionEntity, JSONObject params) {
        List<Object> objects = new ArrayList<>();
        for (AnnotatedType parameterType : method.getAnnotatedParameterTypes()) {
            if (BaseEntity.class.isAssignableFrom((Class) parameterType.getType())) {
                objects.add(actionEntity);
            } else if (JSONObject.class.isAssignableFrom((Class<?>) parameterType.getType())) {
                objects.add(params);
            } else {
                objects.add(entityContext.getBean((Class) parameterType.getType()));
            }
        }
        method.setAccessible(true);
        return (ActionResponseModel) method.invoke(actionHolder, objects.toArray());
    }

    public void postConstruct() {
        List<Class<? extends BaseEntity>> baseEntityClasses = classFinder.getClassesWithParent(BaseEntity.class, null, null);
        this.baseEntitySimpleClasses = baseEntityClasses.stream().collect(Collectors.toMap(Class::getSimpleName, s -> s));

        for (Class<? extends BaseEntity> baseEntityClass : baseEntityClasses) {
            Class<?> cursor = baseEntityClass.getSuperclass();
            while (!cursor.getSimpleName().equals(BaseEntity.class.getSimpleName())) {
                this.baseEntitySimpleClasses.put(cursor.getSimpleName(), (Class<? extends BaseEntity>) cursor);
                cursor = cursor.getSuperclass();
            }
        }
    }

    @GetMapping("/{type}/context")
    @CacheControl(maxAge = 3600, policy = CachePolicy.PUBLIC)
    public List<ItemContext> getItemsBootstrapContext(@PathVariable("type") String type,
                                                      @RequestParam(value = "subType", required = false) String subType) {
        String key = type + subType;
        itemsBootstrapContextMap.computeIfAbsent(key, s -> {
            List<ItemContext> itemContexts = new ArrayList<>();

            for (Class<?> classType : findAllClassImplementationsByType(type)) {
                List<EntityUIMetaData> entityUIMetaData = UIFieldUtils.fillEntityUIMetadataList(classType, new HashSet<>());
                if (subType != null && subType.contains(":")) {
                    String[] bundleAndClassName = subType.split(":");
                    Object subClassObject = entityContext.getBeanOfBundleBySimpleName(bundleAndClassName[0], bundleAndClassName[1]);
                    List<EntityUIMetaData> subTypeFieldMetadata = UIFieldUtils.fillEntityUIMetadataList(subClassObject, new HashSet<>());
                    // add 'cutFromJson' because custom fields must be fetched from json parameter (uses first available json parameter)
                    for (EntityUIMetaData data : subTypeFieldMetadata) {
                        data.setTypeMetaData(new JSONObject(StringUtils.defaultString(data.getTypeMetaData(), "{}")).put("cutFromJson", true).toString());
                    }
                    entityUIMetaData.addAll(subTypeFieldMetadata);
                }
                // fetch type actions
                List<UIActionResponse> actions = UIFieldUtils.fetchUIActionsFromClass(classType);
                itemContexts.add(new ItemContext(classType.getSimpleName(), entityUIMetaData, actions));
            }

            return itemContexts;
        });
        return itemsBootstrapContextMap.get(key);
    }

    @GetMapping("/{type}/types")
    @CacheControl(maxAge = 3600, policy = CachePolicy.PUBLIC)
    public Set<OptionModel> getImplementationsByBaseType(@PathVariable("type") String type) {
        // type nay be base class also
        Class<?> entityClassByType = entityManager.getClassByType(type);
        if (entityClassByType == null) {
            putTypeToEntityIfNotExists(type);
            if (!typeToEntityClassNames.containsKey(type)) {
                return Collections.emptySet();
            }
        }
        Set<OptionModel> list = fetchCreateItemTypes(entityClassByType);
        for (Class<? extends BaseEntity> aClass : typeToEntityClassNames.get(type)) {
            list.add(getUISideBarMenuOption(aClass));
        }
        return list;
    }

    @GetMapping("/type/{type}/options")
    public List<OptionModel> getItemOptionsByType(@PathVariable("type") String type) {
        putTypeToEntityIfNotExists(type);
        List<OptionModel> list = new ArrayList<>();
        for (Class<? extends BaseEntity> aClass : typeToEntityClassNames.get(type)) {
            list.addAll(OptionModel.list(entityContext.findAll(aClass)));
        }
        Collections.sort(list);

        return list;
    }

    private void reloadItems(String type) {
        entityContext.ui().sendNotification("-global", new JSONObject().put("type", "reloadItems")
                .put("value", type));
    }

    @PostMapping(value = "{type}/installDep/{dependency}")
    public void installDep(@PathVariable("type") String type, @PathVariable("dependency") String dependency) {
        if (this.typeToRequireDependencies.containsKey(type)) {
            DependencyInstallersContext context = this.typeToRequireDependencies.get(type);
            DependencyExecutableInstaller installer = context.installerContexts.stream()
                    .filter(c -> c.requireDependency.equals(dependency))
                    .map(c -> c.installer).findAny().orElse(null);
            if (installer != null) {
                if (installer.isRequireInstallDependencies(entityContext)) {
                    entityContext.bgp().runWithProgress("install-deps-" + dependency, false,
                            progressBar -> {
                                installer.installDependency(entityContext, progressBar);
                                typeToRequireDependencies.get(type).installerContexts.removeIf(ctx -> ctx.requireDependency.equals(dependency));
                                reloadItems(type);
                            }, null,
                            () -> new RuntimeException("INSTALL_DEPENDENCY_IN_PROGRESS"));
                }
            }
        }
    }

    @PostMapping(value = "{entityID}/action")
    public ActionResponseModel callAction(@PathVariable("entityID") String entityID, @RequestBody ActionRequestModel actionRequestModel) {
        BaseEntity<?> entity = entityContext.getEntity(entityID);
        return ItemController.executeAction(entityContext, actionRequestModel, entity, entity);
    }

    @GetMapping("/{type}/actions")
    @CacheControl(maxAge = 3600, policy = CachePolicy.PUBLIC)
    public List<UIActionResponse> getItemsActions(@PathVariable("type") String type) {
        Class<?> entityClassByType = entityManager.getClassByType(type);
        return UIFieldUtils.fetchUIActionsFromClass(entityClassByType);
    }

    @PostMapping("/{type}")
    public BaseEntity<?> create(@PathVariable("type") String type) {
        log.debug("Request creating entity by type: <{}>", type);
        Class<? extends BaseEntity> typeClass = EntityContextImpl.baseEntityNameToClass.get(type);
        if (typeClass == null) {
            throw new IllegalArgumentException("Unable to find base entity with type: " + type);
        }
        BaseEntity<?> baseEntity = TouchHomeUtils.newInstance(typeClass);
        return entityContext.save(baseEntity);
    }

    @PostMapping("/{entityID}/copy")
    public BaseEntity<?> copyEntityByID(@PathVariable("entityID") String entityID) {
        BaseEntity<?> entity = entityContext.getEntity(entityID);
        entity.copy();
        return entityContext.save(entity);
    }

    @DeleteMapping("/{entityID}")
    @Secured(ADMIN_ROLE)
    public void removeEntity(@PathVariable("entityID") String entityID) {
        entityContext.delete(entityID);
    }

    @GetMapping("/{entityID}/dependencies")
    public List<String> canRemove(@PathVariable("entityID") String entityID) {
        AbstractRepository repository = entityContext.getRepository(entityContext.getEntity(entityID)).get();
        List<BaseEntity<?>> usages = getUsages(entityID, repository);
        return usages.stream().map(Object::toString).collect(Collectors.toList());
    }

    @PutMapping
    @SneakyThrows
    public BaseEntity<?> updateItems(@RequestBody String json) {
        JSONObject jsonObject = new JSONObject(json);
        BaseEntity<?> resultField = null;
        for (String entityId : jsonObject.keySet()) {
            BaseEntity<?> entity = entityContext.getEntity(entityId);

            if (entity == null) {
                throw new NotFoundException("Entity '" + entityId + "' not found");
            }

            JSONObject entityFields = jsonObject.getJSONObject(entityId);
            entity = objectMapper.readerForUpdating(entity).readValue(entityFields.toString());

            // reference fields isn't updatable, we need update them manually
            for (String fieldName : entityFields.keySet()) {
                Field field = FieldUtils.getField(entity.getClass(), fieldName, true);
                if (field != null && BaseEntity.class.isAssignableFrom(field.getType())) {
                    BaseEntity<?> refEntity = entityContext.getEntity(entityFields.getString(fieldName));
                    FieldUtils.writeField(field, entity, refEntity);
                }
            }

            // update entity
            BaseEntity<?> savedEntity = entityContext.save(entity);
            if (resultField == null) {
                resultField = savedEntity;
            }
        }
        return resultField;
    }

    @GetMapping("/typeContext/{type}")
    public ItemsByTypeResponse getItemsByTypeWithDependencies(@PathVariable("type") String type) {
        putTypeToEntityIfNotExists(type);
        List<BaseEntity> items = new ArrayList<>();
        List<ItemsByTypeResponse.TypeDependency> typeDependencies = new ArrayList<>();

        for (Class<? extends BaseEntity> aClass : typeToEntityClassNames.get(type)) {
            items.addAll(entityContext.findAll(aClass));
            DependencyInstallersContext installersContext = typeToRequireDependencies.get(aClass.getSimpleName());
            if (installersContext != null) {
                typeDependencies.add(new ItemsByTypeResponse.TypeDependency(aClass.getSimpleName(), installersContext.getAllRequireDependencies()));
            }
        }
        List<Set<UIActionResponse>> contextActions = new ArrayList<>();
        for (BaseEntity item : items) {
            Set<UIActionResponse> actions = Collections.emptySet();
            if (item instanceof HasDynamicContextMenuActions) {
                Set<? extends DynamicContextMenuAction> dynamicContextMenuActions = ((HasDynamicContextMenuActions) item).getActions(entityContext);
                if (dynamicContextMenuActions != null) {
                    actions = dynamicContextMenuActions.stream().map(UIActionResponse::new).collect(Collectors.toCollection(LinkedHashSet::new));
                }
            }
            contextActions.add(actions);
        }
        return new ItemsByTypeResponse(items, contextActions, typeDependencies);
    }

    @GetMapping("type/{type}")
    public List<BaseEntity> getItemsByType(@PathVariable("type") String type) {
        putTypeToEntityIfNotExists(type);
        List<BaseEntity> list = new ArrayList<>();
        for (Class<? extends BaseEntity> aClass : typeToEntityClassNames.get(type)) {
            list.addAll(entityContext.findAll(aClass));
        }

        return list;
    }

    @PostMapping("/fireRouteAction")
    public ActionResponseModel fireRouteAction(@RequestBody RouteActionRequest routeActionRequest) {
        Class<? extends BaseEntity> aClass = baseEntitySimpleClasses.get(routeActionRequest.type);
        for (UISidebarButton button : aClass.getAnnotationsByType(UISidebarButton.class)) {
            if (button.handlerClass().getSimpleName().equals(routeActionRequest.handlerClass)) {
                return TouchHomeUtils.newInstance(button.handlerClass()).apply(entityContext, null);
            }
        }
        return null;
    }

    @GetMapping("/{entityID}")
    public BaseEntity<?> getItem(@PathVariable("entityID") String entityID) {
        return entityManager.getEntityWithFetchLazy(entityID);
    }

    /*@PostMapping("/{entityID}/image")
    public DeviceBaseEntity updateItemImage(@PathVariable("entityID") String entityID, @RequestBody ImageEntity imageEntity) {
        return updateItem(entityID, true, baseEntity -> baseEntity.setImageEntity(imageEntity));
    }*/

    @SneakyThrows
    @PutMapping("/{entityID}/mappedBy/{mappedBy}")
    public BaseEntity<?> putToItem(@PathVariable("entityID") String entityID,
                                   @PathVariable("mappedBy") String mappedBy,
                                   @RequestBody String json) {
        JSONObject jsonObject = new JSONObject(json);
        BaseEntity<?> owner = entityContext.getEntity(entityID);

        for (String type : jsonObject.keySet()) {
            Class<? extends BaseEntity> className = entityManager.getClassByType(type);
            JSONObject entityFields = jsonObject.getJSONObject(type);
            BaseEntity<?> newEntity = objectMapper.readValue(entityFields.toString(), className);
            FieldUtils.writeField(newEntity, mappedBy, owner, true);
            entityContext.save(newEntity);
        }

        return entityContext.getEntity(owner);
    }

    @SneakyThrows
    @Secured(ADMIN_ROLE)
    @DeleteMapping("/{entityID}/field/{field}/item/{entityToRemove}")
    public BaseEntity<?> removeFromItem(@PathVariable("entityID") String entityID,
                                        @PathVariable("field") String field,
                                        @PathVariable("entityToRemove") String entityToRemove) {
        BaseEntity<?> entity = entityContext.getEntity(entityID);
        entityContext.delete(entityToRemove);
        return entityContext.getEntity(entity);
    }

    @PostMapping("/{entityID}/block")
    public void updateBlockPosition(@PathVariable("entityID") String entityID,
                                    @RequestBody UpdateBlockPosition position) {
        BaseEntity<?> entity = entityContext.getEntity(entityID);
        if (entity != null) {
            if (entity instanceof HasPosition) {
                HasPosition<?> hasPosition = (HasPosition<?>) entity;
                hasPosition.setXb(position.xb);
                hasPosition.setYb(position.yb);
                hasPosition.setBw(position.bw);
                hasPosition.setBh(position.bh);
                entityContext.save(entity);
            } else {
                throw new IllegalArgumentException("Entity: " + entityID + " has no ability to update position");
            }
        }
    }

    @PostMapping("/{entityID}/uploadImageBase64")
    @Secured(ADMIN_ROLE)
    public ImageEntity uploadImageBase64(@PathVariable("entityID") String entityID, @RequestBody BufferedImage bufferedImage) {
        try {
            return imageService.upload(entityID, bufferedImage);
        } catch (Exception e) {

            log.error(e.getMessage(), e);
            throw new ServerException(e);
        }
    }

    @GetMapping("/{entityID}/{fieldName}/options")
    public Collection<OptionModel> loadSelectOptions(@PathVariable("entityID") String entityID,
                                                     @PathVariable("fieldName") String fieldName,
                                                     @RequestParam("fieldFetchType") String fieldFetchType) {
        BaseEntity<?> entity = entityContext.getEntity(entityID);
        if (entity == null) {
            entity = getInstanceByClass(entityID); // i.e in case we load Widget
        }
        Class<?> entityClass = entity.getClass();
        if (StringUtils.isNotEmpty(fieldFetchType)) {
            String[] bundleAndClassName = fieldFetchType.split(":");
            entityClass = entityContext.getBeanOfBundleBySimpleName(bundleAndClassName[0], bundleAndClassName[1]).getClass();
        }

        List<OptionModel> options = getEntityOptions(fieldName, entity, entityClass);
        if (options != null) {
            return options;
        }

        return UIFieldUtils.loadOptions(entity, entityContext, fieldName);
    }

    private List<OptionModel> getEntityOptions(String fieldName, BaseEntity<?> entity, Class<?> entityClass) {
        Field field = FieldUtils.getField(entityClass, fieldName, true);
        Class<?> returnType = field == null ? null : field.getType();
        if (returnType == null) {
            Method method = InternalUtil.findMethodByName(entityClass, fieldName);
            if (method == null) {
                return null;
            }
            returnType = method.getReturnType();
        }
        if (returnType.getDeclaredAnnotation(Entity.class) != null) {
            Class<BaseEntity<?>> clazz = (Class<BaseEntity<?>>) returnType;
            List<? extends BaseEntity> selectedOptions = entityContext.findAll(clazz);
            List<OptionModel> options = selectedOptions.stream().map(t -> OptionModel.of(t.getEntityID(), t.getTitle())).collect(Collectors.toList());

            // make filtering/add messages/etc...
            Method filterOptionMethod = findFilterOptionMethod(fieldName, entity);
            if (filterOptionMethod != null) {
                try {
                    filterOptionMethod.setAccessible(true);
                    filterOptionMethod.invoke(entity, selectedOptions, options);
                } catch (Exception ignore) {
                }
            }
            return options;
        }
        return null;
    }

    private Set<OptionModel> fetchCreateItemTypes(Class<?> entityClassByType) {
        return classFinder.getClassesWithParent(entityClassByType)
                .stream()
                .map(this::getUISideBarMenuOption)
                .collect(Collectors.toSet());
    }

    private OptionModel getUISideBarMenuOption(Class<?> aClass) {
        OptionModel optionModel = OptionModel.key(aClass.getSimpleName());
        UISidebarChildren uiSidebarChildren = aClass.getAnnotation(UISidebarChildren.class);
        if (uiSidebarChildren != null) {
            optionModel.json(json -> json.put("icon", uiSidebarChildren.icon()).put("color", uiSidebarChildren.color()));
        }
        return optionModel;
    }

    // set synchronized to avoid calculate multiple times
    private synchronized void putTypeToEntityIfNotExists(String type) {
        if (!typeToEntityClassNames.containsKey(type)) {
            typeToEntityClassNames.put(type, new ArrayList<>());
            Class<? extends BaseEntity> baseEntityByName = baseEntitySimpleClasses.get(type);
            if (baseEntityByName != null) {
                if (Modifier.isAbstract(baseEntityByName.getModifiers())) {
                    typeToEntityClassNames.get(type).addAll(classFinder.getClassesWithParent(baseEntityByName).stream().filter((Predicate<Class>) child -> {
                        if (child.isAnnotationPresent(UISidebarChildren.class)) {
                            UISidebarChildren uiSidebarChildren = (UISidebarChildren) child.getDeclaredAnnotation(UISidebarChildren.class);
                            return uiSidebarChildren.allowCreateItem();
                        }
                        return true;
                    }).collect(Collectors.toList()));
                } else {
                    typeToEntityClassNames.get(type).add(baseEntityByName);
                }

                // populate if entity require extra packages to install
                Set<Class> baseClasses = new HashSet<>();
                for (Class<? extends BaseEntity> entityClass : typeToEntityClassNames.get(type)) {
                    baseClasses.addAll(ClassFinder.findAllParentClasses(entityClass, baseEntityByName));
                }

                computeRequireDependenciesForType(type, baseEntityByName);
            }
        }
    }

    private void computeRequireDependenciesForType(String type, Class<? extends BaseEntity> baseEntityByName) {
        Map<String, Pair<RequireExecutableDependency, Set<Class<? extends BaseEntity>>>> dependencyMap = new HashMap<>();
        // assemble all dependencies
        for (Class<? extends BaseEntity> entityClass : typeToEntityClassNames.get(type)) {
            for (Class<?> baseClass : ClassFinder.findAllParentClasses(entityClass, baseEntityByName)) {
                for (RequireExecutableDependency dependency : baseClass.getAnnotationsByType(RequireExecutableDependency.class)) {
                    dependencyMap.putIfAbsent(dependency.name(), Pair.of(dependency, new HashSet<>()));
                    dependencyMap.get(dependency.name()).getSecond().add(entityClass);
                }
            }
        }
        // check if dependencies require to install
        for (Pair<RequireExecutableDependency, Set<Class<? extends BaseEntity>>> entry : dependencyMap.values()) {
            DependencyExecutableInstaller installer = TouchHomeUtils.newInstance(entry.getFirst().installer());
            if (installer.isRequireInstallDependencies(entityContext)) {
                for (Class<? extends BaseEntity> entityClass : entry.getSecond()) {
                    typeToRequireDependencies.putIfAbsent(entityClass.getSimpleName(), new DependencyInstallersContext());
                    typeToRequireDependencies.get(entityClass.getSimpleName()).installerContexts.add(
                            new DependencyInstallersContext.SingleInstallerContext(installer, entry.getFirst().name()));
                }
            }
        }
    }

    private List<BaseEntity<?>> getUsages(String entityID, AbstractRepository<BaseEntity<?>> repository) {
        Object baseEntity = repository.getByEntityIDWithFetchLazy(entityID, false);
        List<BaseEntity<?>> usages = new ArrayList<>();
        if (baseEntity != null) {
            FieldUtils.getAllFieldsList(baseEntity.getClass()).forEach(field -> {
                try {
                    Class<? extends Annotation> aClass = field.isAnnotationPresent(OneToOne.class) ? OneToOne.class :
                            (field.isAnnotationPresent(OneToMany.class) ? OneToMany.class : null);
                    if (aClass != null) {
                        Object targetValue = FieldUtils.readField(field, baseEntity, true);
                        if (targetValue instanceof Collection) {
                            if (!((Collection<?>) targetValue).isEmpty()) {
                                for (Object o : (Collection<?>) targetValue) {
                                    o.toString(); // hibernate initialize
                                    usages.add((BaseEntity<?>) o);
                                }
                            }
                        } else if (targetValue != null) {
                            usages.add((BaseEntity<?>) targetValue);
                        }
                    }
                } catch (Exception e) {
                    throw new ServerException(e);
                }
            });
        }
        return usages;
    }

    private Method findFilterOptionMethod(String fieldName, Object entity) {
        for (Method declaredMethod : entity.getClass().getDeclaredMethods()) {
            if (declaredMethod.isAnnotationPresent(UIFilterOptions.class) && declaredMethod.getAnnotation(UIFilterOptions.class).value().equals(fieldName)) {
                return declaredMethod;
            }
        }

        // if Class has only one selection and only one filtered method - use it
        long count = FieldUtils.getFieldsListWithAnnotation(entity.getClass(), UIField.class).stream().map(p -> p.getAnnotation(UIField.class).type())
                .filter(f -> f == UIFieldType.SelectBox).count();
        if (count == 1) {
            List<Method> methodsListWithAnnotation = Stream.of(entity.getClass().getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(UIFilterOptions.class))
                    .collect(Collectors.toList());

            if (methodsListWithAnnotation.size() == 1) {
                return methodsListWithAnnotation.get(0);
            }
        }
        return null;
    }

    private BaseEntity<?> getInstanceByClass(String className) {
        Class<?> aClass = entityManager.getClassByType(className);
        BaseEntity<?> instance = (BaseEntity<?>) TouchHomeUtils.newInstance(aClass);
        if (instance == null) {
            throw new IllegalArgumentException("Unable find class: " + className);
        }
        return instance;
    }

    private List<Class<?>> findAllClassImplementationsByType(String type) {
        Class<?> classByType = entityManager.getClassByType(type);
        List<Class<?>> classTypes = new ArrayList<>();
        if (classByType == null) {
            putTypeToEntityIfNotExists(type);
            if (!typeToEntityClassNames.containsKey(type)) {
                return classTypes;
            }
            classTypes.addAll(typeToEntityClassNames.getOrDefault(type, Collections.emptyList()));
        } else {
            classTypes.add(classByType);
        }
        return classTypes;
    }

    @Getter
    @RequiredArgsConstructor
    private static class ItemsByTypeResponse {
        private final List<BaseEntity> items;
        private final List<Set<UIActionResponse>> contextActions;
        private final List<TypeDependency> typeDependencies;

        @Getter
        @AllArgsConstructor
        private static class TypeDependency {
            private String name;
            private Set<String> dependencies;
        }
    }

    private static class DependencyInstallersContext {
        private final List<SingleInstallerContext> installerContexts = new ArrayList<>();

        public Set<String> getAllRequireDependencies() {
            return installerContexts.stream().map(c -> c.requireDependency).collect(Collectors.toSet());
        }

        @RequiredArgsConstructor
        private static class SingleInstallerContext {
            private final DependencyExecutableInstaller installer;
            private final String requireDependency;
        }
    }

    @Data
    private static class UpdateBlockPosition {
        private int xb;
        private int yb;
        private int bw;
        private int bh;
    }

    @Setter
    private static class RouteActionRequest {
        private String type;
        private String handlerClass;
    }

    @Getter
    @Setter
    public static class ActionRequestModel {
        private String name;
        private JSONObject metadata;
        private JSONObject params;
    }

    @Getter
    @AllArgsConstructor
    private static class ItemContext {
        private final String type;
        private final List<EntityUIMetaData> fields;
        private final List<UIActionResponse> actions;
    }
}

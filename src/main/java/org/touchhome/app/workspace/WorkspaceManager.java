package org.touchhome.app.workspace;

import com.pivovarit.function.ThrowingRunnable;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;
import org.touchhome.app.model.workspace.WorkspaceBroadcastEntity;
import org.touchhome.app.repository.device.WorkspaceRepository;
import org.touchhome.app.setting.system.SystemClearWorkspaceButtonSetting;
import org.touchhome.app.setting.system.SystemClearWorkspaceVariablesButtonSetting;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.EntityContextBGP;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.entity.workspace.WorkspaceJsonVariableEntity;
import org.touchhome.bundle.api.entity.workspace.WorkspaceShareVariableEntity;
import org.touchhome.bundle.api.entity.workspace.WorkspaceStandaloneVariableEntity;
import org.touchhome.bundle.api.entity.workspace.backup.WorkspaceBackupEntity;
import org.touchhome.bundle.api.entity.workspace.backup.WorkspaceBackupGroupEntity;
import org.touchhome.bundle.api.entity.workspace.bool.WorkspaceBooleanEntity;
import org.touchhome.bundle.api.entity.workspace.bool.WorkspaceBooleanGroupEntity;
import org.touchhome.bundle.api.entity.workspace.var.WorkspaceVariableEntity;
import org.touchhome.bundle.api.entity.workspace.var.WorkspaceVariableGroupEntity;
import org.touchhome.bundle.api.workspace.WorkspaceBlock;
import org.touchhome.bundle.api.workspace.WorkspaceEntity;
import org.touchhome.bundle.api.workspace.WorkspaceEventListener;
import org.touchhome.bundle.api.workspace.scratch.Scratch3ExtensionBlocks;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Log4j2
@Component
@RequiredArgsConstructor
public class WorkspaceManager {

    private final Set<String> ONCE_EXECUTION_BLOCKS = new HashSet<>(Arrays.asList("boolean_link", "group_variable_link"));
    private final BroadcastLockManagerImpl broadcastLockManager;
    private final EntityContext entityContext;

    private Collection<WorkspaceEventListener> workspaceEventListeners;
    private Map<String, Scratch3ExtensionBlocks> scratch3Blocks;
    private Map<String, TabHolder> tabs = new HashMap<>();

    public void postConstruct(EntityContext entityContext) {
        scratch3Blocks = entityContext.getBeansOfType(Scratch3ExtensionBlocks.class).stream()
                .collect(Collectors.toMap(Scratch3ExtensionBlocks::getId, s -> s));
        workspaceEventListeners = entityContext.getBeansOfType(WorkspaceEventListener.class);
    }

    private void reloadWorkspace(WorkspaceEntity workspaceEntity) {
        log.debug("Reloading workspace <{}>...", workspaceEntity.getName());
        boolean workspaceStartedBefore = tabs.putIfAbsent(workspaceEntity.getEntityID(), new TabHolder()) != null;

        TabHolder tabHolder = releaseWorkspaceEntity(workspaceEntity);

        tabHolder.tab2Services.clear();
        tabHolder.tab2WorkspaceBlocks.clear();

        if (StringUtils.isNotEmpty(workspaceEntity.getContent())) {
            try {
                // wait to finish all nested processes if workspace started before
                if (workspaceStartedBefore) {
                    log.info("Wait workspace <{}> to able to finish old one", workspaceEntity.getTitle());
                    Thread.sleep(3000);
                }

                tabHolder.tab2WorkspaceBlocks = parseWorkspace(workspaceEntity);
                tabHolder.tab2WorkspaceBlocks.values().stream()
                        .filter(workspaceBlock -> workspaceBlock.isTopLevel() && !workspaceBlock.isShadow())
                        .forEach(workspaceBlock -> {
                            if (ONCE_EXECUTION_BLOCKS.contains(workspaceBlock.getOpcode())) {
                                executeOnce(workspaceBlock);
                            } else {
                                EntityContextBGP.ThreadContext<?> threadContext = this.entityContext.bgp().run(
                                        "workspace-" + workspaceBlock.getId(),
                                        createWorkspaceThread(workspaceBlock, workspaceEntity), true);
                                threadContext.setDescription("Tab[" + workspaceEntity.getName() + "]. " + workspaceBlock.getDescription());
                                workspaceBlock.setStateHandler(threadContext::setState);
                                tabHolder.tab2Services.add(threadContext);
                            }
                        });
            } catch (Exception ex) {
                log.error("Unable to initialize workspace: " + ex.getMessage(), ex);
                entityContext.ui().sendErrorMessage("Unable to initialize workspace: " + ex.getMessage(), ex);
            }
        }
    }

    private ThrowingRunnable<Exception> createWorkspaceThread(WorkspaceBlock workspaceBlock, WorkspaceEntity workspaceEntity) {
        return () -> {
            String oldName = Thread.currentThread().getName();
            String name = workspaceBlock.getId();
            log.debug("Workspace start thread: <{}>", name);
            try {
                Thread.currentThread().setName(workspaceEntity.getEntityID());
                ((WorkspaceBlockImpl) workspaceBlock).handleOrEvaluate();
            } catch (Exception ex) {
                log.warn("Error in workspace thread: <{}>, <{}>", name, ex.getMessage());
                entityContext.ui().sendErrorMessage("Error in workspace", ex);
            } finally {
                Thread.currentThread().setName(oldName);
            }
            log.debug("Workspace thread finished: <{}>", name);
        };
    }

    private TabHolder releaseWorkspaceEntity(WorkspaceEntity workspaceEntity) {
        TabHolder tabHolder = tabs.get(workspaceEntity.getEntityID());
        broadcastLockManager.release(workspaceEntity.getEntityID());

        for (WorkspaceEventListener workspaceEventListener : workspaceEventListeners) {
            workspaceEventListener.release(workspaceEntity.getEntityID());
        }

        for (WorkspaceBlock workspaceBlock : tabHolder.tab2WorkspaceBlocks.values()) {
            ((WorkspaceBlockImpl) workspaceBlock).release();
        }
        for (EntityContextBGP.ThreadContext threadContext : tabHolder.tab2Services) {
            this.entityContext.bgp().cancelThread(threadContext.getName());
        }
        return tabHolder;
    }

    private void executeOnce(WorkspaceBlock workspaceBlock) {
        try {
            log.debug("Execute single block: <{}>", workspaceBlock);
            workspaceBlock.handle();
        } catch (Exception ex) {
            log.error("Error while execute single block: <{}>", workspaceBlock, ex);
        }
    }

    public boolean isEmpty(String content) {
        if (StringUtils.isEmpty(content)) {
            return true;
        }
        JSONObject target = new JSONObject(content).getJSONObject("target");
        for (String key : new String[]{"comments", "blocks"}) {
            if (target.has(key) && !target.getJSONObject(key).keySet().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void reloadVariable(WorkspaceShareVariableEntity entity) {
        log.debug("Reloading workspace variables...");
        JSONObject target = new JSONObject(StringUtils.defaultIfEmpty(entity.getContent(), "{}"));

        // single variables
        updateWorkspaceObjects(target.optJSONObject("variables"), WorkspaceStandaloneVariableEntity::new);

        // json variables
        updateWorkspaceObjects(target.optJSONObject("json_variables"), WorkspaceJsonVariableEntity::new);

        // broadcasts
        updateWorkspaceObjects(target.optJSONObject("broadcasts"), WorkspaceBroadcastEntity::new);

        // backup
        Map<BaseEntity, JSONArray> values = updateWorkspaceObjects(target.optJSONObject("backup_lists"), WorkspaceBackupGroupEntity::new);
        createSupplier(values, (baseEntity) -> new WorkspaceBackupEntity().setWorkspaceBackupGroupEntity((WorkspaceBackupGroupEntity) baseEntity), WorkspaceBackupEntity.PREFIX);

        // bool
        values = updateWorkspaceObjects(target.optJSONObject("bool_variables"), WorkspaceBooleanGroupEntity::new);
        createSupplier(values, (baseEntity) -> new WorkspaceBooleanEntity().setWorkspaceBooleanGroupEntity((WorkspaceBooleanGroupEntity) baseEntity), WorkspaceBooleanEntity.PREFIX);

        // group variables
        values = updateWorkspaceObjects(target.optJSONObject("group_variables"), WorkspaceVariableGroupEntity::new);
        createSupplier(values, (baseEntity) -> new WorkspaceVariableEntity().setWorkspaceVariableGroupEntity((WorkspaceVariableGroupEntity) baseEntity), WorkspaceVariableEntity.PREFIX);
    }

    private Map<String, WorkspaceBlock> parseWorkspace(WorkspaceEntity workspaceEntity) {
        JSONObject jsonObject = new JSONObject(workspaceEntity.getContent());
        JSONObject target = jsonObject.getJSONObject("target");

        JSONObject blocks = target.getJSONObject("blocks");
        Map<String, WorkspaceBlock> workspaceMap = new HashMap<>();

        for (String blockId : blocks.keySet()) {
            JSONObject block = blocks.getJSONObject(blockId);

            if (!workspaceMap.containsKey(blockId)) {
                workspaceMap.put(blockId, new WorkspaceBlockImpl(blockId, workspaceMap, scratch3Blocks, entityContext));
            }

            WorkspaceBlockImpl workspaceBlock = (WorkspaceBlockImpl) workspaceMap.get(blockId);
            workspaceBlock.setShadow(block.optBoolean("shadow"));
            workspaceBlock.setTopLevel(block.getBoolean("topLevel"));
            workspaceBlock.setOpcode(block.getString("opcode"));
            workspaceBlock.setParent(getOrCreateWorkspaceBlock(workspaceMap, block, "parent"));
            workspaceBlock.setNext(getOrCreateWorkspaceBlock(workspaceMap, block, "next"));

            JSONObject fields = block.optJSONObject("fields");
            if (fields != null) {
                for (String fieldKey : fields.keySet()) {
                    workspaceBlock.getFields().put(fieldKey, fields.getJSONArray(fieldKey));
                }
            }
            JSONObject inputs = block.optJSONObject("inputs");
            if (inputs != null) {
                for (String inputsKey : inputs.keySet()) {
                    workspaceBlock.getInputs().put(inputsKey, inputs.getJSONArray(inputsKey));
                }
            }
        }

        return workspaceMap;
    }

    private void createSupplier(Map<BaseEntity, JSONArray> res, Function<BaseEntity, BaseEntity> entitySupplier, String prefix) {
        List<String> existedEntities = entityContext.findAllByPrefix(prefix).stream().map(BaseEntity::getEntityID).collect(Collectors.toList());
        for (Map.Entry<BaseEntity, JSONArray> entry : res.entrySet()) {
            JSONArray jsonArray = entry.getValue().optJSONArray(2);
            if (jsonArray != null) {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject1 = jsonArray.getJSONObject(i);
                    saveOrUpdateEntity(() -> entitySupplier.apply(entry.getKey()), jsonObject1.getString("id"), jsonObject1.getString("name"), prefix);
                    existedEntities.remove(prefix + jsonObject1.getString("id"));
                }
            }
        }
        for (String existedEntity : existedEntities) {
            entityContext.delete(existedEntity);
        }
    }

    private Map<BaseEntity, JSONArray> updateWorkspaceObjects(JSONObject list, Supplier<BaseEntity> entitySupplier) {
        Set<String> entities = new HashSet<>();
        Map<BaseEntity, JSONArray> res = new HashMap<>();
        String repositoryPrefix = entitySupplier.get().getEntityPrefix();
        if (list != null) {
            for (String id : list.keySet()) {
                JSONArray array = list.optJSONArray(id);
                String name = array == null ? list.getString(id) : array.getString(0);
                if (!name.isEmpty()) {
                    BaseEntity entity = saveOrUpdateEntity(entitySupplier, id, name, repositoryPrefix);
                    res.put(entity, array);
                    entities.add(repositoryPrefix + id);
                }
            }
        }
        // remove deleted items
        for (BaseEntity entity : entityContext.findAllByPrefix(repositoryPrefix)) {
            if (!entities.contains(entity.getEntityID())) {
                entityContext.delete(entity);
            }
        }
        return res;
    }

    private BaseEntity saveOrUpdateEntity(Supplier<BaseEntity> entitySupplier, String id, String name, String repositoryPrefix) {
        BaseEntity entity = entityContext.getEntity(repositoryPrefix + id);
        if (entity == null) {
            entity = entityContext.save(entitySupplier.get().computeEntityID(() -> id).setName(name));
        } else if (entity.getName() == null || !entity.getName().equals(name)) { // update name if changed
            if (name != null) {
                entity = entityContext.save(entity.setName(name));
            }
        }
        return entity;
    }

    private WorkspaceBlock getOrCreateWorkspaceBlock(Map<String, WorkspaceBlock> workspaceMap, JSONObject block, String key) {
        if (block.has(key) && !block.isNull(key)) {
            workspaceMap.putIfAbsent(block.getString(key), new WorkspaceBlockImpl(block.getString(key), workspaceMap, scratch3Blocks, entityContext));
            return workspaceMap.get(block.getString(key));
        }
        return null;
    }

    public void loadWorkspace() {
        try {
            reloadVariables();
            reloadWorkspaces();
        } catch (Exception ex) {
            log.error("Unable to load workspace. Looks like workspace has incorrect value", ex);
        }
        entityContext.event().addEntityUpdateListener(WorkspaceEntity.class,
                "workspace-change-listener", this::reloadWorkspace);
        entityContext.event().addEntityUpdateListener(WorkspaceShareVariableEntity.class,
                "workspace-share-var-change-listener", this::reloadVariable);
        entityContext.event().addEntityRemovedListener(WorkspaceEntity.class,
                "workspace-remove-listener", entity -> tabs.remove(entity.getEntityID()));

        // listen for clear workspace
        entityContext.setting().listenValue(SystemClearWorkspaceButtonSetting.class, "wm-clear-workspace", () ->
                entityContext.findAll(WorkspaceEntity.class).forEach(entity -> entityContext.save(entity.setContent(""))));

        // listen for clear variables
        entityContext.setting().listenValue(SystemClearWorkspaceVariablesButtonSetting.class, "wm-clear-workspace-variables", () -> {
            entityContext.findAll(WorkspaceEntity.class).forEach(entity -> entityContext.save(entity.setContent("")));
            WorkspaceShareVariableEntity entity = entityContext.getEntity(WorkspaceShareVariableEntity.PREFIX + WorkspaceShareVariableEntity.NAME);
            entityContext.save(entity.setContent(""));
        });
    }

    private void reloadVariables() {
        WorkspaceShareVariableEntity entity = entityContext.getEntity(WorkspaceShareVariableEntity.PREFIX + WorkspaceShareVariableEntity.NAME);
        if (entity == null) {
            entity = entityContext.save(new WorkspaceShareVariableEntity().computeEntityID(() -> WorkspaceShareVariableEntity.NAME));
        }
        reloadVariable(entity);
    }

    private void reloadWorkspaces() {
        List<WorkspaceEntity> list = entityContext.findAll(WorkspaceEntity.class);
        if (list.isEmpty()) {
            WorkspaceEntity mainWorkspace = entityContext.getEntity(WorkspaceEntity.PREFIX + WorkspaceRepository.GENERAL_WORKSPACE_TAB_NAME);
            if (mainWorkspace == null) {
                entityContext.save(new WorkspaceEntity().computeEntityID(() -> WorkspaceRepository.GENERAL_WORKSPACE_TAB_NAME));
            }
        } else {
            for (WorkspaceEntity workspaceEntity : list) {
                reloadWorkspace(workspaceEntity);
            }
        }
    }

    public WorkspaceBlock getWorkspaceBlockById(String id) {
        for (TabHolder tabHolder : this.tabs.values()) {
            if (tabHolder.tab2WorkspaceBlocks.containsKey(id)) {
                return tabHolder.tab2WorkspaceBlocks.get(id);
            }
        }
        return null;
    }

    private static class TabHolder {
        private List<EntityContextBGP.ThreadContext> tab2Services = new ArrayList<>();
        private Map<String, WorkspaceBlock> tab2WorkspaceBlocks = new HashMap<>();
    }
}

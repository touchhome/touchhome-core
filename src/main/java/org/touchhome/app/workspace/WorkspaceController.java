package org.touchhome.app.workspace;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;
import org.touchhome.app.manager.BundleService;
import org.touchhome.app.repository.device.WorkspaceRepository;
import org.touchhome.app.rest.BundleController;
import org.touchhome.app.workspace.block.Scratch3Space;
import org.touchhome.app.workspace.block.core.*;
import org.touchhome.bundle.api.BundleEntryPoint;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.entity.workspace.WorkspaceShareVariableEntity;
import org.touchhome.bundle.api.exception.NotFoundException;
import org.touchhome.bundle.api.exception.ServerException;
import org.touchhome.bundle.api.model.OptionModel;
import org.touchhome.bundle.api.repository.AbstractRepository;
import org.touchhome.bundle.api.util.TouchHomeUtils;
import org.touchhome.bundle.api.workspace.HasWorkspaceVariableLinkAbility;
import org.touchhome.bundle.api.workspace.WorkspaceEntity;
import org.touchhome.bundle.api.workspace.scratch.Scratch3Block;
import org.touchhome.bundle.api.workspace.scratch.Scratch3ExtensionBlocks;
import org.touchhome.bundle.hardware.Scratch3HardwareBlocks;
import org.touchhome.bundle.http.Scratch3NetworkBlocks;
import org.touchhome.bundle.media.Scratch3ImageBlocks;
import org.touchhome.bundle.ui.Scratch3UIBlocks;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

import static org.touchhome.bundle.api.util.Constants.ADMIN_ROLE;

@Log4j2
@RestController
@RequiredArgsConstructor
@RequestMapping("/rest/workspace")
public class WorkspaceController {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z-]*");

    private static final List<Class> systemScratches = Arrays.asList(Scratch3ControlBlocks.class, Scratch3MiscBlocks.class,
            Scratch3DataBlocks.class, Scratch3EventsBlocks.class, Scratch3OperatorBlocks.class, Scratch3MutatorBlocks.class);

    private static final List<Class> inlineScratches = Arrays.asList(Scratch3OtherBlocks.class,
            Scratch3NetworkBlocks.class, Scratch3HardwareBlocks.class, Scratch3UIBlocks.class, Scratch3ImageBlocks.class);

    private final BundleController bundleController;
    private final EntityContext entityContext;
    private final BundleService bundleService;
    private final WorkspaceManager workspaceManager;

    private List<Scratch3ExtensionImpl> extensions;

    public void postConstruct(EntityContext entityContext) {
        List<Scratch3ExtensionImpl> oldExtension = this.extensions == null ? Collections.emptyList() : this.extensions;
        this.extensions = new ArrayList<>();
        for (Scratch3ExtensionBlocks scratch3ExtensionBlock : entityContext.getBeansOfType(Scratch3ExtensionBlocks.class)) {
            scratch3ExtensionBlock.init();

            if (!ID_PATTERN.matcher(scratch3ExtensionBlock.getId()).matches()) {
                throw new IllegalArgumentException("Wrong Scratch3Extension: <" + scratch3ExtensionBlock.getId() + ">. Must contains [a-z] or '-'");
            }

            if (!systemScratches.contains(scratch3ExtensionBlock.getClass())) {
                BundleEntryPoint bundleEntrypoint = bundleController.getBundle(scratch3ExtensionBlock.getId());
                if (bundleEntrypoint == null && scratch3ExtensionBlock.getId().contains("-")) {
                    bundleEntrypoint = bundleController.getBundle(scratch3ExtensionBlock.getId().substring(0, scratch3ExtensionBlock.getId().indexOf("-")));
                }
                int order = Integer.MAX_VALUE;
                if (bundleEntrypoint == null) {
                    if (!inlineScratches.contains(scratch3ExtensionBlock.getClass())) {
                        throw new ServerException("Unable to find bundle context with id: " + scratch3ExtensionBlock.getId());
                    }
                } else {
                    order = bundleEntrypoint.order();
                }
                Scratch3ExtensionImpl scratch3ExtensionImpl = new Scratch3ExtensionImpl(scratch3ExtensionBlock, order);

                if (!oldExtension.contains(scratch3ExtensionImpl)) {
                    insertScratch3Spaces(scratch3ExtensionBlock);
                }
                extensions.add(scratch3ExtensionImpl);
            }
        }
        Collections.sort(extensions);
    }

    @GetMapping("/extension")
    public List<Scratch3ExtensionImpl> getExtensions() {
        return extensions;
    }

    @GetMapping("/extension/{bundleID}.png")
    public ResponseEntity<InputStreamResource> getExtensionImage(@PathVariable("bundleID") String bundleID) {
        BundleEntryPoint bundleEntrypoint = bundleService.getBundle(bundleID);
        InputStream stream = bundleEntrypoint.getClass().getClassLoader().getResourceAsStream("extensions/" + bundleEntrypoint.getBundleId() + ".png");
        if (stream == null) {
            stream = bundleEntrypoint.getClass().getClassLoader().getResourceAsStream(bundleEntrypoint.getBundleImage());
        }
        if (stream == null) {
            throw new NotFoundException("Unable to find workspace extension bundle image for bundle: " + bundleID);
        }
        return TouchHomeUtils.inputStreamToResource(stream, MediaType.IMAGE_PNG);
    }

    @GetMapping("/{entityID}")
    public String getWorkspace(@PathVariable("entityID") String entityID) {
        WorkspaceEntity workspaceEntity = entityContext.getEntity(entityID);
        if (workspaceEntity == null) {
            throw new NotFoundException("Unable to find workspace tab with id: " + entityID);
        }
        return workspaceEntity.getContent();
    }

    @GetMapping("/variable")
    public String getWorkspaceVariables() {
        WorkspaceShareVariableEntity entity = entityContext.getEntity(WorkspaceShareVariableEntity.PREFIX + WorkspaceShareVariableEntity.NAME);
        if (entity == null) {
            throw new NotFoundException("Unable to find workspace variables");
        }
        return entity.getContent();
    }

    @GetMapping("/variable/{type}")
    public List<OptionModel> getWorkspaceVariables(@PathVariable("type") String type) {
        return OptionModel.list(entityContext.findAllByPrefix(type));
    }

    @SneakyThrows
    @PostMapping("/{entityID}")
    public void saveWorkspace(@PathVariable("entityID") String entityID, @RequestBody String json) {
        WorkspaceEntity workspaceEntity = entityContext.getEntity(entityID);
        entityContext.save(workspaceEntity.setContent(json));
    }

    @SneakyThrows
    @PostMapping("/variable")
    public void saveVariables(@RequestBody String json) {
        WorkspaceShareVariableEntity entity = entityContext.getEntity(WorkspaceShareVariableEntity.PREFIX + WorkspaceShareVariableEntity.NAME);
        entityContext.save(entity.setContent(json));
    }

    @SneakyThrows
    @PostMapping("/variable/{entityID}")
    public void saveVariables(@PathVariable("entityID") String entityID, @RequestBody CreateVariable createVariable) {
        Optional<AbstractRepository> optionalRepository = entityContext.getRepository(entityID);
        if (optionalRepository.isPresent()) {
            AbstractRepository repository = optionalRepository.get();
            if (repository instanceof HasWorkspaceVariableLinkAbility) {
                ((HasWorkspaceVariableLinkAbility) repository).createVariable(entityID, createVariable.varGroup, createVariable.varName, createVariable.key);
            } else {
                throw new ServerException("Entity: '" + entityID + "' repository has no workspace variable link ability");
            }
        } else {
            throw new ServerException("Unable to find repository for entity: " + entityID);
        }
    }

    @GetMapping("/tab")
    public List<OptionModel> getWorkspaceTabs() {
        List<WorkspaceEntity> tabs = entityContext.findAll(WorkspaceEntity.class);
        Collections.sort(tabs);
        return OptionModel.list(tabs);
    }

    @SneakyThrows
    @PostMapping("/tab/{name}")
    public OptionModel createWorkspaceTab(@PathVariable("name") String name) {
        BaseEntity workspaceEntity = entityContext.getEntity(WorkspaceEntity.PREFIX + name);
        if (workspaceEntity == null) {
            WorkspaceEntity entity = entityContext.save(new WorkspaceEntity().setName(name).computeEntityID(() -> name));
            return OptionModel.of(entity.getEntityID(), entity.getTitle());
        }
        throw new IllegalArgumentException("Workspace tab with name <" + name + "> already exists");
    }

    @SneakyThrows
    @GetMapping("/tab/{name}")
    public boolean tabExists(@PathVariable("name") String name) {
        return entityContext.getEntity(WorkspaceEntity.PREFIX + name) != null;
    }

    @SneakyThrows
    @PutMapping("/tab/{entityID}")
    @Secured(ADMIN_ROLE)
    public void renameWorkspaceTab(@PathVariable("entityID") String entityID, @RequestBody OptionModel option) {
        WorkspaceEntity entity = entityContext.getEntity(entityID);
        if (entity == null) {
            throw new NotFoundException("Unable to find workspace tab with id: " + entityID);
        }

        if (!WorkspaceRepository.GENERAL_WORKSPACE_TAB_NAME.equals(entity.getName()) &&
                !WorkspaceRepository.GENERAL_WORKSPACE_TAB_NAME.equals(option.getKey())) {

            WorkspaceEntity newEntity = entityContext.getEntityByName(option.getKey(), WorkspaceEntity.class);

            if (newEntity == null) {
                entityContext.save(entity.setName(option.getKey()));
            } else {
                throw new IllegalArgumentException("Workspace tab with name <" + option.getKey() + "> already exists");
            }
        }
    }

    @DeleteMapping("/tab/{entityID}")
    @Secured(ADMIN_ROLE)
    public void deleteWorkspaceTab(@PathVariable("entityID") String entityID) {
        WorkspaceEntity entity = entityContext.getEntity(entityID);
        if (entity == null) {
            throw new NotFoundException("Unable to find workspace tab with id: " + entityID);
        }
        if (WorkspaceRepository.GENERAL_WORKSPACE_TAB_NAME.equals(entity.getName())) {
            throw new IllegalArgumentException("REMOVE_MAIN_TAB");
        }
        if (!workspaceManager.isEmpty(entity.getContent())) {
            throw new IllegalArgumentException("REMOVE_NON_EMPTY_TAB");
        }
        entityContext.delete(entityID);
    }

    private void insertScratch3Spaces(Scratch3ExtensionBlocks scratch3ExtensionBlock) {
        ListIterator scratch3BlockListIterator = scratch3ExtensionBlock.getBlocks().listIterator();
        while (scratch3BlockListIterator.hasNext()) {
            Scratch3Block scratch3Block = (Scratch3Block) scratch3BlockListIterator.next();
            if (scratch3Block.getSpaceCount() > 0) {
                scratch3BlockListIterator.add(new Scratch3Space(scratch3Block.getSpaceCount()));
            }
        }
    }

    @Setter
    public static class CreateVariable {
        String key;
        String varGroup;
        String varName;
    }
}

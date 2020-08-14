package org.touchhome.app.manager.scripting;

import com.pivovarit.function.ThrowingBinaryOperator;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.touchhome.app.manager.BackgroundProcessManager;
import org.touchhome.app.model.entity.ScriptEntity;
import org.touchhome.app.thread.js.AbstractJSBackgroundProcessService;
import org.touchhome.app.utils.JavaScriptBinder;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.manager.LoggerManager;
import org.touchhome.bundle.api.thread.BackgroundProcessStatus;
import org.touchhome.bundle.api.util.SpringUtils;
import org.touchhome.bundle.api.util.TouchHomeUtils;

import javax.script.*;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Log4j2
@Component
@RequiredArgsConstructor
public class ScriptManager {
    // TODO: deprecated replace this
    public static final String REPEAT_EVERY = "REPEAT_EVERY";

    private final ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
    private final LoggerManager loggerManager;
    private final BackgroundProcessManager backgroundProcessManager;
    private final EntityContext entityContext;

    private ExecutorService singleCallExecutorService = Executors.newSingleThreadExecutor();

    @Value("${checkScriptStatusTimeout}")
    private int checkScriptStatusTimeout;

    @Value("${minScriptThreadSleep}")
    private int minScriptThreadSleep;

    @Value("${scriptEngineName}")
    private String scriptEngineName;

    @Value("${maxJavaScriptOnceCallBeforeInterrupt:60}")
    private Integer maxJavaScriptOnceCallBeforeInterruptInSec;

    @Value("${maxJavaScriptCompileBeforeInterruptInSec:5}")
    private Integer maxJavaScriptCompileBeforeInterruptInSec;

    public void postConstruct() {
        for (ScriptEntity scriptEntity : entityContext.findAll(ScriptEntity.class)) {
            try {
                backgroundProcessManager.fireIfNeedRestart(scriptEntity.createBackgroundProcessService(entityContext));
            } catch (Exception ex) {
                scriptEntity.setScriptStatus(BackgroundProcessStatus.FAILED);
                scriptEntity.setError(TouchHomeUtils.getErrorMessage(ex));
                entityContext.save(scriptEntity);
                log.error("Error while start script after crash: " + scriptEntity.getEntityID(), ex);
            }
        }
    }

    @ApiOperation("Execute java script")
    public Object executeJavaScriptOnce(
            @ApiParam(name = "scriptEntity") ScriptEntity scriptEntity,
            @ApiParam(name = "jsonParameters") String jsonParameters,
            @ApiParam(name = "logPrintStream") PrintStream logPrintStream,
            @ApiParam(name = "forceBackground") boolean forceBackground) throws Exception {
        return startThread(scriptEntity, jsonParameters, false, logPrintStream, forceBackground);
    }

    public BackgroundProcessStatus stopThread(ScriptEntity scriptEntity) {
        stopThreadInternal(scriptEntity);
        return BackgroundProcessStatus.STOP;
    }

    private void stopThreadInternal(ScriptEntity scriptEntity) {
        String backgroundProcessServiceID = scriptEntity.getBackgroundProcessServiceID();

        log.info("Stop script: " + scriptEntity.getEntityID());
        if (!BackgroundProcessStatus.RUNNING.equals(scriptEntity.getBackgroundProcessStatus())) {
            log.warn("Trying stop script: " + scriptEntity.getEntityID() + " with wrong status: " + scriptEntity.getBackgroundProcessStatus().name());
            if (backgroundProcessManager.isRunning(backgroundProcessServiceID)) {
                log.warn("Trying stop script: " + scriptEntity.getEntityID() + " with wrong status: " + scriptEntity.getBackgroundProcessStatus().name() + ", but it exists in pull of running processes!");
            }
        } else {
            if (!backgroundProcessManager.isRunning(backgroundProcessServiceID)) {
                log.warn("Trying stop script: " + scriptEntity.getEntityID() + " with status: " + scriptEntity.getBackgroundProcessStatus().name() + ", but it NOT exists in pull of running processes!");
            }
        }
        backgroundProcessManager.cancelTask(backgroundProcessServiceID, BackgroundProcessStatus.STOP, null);
    }

    public void invokeAfterFunction(CompiledScript compiled, Object parameters) {
        invokeFunction(compiled, "after", parameters, true);
    }

    public void invokeBeforeFunction(CompiledScript compiled, Object parameters) {
        invokeFunction(compiled, "before", parameters, true);
    }

    public Object invokeFunction(CompiledScript compiled, String methodName, Object parameters, boolean required) {
        if (compiled.getEngine().get(methodName) != null) {
            try {
                return ((Invocable) compiled.getEngine()).invokeFunction(methodName, parameters);
            } catch (Exception ex) {
                if (required) {
                    Logger threadLog = (Logger) compiled.getEngine().get("log");
                    if (threadLog != null) {
                        threadLog.error("Error invokeMethod " + methodName, ex);
                    }
                    log.warn("Error while call script method: " + methodName, ex);
                }
            }
        }
        return null;
    }

    /**
     * @param forceBackground - if force - execute javascript in background without check if process has period or not
     */
    public Object startThread(ScriptEntity scriptEntity, String json, boolean allowRepeat, PrintStream logPrintStream, boolean forceBackground) throws Exception {
        AbstractJSBackgroundProcessService abstractJSBackgroundProcessService = scriptEntity.createBackgroundProcessService(entityContext);
        abstractJSBackgroundProcessService.setPrintStream(logPrintStream);
        long period = abstractJSBackgroundProcessService.getPeriod();

        if (forceBackground) {
            scriptEntity.setScriptStatus(BackgroundProcessStatus.RUNNING);
            scriptEntity.setJavaScriptParameters(json);
            entityContext.save(scriptEntity);
        } else if (period != 0 && allowRepeat) {
            if (backgroundProcessManager.isRunning(abstractJSBackgroundProcessService)) {
                throw new RuntimeException("Script already in progress. Stop script to restart");
            }
            if (period < minScriptThreadSleep) {
                throw new RuntimeException("Script has bad 'REPEAT_EVERY' value. Must be >= " + minScriptThreadSleep);
            }
            scriptEntity.setScriptStatus(BackgroundProcessStatus.RUNNING);
            scriptEntity.setJavaScriptParameters(json);
            entityContext.save(scriptEntity);
        } else {
            return callJavaScriptOnce(abstractJSBackgroundProcessService);
        }
        return BackgroundProcessStatus.RUNNING;
    }

    public CompiledScript createCompiledScript(ScriptEntity scriptEntity, PrintStream logPrintStream, JSONObject params) {
        ScriptEngine engine = scriptEngineManager.getEngineByName(scriptEngineName);
        if (logPrintStream != null) {
            engine.put(JavaScriptBinder.log.name(), loggerManager.getLogger(logPrintStream));
        }
        engine.put(JavaScriptBinder.entityContext.name(), entityContext);
        engine.put(JavaScriptBinder.script.name(), scriptEntity);
        engine.put(JavaScriptBinder.params.name(), params == null ? new JSONObject(scriptEntity.getJavaScriptParameters()) : params);
        CompiledScript compiled;
        try {
            String formattedJavaScript = scriptEntity.getFormattedJavaScript(entityContext);
            formattedJavaScript = detectReplaceableValues(params, (Compilable) engine, formattedJavaScript);

            compiled = ((Compilable) engine).compile(new StringReader(formattedJavaScript));
            Future<Object> future = singleCallExecutorService.submit((Callable<Object>) compiled::eval);
            try {
                future.get(maxJavaScriptCompileBeforeInterruptInSec, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                future.cancel(true);
                singleCallExecutorService.shutdownNow();
                singleCallExecutorService = Executors.newSingleThreadExecutor();
                throw new ExecutionException("Script evaluation stuck. Got TimeoutException: " + TouchHomeUtils.getErrorMessage(ex), ex);
            }
        } catch (Exception ex) {
            log.error("Can not compile script: " + scriptEntity.getEntityID());
            throw new RuntimeException(ex);
        }
        return compiled;
    }

    private String detectReplaceableValues(JSONObject params, Compilable engine, String formattedJavaScript) throws ScriptException {
        List<String> patternValues = SpringUtils.getPatternValues(SpringUtils.HASH_PATTERN, formattedJavaScript);
        if (!patternValues.isEmpty()) {
            StringBuilder sb = new StringBuilder(formattedJavaScript);
            Map<Integer, String> replaceFunctions = new HashMap<>();
            for (String patternValue : patternValues) {
                String fnName = "rpl_" + patternValue.hashCode();
                replaceFunctions.put(patternValue.hashCode(), "");
                sb.append("function ").append(fnName).append("() { ").append(patternValue.contains("return ") ? patternValue : "return " + patternValue).append(" }");
            }

            // fire rpl functions
            String jsWithRplFunctions = sb.toString();
            CompiledScript cmpl = engine.compile(new StringReader(jsWithRplFunctions));
            cmpl.eval();
            return SpringUtils.replaceHashValues(jsWithRplFunctions, (ThrowingBinaryOperator<String, Exception>) (s, s2) -> {
                Object ret = ((Invocable) cmpl.getEngine()).invokeFunction("rpl_" + s.hashCode(), params);
                return ret == null ? "" : ret.toString();
            });
        }
        return formattedJavaScript;
    }

    /**
     * Run java script once and interrupt it if too long works
     */
    public Object callJavaScriptOnce(AbstractJSBackgroundProcessService abstractJSBackgroundProcessService) throws InterruptedException, ExecutionException {
        Future<Object> future = singleCallExecutorService.submit(() -> {
            abstractJSBackgroundProcessService.beforeStart();
            Object retValue = abstractJSBackgroundProcessService.run();
            abstractJSBackgroundProcessService.afterStop();
            return retValue;
        });
        try {
            return future.get(maxJavaScriptOnceCallBeforeInterruptInSec, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            singleCallExecutorService.shutdownNow();
            singleCallExecutorService = Executors.newSingleThreadExecutor();
            throw new ExecutionException("Script stuck. Got TimeoutException: " + TouchHomeUtils.getErrorMessage(ex), ex);
        }
    }
}

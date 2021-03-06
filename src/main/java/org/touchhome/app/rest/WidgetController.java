package org.touchhome.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.log4j.Log4j2;
import net.rossillo.spring.web.mvc.CacheControl;
import net.rossillo.spring.web.mvc.CachePolicy;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.util.Pair;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;
import org.touchhome.app.manager.ScriptService;
import org.touchhome.app.manager.WidgetService;
import org.touchhome.app.manager.common.EntityContextImpl;
import org.touchhome.app.model.CompileScriptContext;
import org.touchhome.app.model.entity.ScriptEntity;
import org.touchhome.app.model.entity.widget.impl.button.WidgetButtonSeriesEntity;
import org.touchhome.app.model.entity.widget.impl.chart.ChartPeriod;
import org.touchhome.app.model.entity.widget.impl.chart.bar.WidgetBarChartEntity;
import org.touchhome.app.model.entity.widget.impl.chart.line.WidgetLineChartEntity;
import org.touchhome.app.model.entity.widget.impl.chart.line.WidgetLineChartSeriesEntity;
import org.touchhome.app.model.entity.widget.impl.chart.pie.WidgetPieChartEntity;
import org.touchhome.app.model.entity.widget.impl.chart.pie.WidgetPieChartSeriesEntity;
import org.touchhome.app.model.entity.widget.impl.display.WidgetDisplayEntity;
import org.touchhome.app.model.entity.widget.impl.display.WidgetDisplaySeriesEntity;
import org.touchhome.app.model.entity.widget.impl.gauge.WidgetGaugeEntity;
import org.touchhome.app.model.entity.widget.impl.js.WidgetJsEntity;
import org.touchhome.app.model.entity.widget.impl.slider.WidgetSliderEntity;
import org.touchhome.app.model.entity.widget.impl.slider.WidgetSliderSeriesEntity;
import org.touchhome.app.model.entity.widget.impl.toggle.WidgetToggleEntity;
import org.touchhome.app.model.entity.widget.impl.toggle.WidgetToggleSeriesEntity;
import org.touchhome.app.model.workspace.WorkspaceBroadcastEntity;
import org.touchhome.app.repository.widget.HasFetchChartSeries;
import org.touchhome.app.repository.widget.HasLastNumberValueRepository;
import org.touchhome.app.repository.widget.impl.chart.WidgetBarChartSeriesEntity;
import org.touchhome.app.utils.JavaScriptBuilderImpl;
import org.touchhome.app.workspace.block.core.Scratch3EventsBlocks;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.entity.widget.WidgetBaseEntity;
import org.touchhome.bundle.api.entity.widget.WidgetTabEntity;
import org.touchhome.bundle.api.entity.workspace.WorkspaceStandaloneVariableEntity;
import org.touchhome.bundle.api.entity.workspace.bool.WorkspaceBooleanEntity;
import org.touchhome.bundle.api.entity.workspace.var.WorkspaceVariableEntity;
import org.touchhome.bundle.api.exception.NotFoundException;
import org.touchhome.bundle.api.exception.ServerException;
import org.touchhome.bundle.api.model.OptionModel;
import org.touchhome.bundle.api.repository.AbstractRepository;
import org.touchhome.bundle.api.util.TouchHomeUtils;
import org.touchhome.bundle.api.widget.WidgetBaseTemplate;
import org.touchhome.bundle.api.widget.WidgetJSBaseTemplate;

import java.util.*;
import java.util.stream.Collectors;

import static org.touchhome.bundle.api.util.Constants.ADMIN_ROLE;
import static org.touchhome.bundle.api.util.Constants.PRIVILEGED_USER_ROLE;

@Log4j2
@RestController
@RequestMapping("/rest/widget")
@RequiredArgsConstructor
public class WidgetController {

    private final List<WidgetBaseEntity<?>> widgetBaseEntities;
    private final EntityContext entityContext;
    private final Scratch3EventsBlocks scratch3EventsBlocks;
    private final ScriptService scriptService;
    private final ObjectMapper objectMapper;
    private final WidgetService widgetService;

    @SneakyThrows
    @GetMapping("/plugins")
    @CacheControl(maxAge = 3600, policy = CachePolicy.PUBLIC)
    public List<WidgetService.AvailableWidget> getAvailableWidgets() {
        return widgetService.getAvailableWidgets();
    }

    @GetMapping("/button/{entityID}/handle")
    public void handleButtonClick(@PathVariable("entityID") String entityID) {
        WidgetButtonSeriesEntity entity = entityContext.getEntity(entityID);
        BaseEntity<?> source = entityContext.getEntity(entity.getDataSource());
        if (source instanceof WorkspaceBroadcastEntity) {
            scratch3EventsBlocks.broadcastEvent((WorkspaceBroadcastEntity) source);
        } else if (source instanceof WorkspaceBooleanEntity) {
            WorkspaceBooleanEntity wbEntity = (WorkspaceBooleanEntity) source;
            entityContext.save(wbEntity.inverseValue());
        } else {
            throw new ServerException("Unable to find handler for button");
        }
    }

    @GetMapping("/bar/{entityID}/series")
    public Set<BarSeries> getBarSeries(@PathVariable("entityID") String entityID,
                                       @RequestParam(value = "liveEntity", required = false) String liveEntity) throws JsonProcessingException {
        WidgetBarChartEntity entity = entityContext.getEntity(entityID);
        if (liveEntity != null) {
            entity = objectMapper.readValue(liveEntity, entity.getClass());
        }
        Set<BarSeries> series = new LinkedHashSet<>();
        for (WidgetBarChartSeriesEntity item : entity.getSeries()) {
            if (item.getDataSource() != null) {
                BaseEntity<?> source = entityContext.getEntity(item.getDataSource());
                HasLastNumberValueRepository repository = getRepository(source, HasLastNumberValueRepository.class);
                double value = repository.getLastNumberValue(source);
                BarSeries barSeries = new BarSeries(StringUtils.defaultString(item.getName(), source.getTitle()), value);
                int i = 1;
                while (!series.add(barSeries)) {
                    barSeries = new BarSeries(StringUtils.defaultString(item.getName(), source.getTitle()) + "(" + i++ + ")", value);
                }
            }
        }
        return series;
    }

    @GetMapping("/line/{entityID}/series")
    public List<ChartSeries> getChartSeries(@PathVariable("entityID") String entityID,
                                            @RequestParam("period") String period) {
        WidgetLineChartEntity entity = entityContext.getEntity(entityID);
        List<ChartSeries> series = new ArrayList<>();
        for (WidgetLineChartSeriesEntity item : entity.getSeries()) {
            BaseEntity<?> source = entityContext.getEntity(item.getDataSource());
            HasFetchChartSeries repository = getRepository(source, HasFetchChartSeries.class);

            ChartPeriod chartPeriod = ChartPeriod.fromValue(period);
            Pair<Date, Date> range = chartPeriod.getDateRange();

            List<Object[]> chartItems = repository.getLineChartSeries(source, range.getFirst(), range.getSecond());
            if (!chartItems.isEmpty()) {
                chartItems.add(new Object[]{new Date(), chartItems.get(chartItems.size() - 1)[1]});
            }

            List<Date> dateList = chartItems.stream().map(p -> (Date) p[0]).collect(Collectors.toList());
            Float[] values = chartItems.stream().map(p -> (Float) p[1]).toArray(Float[]::new);

            series.add(new ChartSeries(source.getTitle(), dateList, values));
        }
        return series;
    }

    @GetMapping("/pie/{entityID}/series")
    public List<PieSeries> getPieSeries(@PathVariable("entityID") String entityID, @RequestParam("period") String period) {
        WidgetPieChartEntity entity = entityContext.getEntity(entityID);
        List<PieSeries> series = new ArrayList<>();
        for (WidgetPieChartSeriesEntity item : entity.getSeries()) {
            BaseEntity<?> source = entityContext.getEntity(item.getDataSource());
            HasFetchChartSeries repository = getRepository(source, HasFetchChartSeries.class);

            ChartPeriod chartPeriod = ChartPeriod.fromValue(period);
            Pair<Date, Date> range = chartPeriod.getDateRange();

            Object value = repository.getPieChartSeries(source, range.getFirst(), range.getSecond(), entity.getPieChartValueType());
            series.add(new PieSeries(source.getTitle(), value, new PieSeries.Extra(item.getTitle())));
        }
        return series;
    }

    @GetMapping("/gauge/{entityID}/value")
    public Float getGaugeValue(@PathVariable("entityID") String entityID) {
        WidgetGaugeEntity entity = entityContext.getEntity(entityID);
        return fetchVariableValue(entityContext.getEntity(entity.getDataSource()));
    }

    @GetMapping("/slider/{entityID}/values")
    public List<Float> getSliderValues(@PathVariable("entityID") String entityID) {
        WidgetSliderEntity entity = entityContext.getEntity(entityID);
        List<Float> values = new ArrayList<>(entity.getSeries().size());
        for (WidgetSliderSeriesEntity item : entity.getSeries()) {
            BaseEntity<?> dataSource = entityContext.getEntity(item.getDataSource());
            values.add(fetchVariableValue(dataSource));
        }
        return values;
    }

    @GetMapping("/toggle/{entityID}/values")
    public List<Boolean> getToggleValues(@PathVariable("entityID") String entityID) {
        WidgetToggleEntity entity = entityContext.getEntity(entityID);
        List<Boolean> values = new ArrayList<>(entity.getSeries().size());
        for (WidgetToggleSeriesEntity item : entity.getSeries()) {
            BaseEntity<?> dataSource = entityContext.getEntity(item.getDataSource());
            if (dataSource instanceof WorkspaceBooleanEntity) {
                values.add(((WorkspaceBooleanEntity) dataSource).getValue());
            } else {
                throw new ServerException("Unable to find handler for fetch value from <Data Source>: " + dataSource.getTitle());
            }
        }
        return values;
    }

    @GetMapping("/display/{entityID}/values")
    public List<Pair<Object, Date>> getDisplayValues(@PathVariable("entityID") String entityID) {
        WidgetDisplayEntity entity = entityContext.getEntity(entityID);
        List<Pair<Object, Date>> values = new ArrayList<>(entity.getSeries().size());
        for (WidgetDisplaySeriesEntity item : entity.getSeries()) {
            BaseEntity<?> source = entityContext.getEntity(item.getDataSource());
            Object val;
            if (source instanceof WorkspaceBooleanEntity) {
                val = ((WorkspaceBooleanEntity) source).getValue();
            } else {
                val = fetchVariableValue(source);
            }
            values.add(Pair.of(val, source.getUpdateTime()));
        }
        return values;
    }

    @PostMapping("/slider/{entityID}/series/{seriesEntityID}")
    public void updateSliderValue(@PathVariable("entityID") String entityID, @PathVariable("seriesEntityID") String seriesEntityID, @RequestBody IntegerValue integerValue) {
        WidgetSliderEntity entity = entityContext.getEntity(entityID);
        WidgetSliderSeriesEntity series = entity.getSeries().stream().filter(s -> s.getEntityID().equals(seriesEntityID)).findAny().orElse(null);
        if (series == null) {
            throw new NotFoundException("Unable to find series: " + seriesEntityID + " for entity: " + entity.getTitle());
        }
        BaseEntity<?> source = entityContext.getEntity(series.getDataSource());
        if (source instanceof WorkspaceStandaloneVariableEntity) {
            entityContext.save(((WorkspaceStandaloneVariableEntity) source).setValue(integerValue.value));
        } else if (source instanceof WorkspaceVariableEntity) {
            entityContext.save(((WorkspaceVariableEntity) source).setValue(integerValue.value));
        } else {
            throw new ServerException("Unable to find handler for set value for slider");
        }
    }

    @PostMapping("/toggle/{entityID}/series/{seriesEntityID}")
    public void updateToggleValue(@PathVariable("entityID") String entityID, @PathVariable("seriesEntityID") String seriesEntityID, @RequestBody BooleanValue booleanValue) {
        WidgetToggleEntity entity = entityContext.getEntity(entityID);
        WidgetToggleSeriesEntity series = entity.getSeries().stream().filter(s -> s.getEntityID().equals(seriesEntityID)).findAny().orElse(null);
        if (series == null) {
            throw new NotFoundException("Unable to find series: " + seriesEntityID + " for entity: " + entity.getTitle());
        }
        BaseEntity<?> source = entityContext.getEntity(series.getDataSource());
        if (source instanceof WorkspaceBooleanEntity) {
            entityContext.save(((WorkspaceBooleanEntity) source).setValue(booleanValue.value));
        } else {
            throw new ServerException("Unable to find handler for set value for slider");
        }
    }

    @GetMapping("/{entityID}")
    public WidgetBaseEntity getWidget(@PathVariable("entityID") String entityID) {
        WidgetBaseEntity widget = entityContext.getEntity(entityID);
        updateWidgetBeforeReturnToUI(widget);
        return widget;
    }

    @GetMapping("/{tabId}/widget")
    public List<WidgetBaseEntity> getWidgets(@PathVariable("tabId") String tabId) {
        List<WidgetBaseEntity> widgets = entityContext.findAll(WidgetBaseEntity.class)
                .stream().filter(w -> w.getWidgetTabEntity().getEntityID().equals(tabId)).collect(Collectors.toList());

        boolean updated = false;
        for (WidgetBaseEntity<?> widget : widgets) {
            updated |= widget.updateRelations(entityContext);
        }
        if (updated) {
            widgets = entityContext.findAll(WidgetBaseEntity.class);
        }
        for (WidgetBaseEntity<?> widget : widgets) {
            updateWidgetBeforeReturnToUI(widget);
        }

        return widgets;
    }

    private void updateWidgetBeforeReturnToUI(WidgetBaseEntity<?> widget) {
        if (widget instanceof WidgetJsEntity) {
            WidgetJsEntity jsEntity = (WidgetJsEntity) widget;
            try {
                jsEntity.setJavaScriptErrorResponse(null);
                ScriptEntity scriptEntity = new ScriptEntity()
                        .setJavaScript(jsEntity.getJavaScript())
                        .setJavaScriptParameters(jsEntity.getJavaScriptParameters());

                CompileScriptContext compileScriptContext = scriptService.createCompiledScript(scriptEntity, null);
                jsEntity.setJavaScriptResponse(scriptService.runJavaScript(compileScriptContext));

            } catch (Exception ex) {
                jsEntity.setJavaScriptErrorResponse(TouchHomeUtils.getErrorMessage(ex));
            }
        }
    }

    @Secured(PRIVILEGED_USER_ROLE)
    @PostMapping("/create/{tabId}/{type}")
    public BaseEntity<?> createWidget(@PathVariable("tabId") String tabId, @PathVariable("type") String type) throws Exception {
        log.debug("Request creating widget entity by type: <{}> in tabId <{}>", type, tabId);
        WidgetTabEntity widgetTabEntity = entityContext.getEntity(tabId);
        if (widgetTabEntity == null) {
            throw new NotFoundException("Unable to find tab with tabId: " + tabId);
        }

        Class<? extends BaseEntity> typeClass = EntityContextImpl.baseEntityNameToClass.get(type);
        WidgetBaseEntity<?> baseEntity = (WidgetBaseEntity<?>) typeClass.getConstructor().newInstance();

        baseEntity.setWidgetTabEntity(widgetTabEntity);
        return entityContext.save(baseEntity);
    }

    @Secured(PRIVILEGED_USER_ROLE)
    @PostMapping("/create/{tabId}/{type}/{bundle}")
    public BaseEntity<?> createExtraWidget(@PathVariable("tabId") String tabId, @PathVariable("type") String type, @PathVariable("bundle") String bundle) {
        log.debug("Request creating extra widget entity by type: <{}> in tabId <{}>, bundle: <{}>", type, tabId, bundle);
        WidgetTabEntity widgetTabEntity = entityContext.getEntity(tabId);
        if (widgetTabEntity == null) {
            throw new NotFoundException("Unable to find tab with tabId: " + tabId);
        }

        Collection<WidgetBaseTemplate> widgets = entityContext.getBeansOfTypeByBundles(WidgetBaseTemplate.class).get(bundle);
        if (widgets == null) {
            throw new NotFoundException("Unable to find bundle: " + tabId + " or widgets in bundle");
        }
        WidgetBaseTemplate template = widgets.stream().filter(w -> w.getClass().getSimpleName().equals(type)).findAny()
                .orElseThrow(() -> new NotFoundException("Unable to find widget: " + type + " in bundle: " + bundle));

        String js = template.toJavaScript();
        String params = "";
        boolean paramReadOnly = false;
        if (js == null) {
            if (template instanceof WidgetJSBaseTemplate) {
                WidgetJSBaseTemplate widgetJSBaseTemplate = (WidgetJSBaseTemplate) template;
                JavaScriptBuilderImpl javaScriptBuilder = new JavaScriptBuilderImpl(template.getClass());
                widgetJSBaseTemplate.createWidget(javaScriptBuilder);
                js = javaScriptBuilder.build();
                paramReadOnly = javaScriptBuilder.isJsonReadOnly();
                params = javaScriptBuilder.getJsonParams().toString();
            }
        }

        WidgetJsEntity widgetJsEntity = new WidgetJsEntity()
                .setJavaScriptParameters(params)
                .setJavaScriptParametersReadOnly(paramReadOnly)
                .setJavaScript(js);

        widgetJsEntity.setWidgetTabEntity(widgetTabEntity)
                .setFieldFetchType(bundle + ":" + template.getClass().getSimpleName())
                .setAutoScale(template.isDefaultAutoScale());

        return entityContext.save(widgetJsEntity);
    }

    @GetMapping("/tab")
    public List<OptionModel> getWidgetTabs() {
        return entityContext.findAll(WidgetTabEntity.class).stream().sorted()
                .map(t -> OptionModel.of(t.getEntityID(), t.getName())).collect(Collectors.toList());
    }

    @SneakyThrows
    @PostMapping("/tab/{name}")
    public OptionModel createWidgetTab(@PathVariable("name") String name) {
        BaseEntity<?> widgetTab = entityContext.getEntity(WidgetTabEntity.PREFIX + name);
        if (widgetTab == null) {
            widgetTab = entityContext.save(new WidgetTabEntity().computeEntityID(() -> name));
            return OptionModel.of(widgetTab.getEntityID(), widgetTab.getName());
        }
        throw new ServerException("Widget tab with same name already exists");
    }

    @SneakyThrows
    @PutMapping("/tab/{tabId}/{name}")
    @Secured(ADMIN_ROLE)
    public void renameWidgetTab(@PathVariable("tabId") String tabId, @PathVariable("name") String name) {
        if (!WidgetTabEntity.GENERAL_WIDGET_TAB_NAME.equals(name)) {

            WidgetTabEntity entity = getWidgetTabEntity(tabId);
            WidgetTabEntity newEntity = entityContext.getEntityByName(name, WidgetTabEntity.class);

            if (newEntity == null) {
                entityContext.save(entity.setName(name));
            }
        }
    }

    @DeleteMapping("/tab/{tabId}")
    @Secured(ADMIN_ROLE)
    public void deleteWidgetTab(@PathVariable("tabId") String tabId) {
        WidgetTabEntity widgetTabEntity = getWidgetTabEntity(tabId);
        if (!WidgetTabEntity.GENERAL_WIDGET_TAB_NAME.equals(widgetTabEntity.getName())) {
            entityContext.delete(widgetTabEntity);
        }
    }

    private WidgetTabEntity getWidgetTabEntity(String tabId) {
        BaseEntity<?> baseEntity = entityContext.getEntity(tabId);
        if (baseEntity instanceof WidgetTabEntity) {
            return (WidgetTabEntity) baseEntity;
        }
        throw new ServerException("Unable to find widget tab with id: " + tabId);
    }

    private Float fetchVariableValue(BaseEntity<?> dataSource) {
        if (dataSource instanceof WorkspaceStandaloneVariableEntity) {
            return ((WorkspaceStandaloneVariableEntity) dataSource).getValue();
        } else if (dataSource instanceof WorkspaceVariableEntity) {
            return ((WorkspaceVariableEntity) dataSource).getValue();
        } else {
            throw new ServerException("Unable to find handler for fetch value from <Data Source>: " + dataSource.getTitle());
        }
    }

    private <T> T getRepository(BaseEntity<?> source, Class<T> repositoryAbility) {
        AbstractRepository<?> abstractRepository = entityContext.getRepository(source).orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repositoryAbility.isAssignableFrom(abstractRepository.getClass())) {
            throw new ServerException("Repository: " + abstractRepository.getClass().getSimpleName() + " must implement <" + repositoryAbility.getSimpleName() + "> interface");
        }
        return (T) abstractRepository;
    }

   /* @RequestMapping(value = "/getWidget", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChartWidgetJSON getMiniBoxWidget(@RequestBody WidgetJsEntity miniBoxWidgetEntity) {
        ChartBuilder chartBuilder = ChartBuilder.create(miniBoxWidgetEntity);
        ScriptEntity scriptEntity = ScriptEntity.create(miniBoxWidgetEntity.getJavaScript(),
                miniBoxWidgetEntity.getEntityID(),
                miniBoxWidgetEntity.getJavaScriptParameters());
        if (StringUtils.isNotEmpty(scriptEntity.getJavaScript())) {
            Object retValue;
            try {
                retValue = scriptManager.executeJavaScriptOnce(scriptEntity, null, null, false);
            } catch (Exception ex) {
                retValue = "<pre style='max-width:600px;max-height:400px;'>" + ExceptionUtils.getStackTrace(ex) + "</pre>";
            }
            chartBuilder.withXAxis().data(Collections.singletonList(retValue == null ? "script returns no value" : retValue.toString()));
        }

        return chartBuilder.build();
    }*/

    /*@RequestMapping(value = "/getChartPieWidget", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChartWidgetJSON getChartPieWidget(@RequestParam(value = "entityID") String entityID) {
        ChartPieWidgetEntity chartPieWidgetEntity = chartPieWidgetRepository.getByEntityID(entityID);
        ChartBuilder chartBuilder = ChartBuilder.create(chartPieWidgetEntity);

        final ChartBuilder.LegendBuilder legendBuilder = chartPieWidgetEntity.getShowLegend() ? chartBuilder.withLegend().withOrient(chartPieWidgetEntity.getLegendOrient()) : new ChartBuilder.LegendBuilder();
        ChartBuilder.SeriesBuilder seriesBuilder = chartBuilder.withSeries("pie", chartPieWidgetEntity.getTitle()).withRadius(50, 70);

        if (chartPieWidgetEntity.getBehaviourType() == ChartPieWidgetEntity.ChartPieBehaviourType.ItemsCount) {
            // ALL
            if (chartPieWidgetEntity.getTargetItems().contains(""))
                entityManager.getRepositories().stream()
                        .filter(abstractRepository -> abstractRepository.getEntityClass().getDeclaredAnnotation(UISidebarMenu.class) != null)
                        .forEach(abstractRepository -> {
                            Class<?> clazz = abstractRepository.getEntityClass();
                            String href = SidebarMenuItem.getHref(clazz);
                            legendBuilder.withData(href);
                            seriesBuilder.withData(abstractRepository.size().intValue(), href).withItemStyleColor(TouchHomeUtils.randomColor());
                        });
        }
        return chartBuilder.build();
    }*/

    @Getter
    @AllArgsConstructor
    private static class ChartSeries {
        private String name;
        private List<Date> dates;
        private Float[] values;
    }

    @Getter
    @AllArgsConstructor
    private static class PieSeries {
        private String name;
        private Object value;
        private Extra extra;

        @Getter
        @AllArgsConstructor
        private static class Extra {
            private String code;
        }
    }

    @Getter
    @AllArgsConstructor
    private static class BarSeries {
        private String name;
        private Object value;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BarSeries barSeries = (BarSeries) o;
            return name.equals(barSeries.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }

    @Setter
    private static class IntegerValue {
        private Integer value;
    }

    @Setter
    private static class BooleanValue {
        private Boolean value;
    }
}

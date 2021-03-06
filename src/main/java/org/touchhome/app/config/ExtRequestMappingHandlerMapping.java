package org.touchhome.app.config;

import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.*;

public class ExtRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

    private Map<Object, List<RequestMappingInfo>> controllerToMethodRegisterInfo = new HashMap<>();

    public void updateContextRestControllers(ApplicationContext context, boolean register) {
        Collection<Object> restControllers = context.getBeansWithAnnotation(RestController.class).values();
        for (Object restController : restControllers) {
            if (register) {
                controllerToMethodRegisterInfo.put(restController, new ArrayList<>());
                super.detectHandlerMethods(restController);
            } else {
                for (RequestMappingInfo requestMappingInfo : controllerToMethodRegisterInfo.get(restController)) {
                    unregisterMapping(requestMappingInfo);
                }
            }
        }
    }

    @Override
    protected void registerHandlerMethod(Object handler, Method method, RequestMappingInfo mapping) {
        if (controllerToMethodRegisterInfo.containsKey(handler)) {
            controllerToMethodRegisterInfo.get(handler).add(mapping);
        }
        super.registerHandlerMethod(handler, method, mapping);
    }
}

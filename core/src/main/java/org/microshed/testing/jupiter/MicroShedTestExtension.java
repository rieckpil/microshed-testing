/*
 * Copyright (c) 2019 IBM Corporation and others
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.microshed.testing.jupiter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.microshed.testing.ApplicationEnvironment;
import org.microshed.testing.jaxrs.RESTClient;
import org.microshed.testing.jaxrs.RestClientBuilder;
import org.microshed.testing.jwt.JwtBuilder;
import org.microshed.testing.jwt.JwtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit Jupiter extension that is applied whenever the <code>@MicroProfileTest</code> is used on a test class.
 * Currently this is tied to Testcontainers managing runtime build/deployment, but in a future version
 * it could be refactored to allow for a different framework managing the runtime build/deployment.
 */
class MicroShedTestExtension implements BeforeAllCallback {

    static final Logger LOGGER = LoggerFactory.getLogger(MicroShedTestExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();
        ApplicationEnvironment config = ApplicationEnvironment.load();
        LOGGER.info("Using ApplicationEnvironment class: " + config.getClass().getCanonicalName());
        config.applyConfiguration(testClass);
        config.start();
        injectRestClients(testClass);
    }

    private static void injectRestClients(Class<?> clazz) {
        List<Field> restClientFields = new ArrayList<>();
        restClientFields.addAll(AnnotationSupport.findAnnotatedFields(clazz, RESTClient.class));
        // Also tolerate people using the MicroProfile @RestClient annotation instead
        getMpRestClient().ifPresent(mpRestClient -> {
            restClientFields.addAll(AnnotationSupport.findAnnotatedFields(clazz, mpRestClient));
        });
        if (restClientFields.size() == 0)
            return;

        for (Field restClientField : restClientFields) {
            if (!Modifier.isPublic(restClientField.getModifiers()) ||
                !Modifier.isStatic(restClientField.getModifiers()) ||
                Modifier.isFinal(restClientField.getModifiers())) {
                throw new ExtensionConfigurationException("REST client field must be public, static, and non-final: " + restClientField);
            }
            RestClientBuilder rcBuilder = new RestClientBuilder();
            String jwt = createJwtIfNeeded(restClientField);
            if (jwt != null)
                rcBuilder.withJwt(jwt);
            Object restClient = rcBuilder.build(restClientField.getType());
            try {
                restClientField.set(null, restClient);
                LOGGER.debug("Injected rest client for " + restClientField);
            } catch (Exception e) {
                throw new ExtensionConfigurationException("Unable to set field " + restClientField, e);
            }
        }
    }

    private static String createJwtIfNeeded(Field restClientField) {
        Field f = restClientField;
        JwtConfig anno = f.getDeclaredAnnotation(JwtConfig.class);
        if (anno != null) {
            try {
                return JwtBuilder.buildJwt(anno.subject(), anno.issuer(), anno.claims());
            } catch (Exception e) {
                throw new ExtensionConfigurationException("Error while building JWT for field " + f + " with JwtConfig: " + anno, e);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Class<? extends Annotation>> getMpRestClient() {
        try {
            return Optional.of((Class<? extends Annotation>) Class.forName("org.eclipse.microprofile.rest.client.inject.RestClient",
                                                                           false,
                                                                           MicroShedTestExtension.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }
}

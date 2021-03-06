/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.smallrye.config;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import javax.annotation.Priority;

import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.eclipse.microprofile.config.spi.Converter;

/**
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2017 Red Hat inc.
 */
public class SmallRyeConfigBuilder implements ConfigBuilder {

    private static final String META_INF_MICROPROFILE_CONFIG_PROPERTIES = "META-INF/microprofile-config.properties";
    private static final String WEB_INF_MICROPROFILE_CONFIG_PROPERTIES = "WEB-INF/classes/META-INF/microprofile-config.properties";

    // sources are not sorted by their ordinals
    private List<ConfigSource> sources = new ArrayList<>();
    private Function<ConfigSource, ConfigSource> sourceWrappers = UnaryOperator.identity();
    private Map<Type, ConverterWithPriority> converters = new HashMap<>();
    private List<ConfigSourceInterceptor> interceptors = new ArrayList<>();
    private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    private boolean addDefaultSources = false;
    private boolean addDiscoveredSources = false;
    private boolean addDiscoveredConverters = false;
    private boolean addDiscoveredInterceptors = false;

    public SmallRyeConfigBuilder() {
    }

    @Override
    public SmallRyeConfigBuilder addDiscoveredSources() {
        addDiscoveredSources = true;
        return this;
    }

    @Override
    public SmallRyeConfigBuilder addDiscoveredConverters() {
        addDiscoveredConverters = true;
        return this;
    }

    public SmallRyeConfigBuilder addDiscoveredInterceptors() {
        addDiscoveredInterceptors = true;
        return this;
    }

    List<ConfigSource> discoverSources() {
        List<ConfigSource> discoveredSources = new ArrayList<>();
        ServiceLoader<ConfigSource> configSourceLoader = ServiceLoader.load(ConfigSource.class, classLoader);
        configSourceLoader.forEach(discoveredSources::add);

        // load all ConfigSources from ConfigSourceProviders
        ServiceLoader<ConfigSourceProvider> configSourceProviderLoader = ServiceLoader.load(ConfigSourceProvider.class,
                classLoader);
        configSourceProviderLoader.forEach(configSourceProvider -> configSourceProvider.getConfigSources(classLoader)
                .forEach(discoveredSources::add));
        return discoveredSources;
    }

    List<Converter> discoverConverters() {
        List<Converter> discoveredConverters = new ArrayList<>();
        ServiceLoader<Converter> converterLoader = ServiceLoader.load(Converter.class, classLoader);
        converterLoader.forEach(discoveredConverters::add);
        return discoveredConverters;
    }

    List<ConfigSourceInterceptor> discoverInterceptors() {
        List<ConfigSourceInterceptor> interceptors = new ArrayList<>();
        ServiceLoader<ConfigSourceInterceptor> interceptorLoader = ServiceLoader.load(ConfigSourceInterceptor.class,
                classLoader);
        interceptorLoader.forEach(interceptors::add);
        return interceptors;
    }

    @Override
    public SmallRyeConfigBuilder addDefaultSources() {
        addDefaultSources = true;
        return this;
    }

    List<ConfigSource> getDefaultSources() {
        List<ConfigSource> defaultSources = new ArrayList<>();

        defaultSources.add(new EnvConfigSource());
        defaultSources.add(new SysPropConfigSource());
        defaultSources.addAll(new PropertiesConfigSourceProvider(META_INF_MICROPROFILE_CONFIG_PROPERTIES, true, classLoader)
                .getConfigSources(classLoader));
        defaultSources.addAll(new PropertiesConfigSourceProvider(WEB_INF_MICROPROFILE_CONFIG_PROPERTIES, true, classLoader)
                .getConfigSources(classLoader));

        return defaultSources;
    }

    @Override
    public SmallRyeConfigBuilder forClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    @Override
    public SmallRyeConfigBuilder withSources(ConfigSource... configSources) {
        Collections.addAll(sources, configSources);
        return this;
    }

    public SmallRyeConfigBuilder withSources(Collection<ConfigSource> configSources) {
        sources.addAll(configSources);
        return this;
    }

    public SmallRyeConfigBuilder withInterceptors(ConfigSourceInterceptor... interceptors) {
        Collections.addAll(this.interceptors, interceptors);
        return this;
    }

    @Override
    public SmallRyeConfigBuilder withConverters(Converter<?>[] converters) {
        for (Converter<?> converter : converters) {
            Type type = Converters.getConverterType(converter.getClass());
            if (type == null) {
                throw new IllegalStateException(
                        "Can not add converter " + converter + " that is not parameterized with a type");
            }
            addConverter(type, getPriority(converter), converter, this.converters);
        }
        return this;
    }

    @Override
    public <T> SmallRyeConfigBuilder withConverter(Class<T> type, int priority, Converter<T> converter) {
        addConverter(type, priority, converter, converters);
        return this;
    }

    @Deprecated
    public SmallRyeConfigBuilder withWrapper(UnaryOperator<ConfigSource> wrapper) {
        sourceWrappers = sourceWrappers.andThen(wrapper);
        return this;
    }

    static void addConverter(Type type, Converter converter, Map<Type, ConverterWithPriority> converters) {
        addConverter(type, getPriority(converter), converter, converters);
    }

    static void addConverter(Type type, int priority, Converter converter,
            Map<Type, ConverterWithPriority> converters) {
        // add the converter only if it has a higher priority than another converter for the same type
        ConverterWithPriority oldConverter = converters.get(type);
        int newPriority = getPriority(converter);
        if (oldConverter == null || priority > oldConverter.priority) {
            converters.put(type, new ConverterWithPriority(converter, newPriority));
        }
    }

    private static int getPriority(Converter<?> converter) {
        int priority = 100;
        Priority priorityAnnotation = converter.getClass().getAnnotation(Priority.class);
        if (priorityAnnotation != null) {
            priority = priorityAnnotation.value();
        }
        return priority;
    }

    List<ConfigSource> getSources() {
        return sources;
    }

    @Deprecated
    Function<ConfigSource, ConfigSource> getSourceWrappers() {
        return sourceWrappers;
    }

    Map<Type, ConverterWithPriority> getConverters() {
        return converters;
    }

    List<ConfigSourceInterceptor> getInterceptors() {
        return interceptors;
    }

    boolean isAddDefaultSources() {
        return addDefaultSources;
    }

    boolean isAddDiscoveredSources() {
        return addDiscoveredSources;
    }

    boolean isAddDiscoveredConverters() {
        return addDiscoveredConverters;
    }

    boolean isAddDiscoveredInterceptors() {
        return addDiscoveredInterceptors;
    }

    @Override
    public SmallRyeConfig build() {
        return new SmallRyeConfig(this);
    }

    static class ConverterWithPriority {
        private final Converter converter;
        private final int priority;

        private ConverterWithPriority(Converter converter, int priority) {
            this.converter = converter;
            this.priority = priority;
        }

        Converter getConverter() {
            return converter;
        }

        int getPriority() {
            return priority;
        }
    }
}

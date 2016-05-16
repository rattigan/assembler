package com.github.rattigan.assembler;

import com.github.rattigan.nonstd.futures.Deferred;
import com.github.rattigan.nonstd.futures.Promise;
import com.github.rattigan.nonstd.seq.Seq;
import com.google.inject.Binding;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.spi.DefaultElementVisitor;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.spi.PrivateElements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Provider;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.github.rattigan.nonstd.seq.Seq.*;

/**
 */
public class Assembly implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Assembly.class);

    private final Set<Component<?>> components;
    // map from importing components to exporting components
    private final Map<Component<?>, Set<Component<?>>> blockedComponents = map();
    private final Map<Component<?>, Set<Component<?>>> startBlockers = map();
    private final List<Dependency> dependencies = list();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean starting = new AtomicBoolean();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final Deferred<Void> started = new Deferred<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<Component<?>, Promise<Void>> startingComponents = map();
    private final Set<Component<?>> startedComponents = set();
    private final List<ComponentError> startupErrors = list();



    private final Lock lock = new ReentrantLock();
    private final Map<? extends Component<?>, ? extends Seq<Dependency>> dependenciesByComponent;
    private final Map<Component<?>, Injector> injectors = map();

    public Assembly(Iterable<Component<?>> components) {
        this.components = set(components);
        Map<Key<?>, Set<Component<?>>> imports = getImports(components);
        Map<Key<?>, Set<Component<?>>> exports = getExports(components);
        extractDependencies(imports, exports);
        dependenciesByComponent = seq(dependencies).groupBy(it -> it.importingComponent).asMap();

        dumpDependencies();
    }

    private void extractDependencies(Map<Key<?>, Set<Component<?>>> imports, Map<Key<?>, Set<Component<?>>> exports) {
        for (Component<?> component : components) {
            startBlockers.put(component, set());
            blockedComponents.put(component, set());
        }
        imports.forEach((key, importingComponents) -> {
            Set<Component<?>> exportingComponents = exports.get(key);
            if (exportingComponents == null)
                return;
            for (Component<?> importingComponent : importingComponents) {
                for (Component<?> exportingComponent : exportingComponents) {
                    startBlockers.get(importingComponent).add(exportingComponent);
                    blockedComponents.get(exportingComponent).add(importingComponent);
                    dependencies.add(new Dependency(key, exportingComponent, importingComponent));
                }
            }
        });
    }

    private Map<Key<?>, Set<Component<?>>> getExports(Iterable<Component<?>> components) {
        Map<Key<?>, Set<Component<?>>> exports = map();
        for (Component<?> component : components) {
            List<Element> elements = Elements.getElements(component);
            for (Element element : elements) {
                log.info(element.toString());
                element.acceptVisitor(new DefaultElementVisitor<Void>() {
                    @Override
                    public <T> Void visit(Binding<T> binding) {
                        Key<T> key = binding.getKey();
                        exports.computeIfAbsent(key, __ -> set()).add(component);
                        return null;
                    }

                    @Override
                    public Void visit(PrivateElements privateElements) {
                        Set<Key<?>> exposedKeys = privateElements.getExposedKeys();
                        for (Key<?> key : exposedKeys)
                            exports.computeIfAbsent(key, __ -> set()).add(component);
                        return null;
                    }
                });
            }
        }
        return exports;
    }

    private Map<Key<?>, Set<Component<?>>> getImports(Iterable<Component<?>> components) {
        Map<Key<?>, Set<Component<?>>> imports = map();
        for (Component<?> component : components) {
            for (Key<?> key : component.getImports())
                imports.computeIfAbsent(key, __ -> set()).add(component);
        }
        return imports;
    }

    private void dumpDependencies() {
        for (Dependency dependency : dependencies) {
            log.info(dependency.importingComponent + " gets " + dependency.key + " from " + dependency.exportingComponent);
        }
    }

    public Promise<Void> start() {
        if (!starting.compareAndSet(false, true))
            throw new AssemblerException("Assembly already started");
        startUnblockedComponents();
        return started;
    }

    public Promise<Void> stop() {
        if (starting.get()) {
            abort();
        }

        stopping.set(true);
        return null; //TODO
    }

    private void startUnblockedComponents() {
        seq(startBlockers)
                .where((__, blockers) -> blockers.isEmpty())
                .forEach((component, __) -> startComponent(component));
    }

    private void startUnblockedComponents(Component<?> component) {
        // remove this component from the startup graph, and start any components that are now ready
        Set<Component<?>> components = blockedComponents.get(component);
        if (components != null) {
            for (Component<?> blocked : components) {
                Set<Component<?>> blockers = startBlockers.get(blocked);
                blockers.remove(component);
                if (blockers.isEmpty())
                    startComponent(blocked);
            }
        }
    }

    private void startComponent(Component<?> component) {
        Promise<Void> async = Promise.async(executor, () -> {
            // inject dependencies and then start the component
            injectComponent(component);
            injectors.put(component, Guice.createInjector(component));
            component.start();
        });
        lock.lock();
        try {
            startingComponents.put(component, async);
        } finally {
            lock.unlock();
        }
        async.done((value, t) -> {
            lock.lock();
            try {
                startingComponents.remove(component);
                if (t != null) {
                    log.error("Error starting {}", component, t);
                    startupErrors.add(new ComponentError(
                            component,
                            new AssemblerException("Error starting " + component, t)));
                    abort();
                } else {
                    startedComponents.add(component);
                    if (startedComponents.size() == components.size()) {
                        started.resolve();
                    } else {
                        startUnblockedComponents(component);
                        if (startingComponents.isEmpty()) {
                            if (!startupErrors.isEmpty())
                                started.reject(startupErrors.get(0).exception);
                        } else {
                            started.resolve();
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        });
    }

    private void injectComponent(Component<?> component) {
        Injector injector = Guice.createInjector(binder -> {
            Seq<Dependency> seq = dependenciesByComponent.get(component);
            if (seq == null)
                return;
            seq.groupBy(it -> it.key)
                    .forEach((key, exportingComponents) -> {
                        binder.bind((Key<?>) key)
                                .toProvider(getProvider(key, exportingComponents));
                    });
        });
        injector.injectMembers(component);
    }

    private Provider getProvider(Key<?> key, Seq<Dependency> dependencies) {
        return () -> {
            Seq<Injector> injectors = dependencies.to(dependency ->
                    this.injectors.get(dependency.exportingComponent));
            if (dependencies.size() == 1) {
                // one exporter - simply return the single exported instance
                return injectors.iterator().next().getInstance(key);
            } else if (Set.class.isAssignableFrom(key.getTypeLiteral().getRawType())) {
                // multiple exporters - aggregate the exported instances
                return injectors.collect(set(), (collection, injector) ->
                                collection.addAll((Collection) injector.getInstance(key)));
            } else if (Map.class.isAssignableFrom(key.getTypeLiteral().getRawType())) {
                return injectors.collect(map(), (collection, injector) ->
                        collection.putAll((Map) injector.getInstance(key)));
            } else {
                return null;
            }
        };
    }

    private void abort() {
        if (aborted.compareAndSet(false, true)) {
            log.info("Aborting startup");
            if (startingComponents.size() > 0) {
                startingComponents.forEach((startingComponent, promise) -> {
                    log.info("Canceling startup of {}", startingComponent);
                    promise.cancel();
                });
            }
        }
    }

    @Override
    public void close() throws Exception {
        stop().get();
        executor.shutdownNow();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
    }

    private static class Dependency {
        final Key<?> key;
        final Component<?> exportingComponent;
        final Component<?> importingComponent;

        Dependency(Key<?> key, Component<?> exportingComponent, Component<?> importingComponent) {
            this.key = key;
            this.exportingComponent = exportingComponent;
            this.importingComponent = importingComponent;
        }
    }

    private static class ComponentError {
        final Component<?> component;
        final AssemblerException exception;

        public ComponentError(Component<?> component, AssemblerException exception) {
            this.component = component;
            this.exception = exception;
        }
    }
}


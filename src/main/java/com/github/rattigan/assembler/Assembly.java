package com.github.rattigan.assembler;

import com.github.rattigan.nonstd.futures.Deferred;
import com.github.rattigan.nonstd.futures.Promise;
import com.github.rattigan.nonstd.seq.Seq;
import com.google.inject.*;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.github.rattigan.assembler.Assembly.State.*;
import static com.github.rattigan.nonstd.futures.Promise.async;
import static com.github.rattigan.nonstd.seq.Seq.*;
import static java.util.Collections.emptySet;

/**
 */
public class Assembly implements AutoCloseable {
    enum State {STARTING, ABORTING_STARTUP, STARTED, STOPPING, STOPPED}

    private static final Logger log = LoggerFactory.getLogger(Assembly.class);

    private final Set<Component> components;
    // map from importing components to exporting components
    private final List<Dependency> dependencies = list();
    private final Map<? extends Component, ? extends Seq<Dependency>> dependenciesByComponent;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // TODO use atomic Status
    private final AtomicReference<State> state = new AtomicReference<>();

    private class Graph {
        private final Map<Component, Set<Component>> successors;
        private final Map<Component, Set<Component>> predecessors;
        final Deferred<Void> finished = new Deferred<>();
        final Map<Component, Promise<Void>> activeComponents = map();
        final Set<Component> finishedComponents = set();
        final List<ComponentError> errors = list();

        private Graph(Map<Component, Set<Component>> successors, Map<Component, Set<Component>> predecessors) {
            this.successors = successors;
            this.predecessors = predecessors;
        }
    }

    private final Graph start;
    private final Graph stop;

    private final Deferred<Void> readyForStop = new Deferred<>();

    private final Lock lock = new ReentrantLock();

    private final Map<Component, Injector> injectors = map();

    public Assembly(Iterable<Component> components) {
        this.components = set(components);
        Map<Key<?>, Set<Component>> imports = getImports(components);
        Map<Key<?>, Set<Component>> exports = getExports(components);
        extractDependencies(imports, exports);
        dependenciesByComponent = seq(dependencies)
                .groupBy(it -> it.importingComponent)
                .collect(map());
        start = new Graph(getComponentDag(false), getComponentDag(true));
        stop = new Graph(getComponentDag(true), getComponentDag(false));
        dumpDependencies();
    }

    private Map<Component, Set<Component>> getComponentDag(boolean invert) {
        Map<Component, Set<Component>> collect = seq(dependencies)
                .groupBy(
                        d -> invert ? d.importingComponent : d.exportingComponent,
                        d -> invert ? d.exportingComponent : d.importingComponent)
                .toValues((k, v) -> v.collect(set()))
                .collect(map());
        // add empty sets for any components without dependencies
        for (Component component : components)
            collect.computeIfAbsent(component, c -> emptySet());
        return collect;
    }

    private void extractDependencies(Map<Key<?>, Set<Component>> imports, Map<Key<?>, Set<Component>> exports) {
        imports.forEach((key, importingComponents) -> {
            Set<Component> exportingComponents = exports.get(key);
            if (exportingComponents == null)
                return;
            for (Component importingComponent : importingComponents) {
                for (Component exportingComponent : exportingComponents) {
                    dependencies.add(new Dependency(key, exportingComponent, importingComponent));
                }
            }
        });
    }

    private Map<Key<?>, Set<Component>> getExports(Iterable<Component> components) {
        Map<Key<?>, Set<Component>> exports = map();
        for (Component component : components) {
            List<Element> elements = Elements.getElements(component);
            for (Element element : elements) {
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

    private Map<Key<?>, Set<Component>> getImports(Iterable<Component> components) {
        Map<Key<?>, Set<Component>> imports = map();
        for (Component component : components) {
            for (Key<?> key : component.getImports())
                imports.computeIfAbsent(key, __ -> set()).add(component);
        }
        return imports;
    }

    private void dumpDependencies() {
        StringBuilder dot = new StringBuilder();
        dot.append("digraph assembly {\n");
        for (Component component : components)
            dot.append(getNodeName(component) + ";\n");

        for (Dependency dependency : dependencies) {
            dot.append(
                    getNodeName(dependency.exportingComponent) + " -> " +
                            getNodeName(dependency.importingComponent) + " [ label=\"" + describeKey(dependency.key) + "\" ]\n");
            log.info(dependency.importingComponent + " gets " + dependency.key + " from " + dependency.exportingComponent);
        }

        dot.append("}\n");
        System.out.println(dot.toString());
    }

    private String describeKey(Key<?> key) {
        return key.toString();
    }

    private String getNodeName(Component component) {
        return "\"" + component + "\"";
    }

    private boolean transition(State newState) {
        boolean transitioned = this.state.updateAndGet(state -> {
            switch (newState) {
                case STARTING:
                    if (state == null)
                        return newState;
                    break;
                case ABORTING_STARTUP:
                    if (state == STARTING)
                        return newState;
                    if (state == STARTED)
                        return STARTED; // nothing to abort
                    break;
                case STARTED:
                    if (state == STARTING || state == ABORTING_STARTUP)
                        return newState;
                    break;
                case STOPPING:
                    if (state == STARTED)
                        return newState;
                    break;
                case STOPPED:
                    if (state == STOPPING)
                        return newState;
                    break;
            }
            throw new AssemblerException("Invalid state transition from " + state + " to " + newState);
        }) == newState;
        if (transitioned)
            log.info("Assembly is " + newState);
        return transitioned;
    }

    public Promise<Void> start() {
        transition(STARTING);
        startUnblockedComponents();
        return start.finished.alwaysRun(() -> {
            transition(STARTED);
            readyForStop.resolve();
        });
    }

    public Promise<Void> stop() {
        abortStart();
        readyForStop.alwaysRun(() -> {
            transition(STOPPING);
            stopUnblockedComponents();
        });
        return stop.finished.alwaysRun(() -> {
            transition(STOPPED);
        });
    }

    private void startUnblockedComponents() {
        lock.lock();
        try {
            biseq(start.predecessors)
                    .where((__, blockers) -> blockers.isEmpty())
                    .forEach((component, __) -> startComponent(component));
        } finally {
            lock.unlock();
        }
    }

    private void startUnblockedComponents(Component startedComponent) {
        // remove this component from the startup graph, and start any components that are now ready
        Set<Component> components = start.successors.get(startedComponent);
        if (components != null) {
            for (Component blocked : components) {
                Set<Component> blockers = start.predecessors.get(blocked);
                blockers.remove(startedComponent);
                if (blockers.isEmpty())
                    startComponent(blocked);
            }
        }
    }

    private void startComponent(Component component) {
        log.debug("Submitting component " + component + " for start");
        Promise<Void> async = async(executor, () -> {
            log.debug("Injecting component " + component);
            // inject dependencies and then start the component
            injectComponent(component);
            injectors.put(component, Guice.createInjector(Stage.PRODUCTION, component));
            log.debug("Starting component " + component);
            component.start();
        });
        lock.lock();
        try {
            start.activeComponents.put(component, async);
        } finally {
            lock.unlock();
        }
        async.done((value, t) -> {
            lock.lock();
            try {
                start.activeComponents.remove(component);
                start.finishedComponents.add(component);
                if (t == null) {
                    log.info("Started component {}", component);
                } else {
                    log.error("Error starting component {}", component, t);
                    start.errors.add(new ComponentError(
                            component,
                            new AssemblerException("Error starting component " + component, t)));
                    abortStart();
                }
                if (state.get() == STARTING) {
                    if (start.finishedComponents.size() == components.size()) {
                        log.info("All components have started");
                        start.finished.resolve();
                    } else {
                        startUnblockedComponents(component);
                        checkForStartupDeadlock();
                    }
                } else {
                    // only reject once all starting components have completed or been canceled
                    if (start.activeComponents.isEmpty()) {
                        log.info("All submitted components have startGraph.finished or aborted");
                        start.finished.reject(getStartupException());
                    }
                }
            } finally {
                lock.unlock();
            }
        });
    }

    private void checkForStartupDeadlock() {
        if (start.finishedComponents.size() < components.size() &&
                start.activeComponents.isEmpty()) {
            log.error("Startup is deadlocked");
            start.finished.reject(getStartupDeadlockException());
        }
    }

    private void stopUnblockedComponents() {
        lock.lock();
        try {
            biseq(stop.predecessors)
                    .where((__, blockers) -> blockers.isEmpty())
                    .forEach((component, __) -> stopComponent(component));
        } finally {
            lock.unlock();
        }
    }

    private void stopUnblockedComponents(Component stoppedComponent) {
        // remove this component from the shutdown graph, and stop any components that are now ready
        Set<Component> components = stop.successors.get(stoppedComponent);
        if (components != null) {
            for (Component blocked : components) {
                Set<Component> blockers = stop.predecessors.get(blocked);
                blockers.remove(stoppedComponent);
                if (blockers.isEmpty())
                    stopComponent(blocked);
            }
        }
    }

    private void stopComponent(Component component) {
        if (start.finishedComponents.contains(component)) {
            log.debug("Submitting component " + component + " for stop");
            Promise<Void> async = async(executor, () -> {
                log.debug("Stopping component " + component);
                component.stop();
            });
            lock.lock();
            try {
                stop.activeComponents.put(component, async);
            } finally {
                lock.unlock();
            }
            async.done((value, t) -> {
                lock.lock();
                try {
                    stop.activeComponents.remove(component);
                    stop.finishedComponents.add(component);
                    if (t == null) {
                        log.info("Stopped component {}", component);
                    } else {
                        log.error("Error stopping component {}", component, t);
                        stop.errors.add(new ComponentError(
                                component,
                                new AssemblerException("Error stopping component " + component, t)));
                    }
                    if (stop.finishedComponents.size() == components.size()) {
                        log.info("All components have stopped");
                        if (stop.errors.isEmpty()) {
                            stop.finished.resolve();
                        } else {
                            stop.finished.reject(getShutdownException());
                        }
                    } else {
                        stopUnblockedComponents(component);
                        checkForShutdownDeadlock();
                    }
                } finally {
                    lock.unlock();
                }
            });
        } else {
            lock.lock();
            try {
                log.debug("Not stopping component " + component + " as it was never started");
                stop.finishedComponents.add(component);
                stopUnblockedComponents(component);
            } finally {
                lock.unlock();
            }
        }
    }

    private void checkForShutdownDeadlock() {
        lock.lock();
        try {
            if (stop.finishedComponents.size() < components.size() &&
                stop.activeComponents.isEmpty()) {
                log.error("Shutdown is deadlocked");
                stop.finished.reject(getShutdownDeadlockException());
            }
        } finally {
            lock.unlock();
        }
    }

    private AssemblerException getStartupException() {
        return start.errors.get(0).exception;
    }

    private Throwable getStartupDeadlockException() {
        return null; // TODO
    }

    private Throwable getShutdownException() {
        return null; // TODO
    }

    private Throwable getShutdownDeadlockException() {
        return null; // TODO
    }

    private void injectComponent(Component component) {
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

    private void abortStart() {
        if (transition(ABORTING_STARTUP)) {
            if (start.activeComponents.size() > 0) {
                log.info("Aborting startup");
                start.activeComponents.forEach((startingComponent, promise) -> {
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
        final Component exportingComponent;
        final Component importingComponent;

        Dependency(Key<?> key, Component exportingComponent, Component importingComponent) {
            this.key = key;
            this.exportingComponent = exportingComponent;
            this.importingComponent = importingComponent;
        }
    }

    private static class ComponentError {
        final Component component;
        final AssemblerException exception;

        public ComponentError(Component component, AssemblerException exception) {
            this.component = component;
            this.exception = exception;
        }
    }
}


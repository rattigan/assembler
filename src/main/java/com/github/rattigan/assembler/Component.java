package com.github.rattigan.assembler;

import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.spi.InjectionPoint;
import com.google.inject.util.Types;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;

import static com.github.rattigan.nonstd.exceptions.Exceptions.unchecked;
import static com.github.rattigan.nonstd.seq.Seq.seq;
import static com.github.rattigan.nonstd.seq.Seq.set;

/**
 */
public abstract class Component<T> extends PrivateModule {
    private T config;
    private Set<InjectionPoint> injectionPoints =
            InjectionPoint.forInstanceMethodsAndFields(this.getClass());

    void setConfig(T config) {
        this.config = config;
    }

    public T getConfig() {
        return config;
    }

    @Override
    protected final void configure() {
        bindComponentInjections();
        configureComponent();
    }

    public Set<Key<?>> getImports() {
        return seq(injectionPoints)
                .to(it -> it.getDependencies().get(0).getKey())
                .collect(set());
    }

    private void bindComponentInjections() {
        for (InjectionPoint injectionPoint : injectionPoints) {
            Field field = (Field) injectionPoint.getMember();
            Key key = injectionPoint.getDependencies().get(0).getKey();
            bind(key).toProvider(() -> {
                try {
                    return field.get(this);
                } catch (IllegalAccessException e) {
                    throw unchecked(e);
                }
            });
        }
    }

    protected void configureComponent() {};

    void start() {}

    void stop() {}


    protected class MultibindingBuilder<T> {
        private TypeLiteral<T> type;
        private Key<T> key;
        private boolean exposed;
        private Class<? extends Annotation> annotationType;
        private Annotation annotation;

        public MultibindingBuilder(Class<T> type) {
            this.type = TypeLiteral.get(type);
        }
        public MultibindingBuilder(TypeLiteral<T> type) {
            this.type = type;
        }
        public MultibindingBuilder(Key<T> key) {
            this.key = key;
        }

        public MultibindingBuilder<T> annotatedWith(Class<? extends Annotation> annotationType) {
            this.annotationType = annotationType;
            return this;
        }

        public MultibindingBuilder<T> annotatedWith(Annotation annotation) {
            this.annotation = annotation;
            return this;
        }


        public MultibindingBuilder<T> exposed() {
            this.exposed = true;
            return this;
        }

        public Multibinder<T> build() {
            Multibinder<T> binder;
            if (key == null) {
                if (annotationType != null) {
                    binder = Multibinder.newSetBinder(binder(), type, annotationType);
                    if (exposed) {
                        expose(getBindingTypeLiteral(type.getType()))
                                .annotatedWith(annotationType);
                    }
                } else if (annotation != null) {
                    binder = Multibinder.newSetBinder(binder(), type, annotation);
                    if (exposed) {
                        expose(getBindingTypeLiteral(type.getType()))
                                .annotatedWith(annotation);
                    }
                } else {
                    binder = Multibinder.newSetBinder(binder(), type);
                    if (exposed)
                        expose(getBindingTypeLiteral(type.getType()));
                }
            } else {
                binder = Multibinder.newSetBinder(binder(), key);
                if (exposed) {
                    expose(getBindingTypeLiteral(key.getTypeLiteral().getType()))
                            .annotatedWith(key.getAnnotation());
                }
            }
            return binder;
        }

        private TypeLiteral<?> getBindingTypeLiteral(Type type) {
            return TypeLiteral.get(Types.newParameterizedType(Set.class, type));
        }
    }

    protected class MapBindingBuilder<K, V> {
        private final TypeLiteral<K> keyType;
        private final TypeLiteral<V> valueType;
        private Class<? extends Annotation> annotationType;
        private Annotation annotation;
        private boolean exposed;

        public MapBindingBuilder(Class<K> keyType, Class<V> valueType) {
            this.keyType = TypeLiteral.get(keyType);
            this.valueType = TypeLiteral.get(valueType);
        }

        public MapBindingBuilder(TypeLiteral<K> keyType, TypeLiteral<V> valueType) {
            this.keyType = keyType;
            this.valueType = valueType;
        }

        public MapBindingBuilder<K, V> annotatedWith(Class<? extends Annotation> annotationType) {
            this.annotationType = annotationType;
            return this;
        }

        public MapBindingBuilder<K, V> annotatedWith(Annotation annotation) {
            this.annotation = annotation;
            return this;
        }


        public MapBindingBuilder<K, V> exposed() {
            this.exposed = true;
            return this;
        }

        public MapBinder<K, V> build() {
            MapBinder<K, V> binder;
            if (annotationType != null) {
                binder = MapBinder.newMapBinder(binder(), keyType, valueType, annotationType);
                if (exposed) {
                    expose(getBindingTypeLiteral())
                            .annotatedWith(annotationType);
                }
            } else if (annotation != null) {
                binder = MapBinder.newMapBinder(binder(), keyType, valueType, annotation);
                if (exposed) {
                    expose(getBindingTypeLiteral())
                            .annotatedWith(annotation);
                }
            } else {
                binder = MapBinder.newMapBinder(binder(), keyType, valueType);
                if (exposed)
                    expose(getBindingTypeLiteral());
            }
            return binder;
        }

        private TypeLiteral<?> getBindingTypeLiteral() {
            return TypeLiteral.get(Types.newParameterizedType(Map.class, keyType.getType(), valueType.getType()));
        }

    }

    protected <T> MultibindingBuilder<T> multibinding(Class<T> type) {
        return new MultibindingBuilder<>(type);
    }
    protected <T> MultibindingBuilder<T> multibinding(TypeLiteral<T> type) {
        return new MultibindingBuilder<>(type);
    }
    protected <T> MultibindingBuilder<T> multibinding(Key<T> key) {
        return new MultibindingBuilder<>(key);
    }

    protected <K, V> MapBindingBuilder<K, V> mapBinding(Class<K> keyType, Class<V> valueType) {
        return new MapBindingBuilder<>(keyType, valueType);
    }

    protected <K, V> MapBindingBuilder<K, V> mapBinding(TypeLiteral<K> keyType, TypeLiteral<V> valueType) {
        return new MapBindingBuilder<>(keyType, valueType);
    }
}

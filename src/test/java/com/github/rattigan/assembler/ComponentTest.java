package com.github.rattigan.assembler;

import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.multibindings.Multibinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.Set;

import static com.github.rattigan.nonstd.seq.Seq.seqOf;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 */
public class ComponentTest {
    private static final Logger log = LoggerFactory.getLogger(ComponentTest.class);

    @Retention(RUNTIME)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    @BindingAnnotation
    public @interface Foo {
    }

    public static class A extends Component {
        @Override
        protected void configureComponent() {
            // public binding
            bind(String.class).toInstance("foo");
            expose(String.class);

            // public multibinding
            Multibinder<Double> doubleBinder = multibinding(Double.class)
                    .annotatedWith(Foo.class)
                    .exposed()
                    .build();

            doubleBinder.addBinding().toInstance(1d);

            // public map binding
            MapBinder<String, String> mapBinder = mapBinding(String.class, String.class)
                    .annotatedWith(Foo.class)
                    .exposed()
                    .build();

            mapBinder.addBinding("Hi").toInstance("Bob");

            // private
            bind(Integer.class).toInstance(5);
        }
    }

    public static class B extends Component {
        @Inject
        private String foo;

        @Override
        protected void configureComponent() {
            // public multibinding
            Multibinder<Double> doubleBinder = multibinding(Double.class)
                    .annotatedWith(Foo.class)
                    .exposed()
                    .build();
            doubleBinder.addBinding().toInstance(2d);

            // public map binding
            MapBinder<String, String> mapBinder = mapBinding(String.class, String.class)
                    .annotatedWith(Foo.class)
                    .exposed()
                    .build();

            mapBinder.addBinding("Bye").toInstance("Barry");
        }

//        @Override
//        void start() {
//            throw new RuntimeException("start");
//        }
//
//        @Override
//        void stop() {
//            throw new RuntimeException("stop");
//        }
    }

    public static class C extends Component {
        @Inject
        @Foo
        private Set<Double> foo;

        @Inject
        @Foo
        private Map<String, String> bar;
    }

    @Test
    public void test() {
        Assembly assembly = new Assembly(seqOf(new A(), new B(), new C()));
        try {
            assembly.start().get();
        } finally {
            assembly.stop().get();
        }
        log.info("ooh");
    }
}

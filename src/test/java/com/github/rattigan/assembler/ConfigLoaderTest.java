package com.github.rattigan.assembler;

import org.testng.annotations.Test;

import java.util.List;

/**
 */
public class ConfigLoaderTest {
    public static class Foo extends Component {
        private String bar;
        private int baz;
        private List<Bar> bars;
    }

    public static class Bar extends Component {
        private String a;
        private String b;
    }

    @Test
    public void testLoadConfig() throws Exception {
        String config =
                "Foo:\n" +
                "  bar: hello\n" +
                "  baz: 5\n" +
                "  bars:\n" +
                "    - a: hi\n" +
                "      b: bye";

        config = config
                .replace("Foo", Foo.class.getName())
                .replace("Bar", Foo.class.getName());

        Iterable<Component> components = ConfigLoader.loadConfig(config);

        for (Component component : components) {
            System.out.println(component);
        }

    }
}
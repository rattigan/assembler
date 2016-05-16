package com.github.rattigan.assembler;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.spi.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 */
public class GuiceHelper {
    private static final Logger log = LoggerFactory.getLogger(GuiceHelper.class);

    public static Set<Key<?>> getBindingKeys(Injector injector) {
        return injector.getBindings().keySet();
    }

    public static void dump(Module module) {
        Elements.getElements(module);
    }
}

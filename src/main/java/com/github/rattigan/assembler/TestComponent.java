package com.github.rattigan.assembler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class TestComponent extends Component {
    private static final Logger log = LoggerFactory.getLogger(TestComponent.class);

    private String message;

    @Override
    public void start() {
        log.info(message);
    }
}

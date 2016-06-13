package com.github.rattigan.assembler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;

/**
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        main(args, mapper -> {
        });
    }

    public static void main(String[] args, Consumer<ObjectMapper> configureMapper) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            configureMapper.accept(mapper);
            if (args.length != 1) {
                System.err.println("Usage: <command> <environment file or resource>");
                log.error("Error starting assembly: invalid parameters"  );
                System.exit(1);
            }
            String environment = args[0];
            String config = getConfig(environment);
            List<Component> components = new ConfigLoader(mapper).loadConfig(config);
            Assembly assembly = new Assembly(components);
            assembly.start();
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    assembly.stop();
                }
            });
        } catch (Throwable t) {
            log.error("Error starting assembly", t);
            System.exit(1);
        }

    }

    private static String getConfig(String environment) throws IOException {
        File configFile = new File(environment);
        if (configFile.exists())
            return new String(Files.readAllBytes(Paths.get(environment)), StandardCharsets.UTF_8);
        URL resource = Thread.currentThread().getContextClassLoader().getResource(environment);
        if (resource == null)
            throw new AssemblerException("Could no find file or resource for configuration " + environment);
        try {
            return new String(Files.readAllBytes(Paths.get(resource.toURI())), StandardCharsets.UTF_8);
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

}

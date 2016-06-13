package com.github.rattigan.assembler;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static com.github.rattigan.nonstd.seq.Seq.biseq;
import static com.github.rattigan.nonstd.seq.Seq.list;

/**
 */
public class ConfigLoader {
    private final ObjectMapper mapper;

    public ConfigLoader() {
        this(new ObjectMapper());
    }

    public ConfigLoader(ObjectMapper mapper) {
        this.mapper = mapper;
        configMapper(mapper);
    }

    private void configMapper(ObjectMapper mapper) {
        // allow private fields to be written
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    }

    public List<Component> loadConfig(String yaml) throws AssemblerException {
        Yaml parser = new Yaml();
        Map<String, Object> map = (Map<String, Object>) parser.loadAs(yaml, Object.class);
        return biseq(map).to(this::loadConfigEntry).collect(list());
    }

    private Component loadConfigEntry(String className, Object configuration) {
        Writer writer = new StringWriter();
        try {
            Class type = Thread.currentThread().getContextClassLoader().loadClass(className);
            mapper.writeValue(writer, configuration);
            return (Component) mapper.readValue(writer.toString(), type);
        } catch (Exception e) {
            throw new AssemblerException(e);
        }
    }
}

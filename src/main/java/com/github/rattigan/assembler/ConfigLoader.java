package com.github.rattigan.assembler;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static com.github.rattigan.nonstd.seq.Seq.list;
import static com.github.rattigan.nonstd.seq.Seq.seq;

/**
 */
public class ConfigLoader {
    private static ObjectMapper mapper = createMapper();

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // allow private fields to be written
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        return mapper;
    }

    public static List<Component> loadConfig(String yaml) throws AssemblerException {
        Yaml parser = new Yaml();
        Map<String, Object> map = (Map<String, Object>) parser.loadAs(yaml, Object.class);
        return seq(map).to(ConfigLoader::loadConfigEntry).collect(list());
    }

    private static Component loadConfigEntry(String className, Object configuration) {
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

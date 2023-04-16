package com.github.wenqiglantz.service.customer.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Common utility methods.
 */
@Slf4j
public class Utils {

    private Utils() {
    }

    /**
     * compare and append ) 0 to the given string
     *
     * @param sb      - the String
     * @param value   - value
     * @param compare - compare value
     */
    private static void prefixZeroes(StringBuilder sb, int value, int compare) {
        if (value < compare) {
            sb.append("0");
        }
    }

    /**
     * @return
     */
    public static LocalDateTime currentUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Convert object to String if it is not null
     *
     * @param obj - the object
     * @return the String value
     */
    public static String toStringNotNull(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    /**
     * Get object from JSON String
     *
     * @param jsonStr - JSON String
     * @param clazz   - Class Type
     * @param <T>     - the Type
     * @return the result
     */
    public static <T> T fromJson(String jsonStr, Class<T> clazz) {
        try {
            return new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule())
                    .readValue(jsonStr, clazz);
        } catch (IOException e) {
            log.error("Error converting fromJson", e);
        }
        return null;
    }

    /**
     * Get object from JSON String
     *
     * @param file  - JSON File
     * @param clazz - Class Type
     * @param <T>   - the Type
     * @return the result
     */
    public static <T> T fromJson(File file, Class<T> clazz) {
        try {
            return new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule())
                    .readValue(file, clazz);
        } catch (IOException e) {
            log.error("Error converting fromJson", e);
        }
        return null;
    }


    /**
     * Convert Object to JSON String
     *
     * @param obj - Object that need to convert
     * @return the JSON String
     */
    public static String toJson(Object obj) {
        try {
            return new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule())
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Error converting toJson", e);
        }
        return "{}";
    }
}

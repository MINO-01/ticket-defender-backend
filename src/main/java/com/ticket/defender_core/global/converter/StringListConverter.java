package com.ticket.defender_core.global.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> stringList) {
        if (stringList == null || stringList.isEmpty()) {
            return "";
        }
        return stringList.stream()
                .map(this::escapeSpecialCharacters)
                .collect(Collectors.joining(","));
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < dbData.length(); i++) {
            char c = dbData.charAt(i);

            if (c == '\"') {
                if (inQuotes && i + 1 < dbData.length() && dbData.charAt(i + 1) == '\"') {
                    current.append('\"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());

        return result;
    }

    private String escapeSpecialCharacters(String data) {
        if (data == null) return "";

        if (data.contains(",") || data.contains("\"")) {
            return "\"" + data.replace("\"", "\"\"") + "\"";
        }
        return data;
    }
}
package com.hhg.callgraph.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FieldAccessIndex {

    private final Map<FieldReference, Set<MethodReference>> readers = new HashMap<>();
    private final Map<FieldReference, Set<MethodReference>> writers = new HashMap<>();

    public void addRead(MethodReference accessor, FieldReference field) {
        readers.computeIfAbsent(field, k -> new HashSet<>()).add(accessor);
    }

    public void addWrite(MethodReference accessor, FieldReference field) {
        writers.computeIfAbsent(field, k -> new HashSet<>()).add(accessor);
    }

    public Set<MethodReference> getReaders(FieldReference field) {
        return Collections.unmodifiableSet(readers.getOrDefault(field, Collections.emptySet()));
    }

    public Set<MethodReference> getWriters(FieldReference field) {
        return Collections.unmodifiableSet(writers.getOrDefault(field, Collections.emptySet()));
    }

    public Set<MethodReference> getAccessors(FieldReference field) {
        Set<MethodReference> result = new HashSet<>();
        result.addAll(readers.getOrDefault(field, Collections.emptySet()));
        result.addAll(writers.getOrDefault(field, Collections.emptySet()));
        return Collections.unmodifiableSet(result);
    }

    public Set<FieldReference> getAllFields() {
        Set<FieldReference> all = new HashSet<>();
        all.addAll(readers.keySet());
        all.addAll(writers.keySet());
        return Collections.unmodifiableSet(all);
    }

    public void mergeFrom(FieldAccessIndex other) {
        for (var entry : other.readers.entrySet()) {
            for (MethodReference reader : entry.getValue()) {
                addRead(reader, entry.getKey());
            }
        }
        for (var entry : other.writers.entrySet()) {
            for (MethodReference writer : entry.getValue()) {
                addWrite(writer, entry.getKey());
            }
        }
    }

    public Set<FieldReference> findByName(String internalClass, String fieldName) {
        Set<FieldReference> result = new HashSet<>();
        for (FieldReference field : getAllFields()) {
            if (field.className().equals(internalClass) && field.fieldName().equals(fieldName)) {
                result.add(field);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
